// The control plane, as a browser sees it.
//
// Three rules of the server shape every function here:
//  - every request carries a bearer token: a signed-in session (ADR-024) or a
//    configured service token (ADR-013);
//  - every mutation of an existing resource carries If-Match with the ETag the
//    caller last read (ADR-009) -- so the functions that mutate take the tag
//    explicitly, and the ones that read a single resource return it;
//  - every error is a problem detail with a stable `type` (ADR-007), turned
//    into an ApiProblem the views can branch on.
import type {
  Agent, AgentProfile, AgentTemplate, AgentWrite, AutonomyLevel, DailyItem, InstalledAgent, Resource, Handoff, HandoffOutcome, LaunchResult, ModelCatalog, ModelList, Orchestration,
  Phase, Plan, PlanRun, Project, ProjectType, ProjectTypeInfo, ProjectWrite, Provider, Review, Run, ScaffoldEntry,
  Software, Suggestion, Task, TaskCreate, TaskStatus, TaskUpdate, Transition, UsageWindow, WorkspaceDocument,
  WorkspaceDocuments, Me, Role, SecurityEventInfo, UserInfo,
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
  /** Who signed in (ADR-024); absent for a service-token session. */
  user?: UserInfo;
}

type Method = "GET" | "POST" | "PUT" | "DELETE";

export class ControlPlane {
  constructor(private readonly session: Session, private readonly fetcher: typeof fetch = fetch.bind(globalThis)) {}

