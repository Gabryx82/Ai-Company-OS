// The control plane, as a browser sees it.
//
// Three rules of the server shape every function here:
//  - every request carries the operator's bearer token (ADR-013);
//  - every mutation of an existing resource carries If-Match with the ETag the
//    caller last read (ADR-009) -- so the functions that mutate take the tag
//    explicitly, and the ones that read a single resource return it;
//  - every error is a problem detail with a stable `type` (ADR-007), turned
//    into an ApiProblem the views can branch on.
import type {
  Agent, AgentWrite, LaunchResult, ModelCatalog, ModelList, Project, ProjectWrite, Provider, Run, Software,
  Suggestion, Task, TaskCreate, TaskStatus, TaskUpdate, Transition, UsageWindow,
} from "./types";

const PROBLEM = "urn:ai-company-os:problem:";

export class ApiProblem extends Error {
  readonly type: string;
  readonly status: number;
  readonly title: string;
  readonly detail: string;
  readonly errors: Record<string, string>;

  constructor(status: number, body: Partial<{ type: string; title: string; detail: string; errors: Record<string, string> }>) {
    super(body.detail ?? body.title ?? `HTTP ${status}`);
    this.status = status;
    this.type = body.type ?? `${PROBLEM}unknown`;
    this.title = body.title ?? `HTTP ${status}`;
    this.detail = body.detail ?? "";
    this.errors = body.errors ?? {};
  }

  /** The short identifier, e.g. "precondition-failed". */
  get slug(): string {
    return this.type.startsWith(PROBLEM) ? this.type.slice(PROBLEM.length) : this.type;
  }

  /** Somebody else wrote since this caller read: re-read, then decide again. */
  get isStale(): boolean {
    return this.status === 412;
  }

  get isUnauthenticated(): boolean {
    return this.status === 401;
  }
}

export interface Versioned<T> {
  body: T;
  etag: string;
}

export interface Session {
  baseUrl: string;
  token: string;
}

type Method = "GET" | "POST" | "PUT";

export class ControlPlane {
  constructor(private readonly session: Session, private readonly fetcher: typeof fetch = fetch.bind(globalThis)) {}