  private async request<T>(method: Method, path: string, options: { body?: unknown; ifMatch?: string } = {}):
    Promise<{ body: T; etag: string | null; status: number }> {
    const headers: Record<string, string> = {
      Accept: "application/json, application/problem+json",
    };
    if (this.session.token) headers.Authorization = `Bearer ${this.session.token}`;
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

  // --- people and sessions (ADR-024) -----------------------------------------

  /** Signs in and returns the session the console keeps. The one call without a token. */
  static async login(baseUrl: string, username: string, password: string,
                     fetcher: typeof fetch = fetch.bind(globalThis)): Promise<Session> {
    const base = baseUrl.trim().replace(/\/+$/, "");
    const anonymous = new ControlPlane({ baseUrl: base, token: "" }, fetcher);
    const body = await anonymous.plain<{ token: string; expiresAt: string; user: UserInfo }>(
      "POST", "/api/auth/login", { body: { username, password } });
    return { baseUrl: base, token: body.token, user: body.user };
  }

  logout(): Promise<void> {
    return this.plain("POST", "/api/auth/logout");
  }

  me(): Promise<Me> {
    return this.plain("GET", "/api/auth/me");
  }

  changePassword(currentPassword: string, newPassword: string): Promise<void> {
    return this.plain("PUT", "/api/auth/password", { body: { currentPassword, newPassword } });
  }

  users(): Promise<UserInfo[]> {
    return this.plain("GET", "/api/admin/users");
  }

  createUser(user: { username: string; displayName?: string; role: Role; initialPassword: string }): Promise<UserInfo> {
    return this.plain("POST", "/api/admin/users", { body: user });
  }

  updateUser(id: number, version: number, update: { displayName: string | null; role: Role; enabled: boolean }): Promise<UserInfo> {
    return this.plain("PUT", `/api/admin/users/${id}`, { body: update, ifMatch: `"${version}"` });
  }

  resetUserPassword(id: number, version: number, temporaryPassword: string): Promise<UserInfo> {
    return this.plain("PUT", `/api/admin/users/${id}/password`, { body: { temporaryPassword }, ifMatch: `"${version}"` });
  }

  securityEvents(limit = 100): Promise<SecurityEventInfo[]> {
    return this.plain("GET", `/api/admin/security-events?limit=${limit}`);
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

  // --- project workspace (ADR-020) --------------------------------------------

  projectTypes(): Promise<ProjectTypeInfo[]> {
    return this.plain("GET", "/api/catalog/project-types");
  }

  defaultFolder(name: string): Promise<{ path: string }> {
    return this.plain("GET", `/api/workspace/default-folder?name=${encodeURIComponent(name)}`);
  }

  configureProject(id: number, etag: string, profile: { projectType?: ProjectType | null; stack?: string | null;
    workspacePath?: string | null; autonomyLevel?: AutonomyLevel | null }): Promise<Versioned<Project>> {
    return this.versioned("PUT", `/api/projects/${id}/profile`, { body: profile, ifMatch: etag });
  }

  scaffold(id: number): Promise<ScaffoldEntry[]> {
    return this.plain("POST", `/api/projects/${id}/workspace`);
  }

  documents(id: number): Promise<WorkspaceDocuments> {
    return this.plain("GET", `/api/projects/${id}/documents`);
  }

  async readFile(id: number, path: string): Promise<string> {
    const response = await this.fetcher(this.session.baseUrl.replace(/\/+$/, "")
      + `/api/projects/${id}/files?path=${encodeURIComponent(path)}`,
      { headers: { Authorization: `Bearer ${this.session.token}` } });
    if (!response.ok) {
      const text = await response.text();
      throw new ApiProblem(response.status, (text ? safeJson(text) : {}) as object);
    }
    return response.text();
  }

  async fileUrl(id: number, path: string): Promise<string | null> {
    try {
      const response = await this.fetcher(this.session.baseUrl.replace(/\/+$/, "")
        + `/api/projects/${id}/files?path=${encodeURIComponent(path)}`,
        { headers: { Authorization: `Bearer ${this.session.token}` } });
      return response.ok ? URL.createObjectURL(await response.blob()) : null;
    } catch {
      return null;
    }
  }

  writeFile(id: number, path: string, content: string): Promise<WorkspaceDocument> {
    return this.plain("PUT", `/api/projects/${id}/files?path=${encodeURIComponent(path)}`, { body: { content } });
  }

  // --- planning (ADR-021) -----------------------------------------------------

  plan(id: number): Promise<Plan> {
    return this.plain("GET", `/api/projects/${id}/plan`);
  }

  generatePlan(id: number, model?: string): Promise<PlanRun> {
    return this.plain("POST", `/api/projects/${id}/plan/generate`, { body: model ? { model } : {} });
  }

  /** The planning request as an external agent reads it (text). */
  async readPlanHandoff(id: number): Promise<string> {
    const response = await this.fetcher(this.session.baseUrl.replace(/\/+$/, "") + `/api/projects/${id}/plan/handoff`,
      { headers: { Authorization: `Bearer ${this.session.token}`, Accept: "text/plain, application/problem+json" } });
    const text = await response.text();
    if (!response.ok) throw new ApiProblem(response.status, (text ? safeJson(text) : {}) as object);
    return text;
  }

  importPlan(id: number): Promise<PlanRun> {
    return this.plain("POST", `/api/projects/${id}/plan/import`);
  }

  planRun(runId: number): Promise<PlanRun> {
    return this.plain("GET", `/api/plan-runs/${runId}`);
  }

  approvePlan(id: number, etag: string, approveAllPhases: boolean): Promise<Versioned<Project>> {
    return this.versioned("POST", `/api/projects/${id}/plan/approve?approveAllPhases=${approveAllPhases}`, { ifMatch: etag });
  }

  reviewPhase(phase: Phase, approve: boolean, note?: string): Promise<Phase> {
    return this.plain("POST", `/api/phases/${phase.id}/${approve ? "approve" : "request-changes"}`,
      { body: note ? { note } : {}, ifMatch: `"${phase.version}"` });
  }

  // --- orchestration (ADR-021 §3-5) -------------------------------------------

  orchestration(taskId: number): Promise<Orchestration> {
    return this.plain("GET", `/api/tasks/${taskId}/orchestration`);
  }

  handoff(taskId: number, etag: string, target: string): Promise<HandoffOutcome> {
    return this.plain("POST", `/api/tasks/${taskId}/handoffs`, { body: { target }, ifMatch: etag });
  }

  handoffs(taskId: number): Promise<Handoff[]> {
    return this.plain("GET", `/api/tasks/${taskId}/handoffs`);
  }

  review(taskId: number, etag: string, verdict: "ACCEPTED" | "CHANGES_REQUESTED", note?: string,
    link: { runId?: number; handoffId?: number } = {}): Promise<unknown> {
    return this.plain("POST", `/api/tasks/${taskId}/reviews`, { body: { verdict, note, ...link }, ifMatch: etag });
  }

  reviews(taskId: number): Promise<Review[]> {
    return this.plain("GET", `/api/tasks/${taskId}/reviews`);
  }

  // --- second brain (directive §17) ---------------------------------------------

  graph(): Promise<{ nodes: { id: string; type: string; label: string; status: string | null; meta: Record<string, unknown> }[];
    edges: { source: string; target: string; kind: string }[] }> {
    return this.plain("GET", "/api/graph");
  }

  // --- daily work (directive §18) ----------------------------------------------

  daily(from: string, to: string): Promise<DailyItem[]> {
    return this.plain("GET", `/api/daily?from=${from}&to=${to}`);
  }

  createDaily(item: Record<string, unknown>): Promise<DailyItem> {
    return this.plain("POST", "/api/daily", { body: item });
  }

  updateDaily(item: DailyItem, changes: Record<string, unknown>): Promise<DailyItem> {
    return this.plain("PUT", `/api/daily/${item.id}`, {
      body: { day: item.day, title: item.task ? null : item.title, notes: item.notes, status: item.status,
        priority: item.priority, dueTime: item.dueTime, position: item.position, ...changes },
      ifMatch: `"${item.version}"`,
    });
  }

  removeDaily(item: DailyItem): Promise<void> {
    return this.plain("DELETE", `/api/daily/${item.id}`, { ifMatch: `"${item.version}"` });
  }

  carryOver(from: string, to: string): Promise<DailyItem[]> {
    return this.plain("POST", `/api/daily/carry-over?from=${from}&to=${to}`);
  }

  // --- agent ecosystem (ADR-023) -----------------------------------------------

  agentProfiles(): Promise<AgentProfile[]> {
    return this.plain("GET", "/api/agent-profiles");
  }

  agentProfile(id: number): Promise<Versioned<AgentProfile>> {
    return this.versioned("GET", `/api/agents/${id}/profile`);
  }

  configureAgent(id: number, etag: string, profile: Record<string, unknown>): Promise<Versioned<AgentProfile>> {
    return this.versioned("PUT", `/api/agents/${id}/profile`, { body: profile, ifMatch: etag });
  }

  attachResource(agentId: number, key: string): Promise<void> {
    return this.plain("PUT", `/api/agents/${agentId}/resources/${encodeURIComponent(key)}`);
  }

  detachResource(agentId: number, key: string): Promise<void> {
    return this.plain("DELETE", `/api/agents/${agentId}/resources/${encodeURIComponent(key)}`);
  }

  allowSoftware(agentId: number, key: string): Promise<void> {
    return this.plain("PUT", `/api/agents/${agentId}/software/${encodeURIComponent(key)}`);
  }

  disallowSoftware(agentId: number, key: string): Promise<void> {
    return this.plain("DELETE", `/api/agents/${agentId}/software/${encodeURIComponent(key)}`);
  }

  resources(kind?: string, q?: string): Promise<Resource[]> {
    const params = new URLSearchParams();
    if (kind) params.set("kind", kind);
    if (q) params.set("q", q);
    const query = params.toString();
    return this.plain("GET", `/api/resources${query ? `?${query}` : ""}`);
  }

  adoptResource(projectId: number, key: string): Promise<void> {
    return this.plain("PUT", `/api/projects/${projectId}/resources/${encodeURIComponent(key)}`);
  }

  agentTemplates(): Promise<AgentTemplate[]> {
    return this.plain("GET", "/api/agent-templates");
  }

  installAgentTemplate(key: string): Promise<InstalledAgent[]> {
    return this.plain("POST", `/api/agent-templates/${encodeURIComponent(key)}/install`);
  }

  installAllAgentTemplates(): Promise<InstalledAgent[]> {
    return this.plain("POST", "/api/agent-templates/install-all");
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