  private async request<T>(method: Method, path: string, options: { body?: unknown; ifMatch?: string } = {}):
    Promise<{ body: T; etag: string | null; status: number }> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.session.token}`,
      Accept: "application/json, application/problem+json",
    };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (options.ifMatch !== undefined) headers["If-Match"] = options.ifMatch;

    let response: Response;
    try {
      response = await this.fetcher(this.session.baseUrl.replace(/\/+$/, "") + path, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      });
    } catch {
      throw new ApiProblem(0, {
        type: `${PROBLEM}control-plane-unreachable`,
        title: "Control plane unreachable",
        detail: `Nothing answered at ${this.session.baseUrl}. Is the backend running, and is this page served from an allowed origin?`,
      });
    }

    const text = await response.text();
    const parsed = text ? safeJson(text) : null;
    if (!response.ok) {
      throw new ApiProblem(response.status, (parsed as object | null) ?? {});
    }
    return { body: parsed as T, etag: response.headers.get("ETag"), status: response.status };
  }

  private async versioned<T>(method: Method, path: string, options: { body?: unknown; ifMatch?: string } = {}):
    Promise<Versioned<T>> {
    const { body, etag } = await this.request<T>(method, path, options);
    if (!etag) {
      // Without the tag the next write would be a 428. A server that stops
      // sending it -- or a CORS policy that hides it -- must fail here, loudly.
      throw new ApiProblem(0, { type: `${PROBLEM}missing-etag`, title: "Missing ETag",
        detail: `${method} ${path} answered without an ETag the page can read` });
    }
    return { body, etag };
  }

  private async plain<T>(method: Method, path: string, options: { body?: unknown; ifMatch?: string } = {}): Promise<T> {
    return (await this.request<T>(method, path, options)).body;
  }

  // --- liveness ---------------------------------------------------------------

  async health(): Promise<boolean> {
    try {
      const response = await this.fetcher(this.session.baseUrl.replace(/\/+$/, "") + "/actuator/health");
      return response.ok;
    } catch {
      return false;
    }
  }

  // --- tasks ------------------------------------------------------------------

  tasks(status?: TaskStatus): Promise<Task[]> {
    return this.plain("GET", status ? `/api/tasks?status=${status}` : "/api/tasks");
  }

  task(id: number): Promise<Versioned<Task>> {
    return this.versioned("GET", `/api/tasks/${id}`);
  }

  createTask(task: TaskCreate): Promise<Versioned<Task>> {
    return this.versioned("POST", "/api/tasks", { body: task });
  }

  updateTask(id: number, etag: string, details: TaskUpdate): Promise<Versioned<Task>> {
    return this.versioned("PUT", `/api/tasks/${id}`, { body: details, ifMatch: etag });
  }

  transition(id: number, step: Transition, etag: string): Promise<Versioned<Task>> {
    return this.versioned("POST", `/api/tasks/${id}/${step}`, { ifMatch: etag });
  }

  assignAgent(id: number, etag: string, agentId: number): Promise<Versioned<Task>> {
    return this.versioned("PUT", `/api/tasks/${id}/agent`, { body: { agentId }, ifMatch: etag });
  }

  assignProject(id: number, etag: string, projectId: number): Promise<Versioned<Task>> {
    return this.versioned("PUT", `/api/tasks/${id}/project`, { body: { projectId }, ifMatch: etag });
  }

  suggestions(taskId: number): Promise<Suggestion[]> {
    return this.plain("GET", `/api/tasks/${taskId}/agent-suggestions`);
  }

  // --- runs -------------------------------------------------------------------

  /** Takes the TASK's tag: launching may start the task (ADR-016 §2). */
  launchRun(taskId: number, taskEtag: string, model?: string): Promise<Run> {
    return this.plain("POST", `/api/tasks/${taskId}/runs`, { body: model ? { model } : {}, ifMatch: taskEtag });
  }

  runs(taskId: number): Promise<Run[]> {
    return this.plain("GET", `/api/tasks/${taskId}/runs`);
  }

  run(id: number): Promise<Run> {
    return this.plain("GET", `/api/runs/${id}`);
  }

  engineModels(): Promise<ModelList> {
    return this.plain("GET", "/api/engine/models");
  }

  // --- agents -----------------------------------------------------------------

  agents(): Promise<Agent[]> {
    return this.plain("GET", "/api/agents");
  }

  agent(id: number): Promise<Versioned<Agent>> {
    return this.versioned("GET", `/api/agents/${id}`);
  }

  createAgent(agent: AgentWrite): Promise<Versioned<Agent>> {
    return this.versioned("POST", "/api/agents", { body: agent });
  }

  updateAgent(id: number, etag: string, agent: AgentWrite): Promise<Versioned<Agent>> {
    return this.versioned("PUT", `/api/agents/${id}`, { body: agent, ifMatch: etag });
  }

  setAgentActive(id: number, etag: string, active: boolean): Promise<Versioned<Agent>> {
    return this.versioned("POST", `/api/agents/${id}/${active ? "activate" : "deactivate"}`, { ifMatch: etag });
  }

  // --- projects ---------------------------------------------------------------

  projects(): Promise<Project[]> {
    return this.plain("GET", "/api/projects");
  }

  project(id: number): Promise<Versioned<Project>> {
    return this.versioned("GET", `/api/projects/${id}`);
  }

  createProject(project: ProjectWrite): Promise<Versioned<Project>> {
    return this.versioned("POST", "/api/projects", { body: project });
  }

  setProjectArchived(id: number, etag: string, archived: boolean): Promise<Versioned<Project>> {
    return this.versioned("POST", `/api/projects/${id}/${archived ? "archive" : "restore"}`, { ifMatch: etag });
  }

  // --- software hub (ADR-019) -------------------------------------------------

  software(): Promise<Software[]> {
    return this.plain("GET", "/api/software");
  }

  softwareEntry(key: string): Promise<Versioned<Software>> {
    return this.versioned("GET", `/api/software/${encodeURIComponent(key)}`);
  }

  updateSoftware(key: string, etag: string, entry: Record<string, unknown>): Promise<Versioned<Software>> {
    return this.versioned("PUT", `/api/software/${encodeURIComponent(key)}`, { body: entry, ifMatch: etag });
  }

  createSoftware(entry: Record<string, unknown>): Promise<Versioned<Software>> {
    return this.versioned("POST", "/api/software", { body: entry });
  }

  /** The body may name a project; the command line always comes from the catalog. */
  launch(key: string, projectId?: number): Promise<LaunchResult> {
    return this.plain("POST", `/api/software/${encodeURIComponent(key)}/launch`,
      { body: projectId ? { projectId } : {} });
  }

  refreshSoftware(): Promise<void> {
    return this.plain("POST", "/api/software/refresh");
  }

  /** The program's real icon, fetched with the token and handed back as an object URL (or null). */
  async softwareIcon(key: string): Promise<string | null> {
    try {
      const response = await this.fetcher(
        this.session.baseUrl.replace(/\/+$/, "") + `/api/software/${encodeURIComponent(key)}/icon`,
        { headers: { Authorization: `Bearer ${this.session.token}` } });
      if (response.status !== 200) return null;
      return URL.createObjectURL(await response.blob());
    } catch {
      return null;
    }
  }

  // --- catalogs of providers and models (ADR-018) -------------------------------

  providers(): Promise<Provider[]> {
    return this.plain("GET", "/api/catalog/providers");
  }

  modelCatalog(): Promise<ModelCatalog> {
    return this.plain("GET", "/api/catalog/models");
  }

  // --- usage ------------------------------------------------------------------

  usage(): Promise<UsageWindow[]> {
    return this.plain("GET", "/api/usage");
  }

  anchorQuota(key: string, etag: string, anchor: { resetWeekday?: number | null; resetTime?: string | null;
    resetZone?: string | null; limitNote?: string | null }): Promise<void> {
    return this.plain("PUT", `/api/usage/plans/${encodeURIComponent(key)}`, { body: anchor, ifMatch: etag });
  }
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text.slice(0, 200) };
  }
}
