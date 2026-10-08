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
  Phase, Plan, PlanRun, Project, ProjectType, ProjectTypeInfo, ProjectWrite, Provider, Review, Run, ScaffoldEntry, TaskMove, TaskOutcome, Learning,
  Software, Suggestion, Task, TaskCreate, TaskStatus, TaskUpdate, Transition, UsageWindow, WorkspaceDocument,
  WorkspaceDocuments, Me, Role, SecurityEventInfo, UserInfo, AgentConfiguration, AgentConfigurationWrite, Binding,
  ExecutionTarget, LibraryInfo, LibrarySync, ResourceFile, DeletionImpact, TasksPolicy, DeletionPreview, SubAgentsPolicy, CatalogRetirement,
  InProgressPolicy, PlannedAgent, EcosystemServiceInfo, TerminalShell, TerminalTicket, TerminalSession, CodeGraph, CostSummary,
  AiAction, BlueprintActionOutcome, BlueprintAiAction, BlueprintBlockDetail, BlueprintBlockWrite, BlueprintCanvasLayout, BlueprintGraph, BlueprintPoint,
  BlueprintProposal, BlueprintProposalWrite, BlueprintRelation, BlueprintRelationWrite, BlueprintScanReport, BlueprintSnippet,
  BlueprintVocabulary, HubInstalled, HubFoundDetail, HubSyncReport, HubSyncEvent, HubMarketplace, HubSearchResults,
  HubResult, HubPreview, HubInstallResult, HubCopyTarget, HubDeployResult, HubUpdates, HubApplied,
  HubOfferKind, HubExtensionPreview, HubInstallOutcome,
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

  updateProfile(displayName: string): Promise<UserInfo> {
    return this.plain("PUT", "/api/auth/profile", { body: { displayName } });
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

  /** `agentStatus: "INACTIVE"` -- the tasks waiting on a switched-off agent (PHASE 32, TD-34). */
  tasks(status?: TaskStatus, agentStatus?: "ACTIVE" | "INACTIVE"): Promise<Task[]> {
    const query = new URLSearchParams();
    if (status) query.set("status", status);
    if (agentStatus) query.set("agentStatus", agentStatus);
    return this.plain("GET", query.size ? `/api/tasks?${query}` : "/api/tasks");
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

  /** PHASE 32 (TD-35): the task goes back to nobody; the agent stays. */
  unassignAgent(id: number, etag: string): Promise<Versioned<Task>> {
    return this.versioned("DELETE", `/api/tasks/${id}/agent`, { ifMatch: etag });
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

  /** PHASE 32 (TD-39): stops a queued or running run; a finished one comes back as it is. */
  cancelRun(runId: number): Promise<Run> {
    return this.plain("POST", `/api/runs/${runId}/cancel`);
  }

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

  /** PHASE 32 (TD-43): stops a generation that is running; a finished one comes back as it is. */
  cancelPlanRun(runId: number): Promise<PlanRun> {
    return this.plain("POST", `/api/plan-runs/${runId}/cancel`);
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

  /** PHASE 32 (TD-45): learning metrics and the suggested level; applying it is the operator's choice. */
  learning(projectId: number): Promise<Learning> {
    return this.plain("GET", `/api/projects/${projectId}/learning`);
  }

  /** PHASE 32 (TD-55): what the task produced, the most recent first; empty while nothing has. */
  taskOutcomes(taskId: number): Promise<TaskOutcome[]> {
    return this.plain("GET", `/api/tasks/${taskId}/outcomes`);
  }

  /** PHASE 32 (TD-38): who moved the task, when and through what, the most recent first. */
  taskTransitions(taskId: number): Promise<TaskMove[]> {
    return this.plain("GET", `/api/tasks/${taskId}/transitions`);
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

  // --- visual references (PHASE 25) -----------------------------------------------

  /** An image into the project's references/: the raw bytes, never JSON. */
  async uploadReference(projectId: number, folder: string, file: Blob, name: string): Promise<WorkspaceDocument> {
    const url = `${this.session.baseUrl.replace(/\/+$/, "")}/api/projects/${projectId}/references`
      + `?folder=${encodeURIComponent(folder)}&name=${encodeURIComponent(name)}`;
    let response: Response;
    try {
      response = await this.fetcher(url, { method: "POST", body: file, headers: {
        Authorization: `Bearer ${this.session.token}`, Accept: "application/json, application/problem+json",
        "Content-Type": file.type || "application/octet-stream" } });
    } catch {
      throw new ApiProblem(0, { type: `${PROBLEM}control-plane-unreachable`, title: "Control plane unreachable" });
    }
    const text = await response.text();
    const parsed = text ? safeJson(text) : null;
    if (!response.ok) throw new ApiProblem(response.status, (parsed as object | null) ?? {});
    return parsed as WorkspaceDocument;
  }

  // --- cost governance (ADR-031) --------------------------------------------------

  costs(): Promise<CostSummary> {
    return this.plain("GET", "/api/costs");
  }

  setBudget(provider: string, monthlyLimitUsd: number, alertPercent: number, etag?: string): Promise<unknown> {
    return this.plain("PUT", `/api/admin/costs/budgets/${encodeURIComponent(provider)}`,
      { body: { monthlyLimitUsd, alertPercent }, ifMatch: etag });
  }

  setModelPrice(model: string, version: number, inputPricePerMtok: number | null, outputPricePerMtok: number | null): Promise<unknown> {
    return this.plain("PUT", `/api/admin/costs/prices/${encodeURIComponent(model)}`,
      { body: { inputPricePerMtok, outputPricePerMtok }, ifMatch: `"${version}"` });
  }

  // --- the code graph (ADR-030) -------------------------------------------------

  async codeGraph(projectId: number): Promise<CodeGraph | null> {
    const graph = await this.plain<CodeGraph | null>("GET", `/api/projects/${projectId}/code-graph`);
    return graph ?? null;
  }

  scanCodeGraph(projectId: number): Promise<CodeGraph> {
    return this.plain("POST", `/api/projects/${projectId}/code-graph`);
  }

  // --- the Project Blueprint (ADR-033) ------------------------------------------------
  // Blocks are read one at a time with their ETag; relations, the layout and
  // proposals carry their version in the body, and the tag is built from it.

  blueprintVocabulary(): Promise<BlueprintVocabulary> {
    return this.plain("GET", "/api/blueprint/vocabulary");
  }

  /**
   * PHASE 32 (TD-61): `outline` leaves the texts out; `within` keeps one area of the canvas
   * ([x0, y0, x1, y1] in canvas coordinates) and the relations between its blocks.
   */
  blueprint(projectId: number, view: { outline?: boolean; within?: number[] } = {}): Promise<BlueprintGraph> {
    const query = new URLSearchParams();
    if (view.outline) query.set("detail", "outline");
    if (view.within) query.set("within", view.within.map((v) => Math.round(v)).join(","));
    const suffix = query.toString() ? `?${query}` : "";
    return this.plain("GET", `/api/projects/${projectId}/blueprint${suffix}`);
  }

  scanBlueprint(projectId: number): Promise<BlueprintScanReport> {
    return this.plain("POST", `/api/projects/${projectId}/blueprint/scan`);
  }

  blueprintNode(projectId: number, nodeId: number): Promise<Versioned<BlueprintBlockDetail>> {
    return this.versioned("GET", `/api/projects/${projectId}/blueprint/nodes/${nodeId}`);
  }

  createBlueprintNode(projectId: number, block: BlueprintBlockWrite): Promise<Versioned<BlueprintBlockDetail>> {
    return this.versioned("POST", `/api/projects/${projectId}/blueprint/nodes`, { body: block });
  }

  updateBlueprintNode(projectId: number, nodeId: number, etag: string, block: BlueprintBlockWrite): Promise<Versioned<BlueprintBlockDetail>> {
    return this.versioned("PUT", `/api/projects/${projectId}/blueprint/nodes/${nodeId}`, { body: block, ifMatch: etag });
  }

  async deleteBlueprintNode(projectId: number, nodeId: number, etag: string): Promise<void> {
    await this.plain("DELETE", `/api/projects/${projectId}/blueprint/nodes/${nodeId}`, { ifMatch: etag });
  }

  revertBlueprintNode(projectId: number, nodeId: number, etag: string, fields: string[]): Promise<Versioned<BlueprintBlockDetail>> {
    return this.versioned("POST", `/api/projects/${projectId}/blueprint/nodes/${nodeId}/revert`, { body: { fields }, ifMatch: etag });
  }

  regenerateBlueprintPseudocode(projectId: number, nodeId: number, etag: string): Promise<Versioned<BlueprintBlockDetail>> {
    return this.versioned("POST", `/api/projects/${projectId}/blueprint/nodes/${nodeId}/pseudocode`, { ifMatch: etag });
  }

  setBlueprintNodeExpanded(projectId: number, nodeId: number, etag: string, expanded: boolean): Promise<Versioned<BlueprintBlockDetail>> {
    return this.versioned("POST", `/api/projects/${projectId}/blueprint/nodes/${nodeId}/${expanded ? "expand" : "collapse"}`,
      { ifMatch: etag });
  }

  blueprintSnippet(projectId: number, nodeId: number, full = false): Promise<BlueprintSnippet> {
    return this.plain("GET", `/api/projects/${projectId}/blueprint/nodes/${nodeId}/snippet${full ? "?full=true" : ""}`);
  }

  /** PHASE 32 (TD-63): the AI actions asked about a block, with their outcomes; nothing is applied by reading them. */
  blueprintAiActions(projectId: number, nodeId: number): Promise<BlueprintAiAction[]> {
    return this.plain("GET", `/api/projects/${projectId}/blueprint/nodes/${nodeId}/ai-actions`);
  }

  blueprintAiAction(projectId: number, nodeId: number, action: AiAction): Promise<BlueprintActionOutcome> {
    return this.plain("POST", `/api/projects/${projectId}/blueprint/nodes/${nodeId}/ai-actions`, { body: { action } });
  }

  createBlueprintEdge(projectId: number, relation: BlueprintRelationWrite): Promise<BlueprintRelation> {
    return this.plain("POST", `/api/projects/${projectId}/blueprint/edges`, { body: relation });
  }

  updateBlueprintEdge(projectId: number, edgeId: number, version: number, relation: BlueprintRelationWrite): Promise<BlueprintRelation> {
    return this.plain("PUT", `/api/projects/${projectId}/blueprint/edges/${edgeId}`, { body: relation, ifMatch: `"${version}"` });
  }

  async deleteBlueprintEdge(projectId: number, edgeId: number, version: number): Promise<void> {
    await this.plain("DELETE", `/api/projects/${projectId}/blueprint/edges/${edgeId}`, { ifMatch: `"${version}"` });
  }

  confirmBlueprintEdge(projectId: number, edgeId: number, version: number): Promise<BlueprintRelation> {
    return this.plain("POST", `/api/projects/${projectId}/blueprint/edges/${edgeId}/confirm`, { ifMatch: `"${version}"` });
  }

  saveBlueprintLayout(projectId: number, version: number, positions: Record<string, BlueprintPoint>): Promise<BlueprintCanvasLayout> {
    return this.plain("PUT", `/api/projects/${projectId}/blueprint/layout`, { body: { positions }, ifMatch: `"${version}"` });
  }

  autoBlueprintLayout(projectId: number, version: number): Promise<BlueprintCanvasLayout> {
    return this.plain("POST", `/api/projects/${projectId}/blueprint/layout/auto`, { ifMatch: `"${version}"` });
  }

  blueprintProposals(projectId: number): Promise<BlueprintProposal[]> {
    return this.plain("GET", `/api/projects/${projectId}/blueprint/proposals`);
  }

  proposeBlueprintChange(projectId: number, proposal: BlueprintProposalWrite): Promise<BlueprintProposal> {
    return this.plain("POST", `/api/projects/${projectId}/blueprint/proposals`, { body: proposal });
  }

  decideBlueprintProposal(projectId: number, proposalId: number, version: number, accept: boolean): Promise<BlueprintProposal> {
    return this.plain("POST", `/api/projects/${projectId}/blueprint/proposals/${proposalId}/${accept ? "accept" : "reject"}`,
      { ifMatch: `"${version}"` });
  }

  // --- the integrated terminal (ADR-029) ---------------------------------------

  terminalShells(): Promise<TerminalShell[]> {
    return this.plain("GET", "/api/terminal/shells");
  }

  terminalTicket(shell: string, projectId?: number): Promise<TerminalTicket> {
    return this.plain("POST", "/api/terminal/tickets", { body: { shell, projectId: projectId ?? null } });
  }

  /** A ticket to show again one of my running shells (ADR-029 §5): it may be detached, or shown elsewhere. */
  terminalAttachTicket(session: string): Promise<TerminalTicket> {
    return this.plain("POST", "/api/terminal/tickets", { body: { attach: session } });
  }

  /** My running shells, shown on a socket or detached. */
  terminalSessions(): Promise<TerminalSession[]> {
    return this.plain("GET", "/api/terminal/sessions");
  }

  closeTerminalSession(session: string): Promise<void> {
    return this.plain("DELETE", `/api/terminal/sessions/${encodeURIComponent(session)}`);
  }

  /** Where the terminal's socket is, for the base URL this session talks to. */
  terminalSocketUrl(): string {
    return this.session.baseUrl.replace(/\/+$/, "").replace(/^http/, "ws") + "/api/terminal/ws";
  }

  // --- the ecosystem's start (ADR-028) -----------------------------------------

  ecosystemServices(): Promise<EcosystemServiceInfo[]> {
    return this.plain("GET", "/api/ecosystem/services");
  }

  startEcosystemService(key: string): Promise<EcosystemServiceInfo> {
    return this.plain("POST", `/api/ecosystem/services/${encodeURIComponent(key)}/start`);
  }

  startEcosystem(): Promise<EcosystemServiceInfo[]> {
    return this.plain("POST", "/api/ecosystem/start-all");
  }

  configureEcosystemService(key: string, version: number, body: { autostart: boolean; position: number; timeoutSeconds: number }):
    Promise<EcosystemServiceInfo> {
    return this.plain("PUT", `/api/admin/ecosystem/services/${encodeURIComponent(key)}`, { body, ifMatch: `"${version}"` });
  }

  /** PHASE 32 (TD-69): taking a service an admin added out of the start; a default one is refused. */
  ecosystemServiceRemovalPreview(key: string): Promise<DeletionPreview> {
    return this.plain("GET", `/api/admin/ecosystem/services/${encodeURIComponent(key)}/deletion`);
  }

  async removeEcosystemService(key: string, version: number): Promise<void> {
    await this.request("DELETE", `/api/admin/ecosystem/services/${encodeURIComponent(key)}`, { ifMatch: `"${version}"` });
  }

  /** PHASE 32 (TD-68, TD-71): deleting a software entry; a catalog entry is retired. */
  softwareDeletionPreview(key: string): Promise<DeletionPreview> {
    return this.plain("GET", `/api/software/${encodeURIComponent(key)}/deletion`);
  }

  async deleteSoftware(key: string, version: number): Promise<void> {
    await this.request("DELETE", `/api/software/${encodeURIComponent(key)}`, { ifMatch: `"${version}"` });
  }

  /** PHASE 32 (TD-71): a model leaves the catalog; one the catalog ships is retired. */
  modelDeletionPreview(key: string): Promise<DeletionPreview> {
    return this.plain("GET", `/api/catalog/models/deletion?key=${encodeURIComponent(key)}`);
  }

  async deleteModel(key: string, version: number): Promise<void> {
    await this.request("DELETE", `/api/catalog/models?key=${encodeURIComponent(key)}`, { ifMatch: `"${version}"` });
  }

  catalogRetirements(): Promise<CatalogRetirement[]> {
    return this.plain("GET", "/api/admin/catalog/retirements");
  }

  /** Puts a retired entry back, as the catalog ships it. */
  async restoreCatalogEntry(catalog: CatalogRetirement["catalog"], key: string): Promise<void> {
    await this.request("DELETE", `/api/admin/catalog/retirements/${catalog}/${encodeURIComponent(key)}`);
  }

  // --- deleting for real (ADR-027) ---------------------------------------------

  taskDeletionPreview(id: number): Promise<DeletionImpact> {
    return this.plain("GET", `/api/tasks/${id}/deletion`);
  }

  deleteTask(id: number, etag: string, confirm: string): Promise<DeletionImpact> {
    return this.plain("DELETE", `/api/tasks/${id}?confirm=${encodeURIComponent(confirm)}`, { ifMatch: etag });
  }

  projectDeletionPreview(id: number, tasks: TasksPolicy): Promise<DeletionImpact> {
    return this.plain("GET", `/api/projects/${id}/deletion?tasks=${tasks}`);
  }

  deleteProject(id: number, etag: string, tasks: TasksPolicy, confirm: string): Promise<DeletionImpact> {
    return this.plain("DELETE", `/api/projects/${id}?tasks=${tasks}&confirm=${encodeURIComponent(confirm)}`, { ifMatch: etag });
  }

  // --- removing what was created here (PHASE 30, ADR-035) -----------------------

  agentDeletionPreview(ids: number[], subagents: SubAgentsPolicy, inProgress: InProgressPolicy): Promise<DeletionPreview> {
    const query = `subagents=${subagents}&inProgress=${inProgress}`;
    return ids.length === 1
      ? this.plain("GET", `/api/agents/${ids[0]}/deletion?${query}`)
      : this.plain("GET", `/api/agents/deletion?ids=${ids.join(",")}&${query}`);
  }

  /** One agent under its tag; a selection with the version each one was read at. 204 either way. */
  async deleteAgents(agents: { id: number; version: number }[], subagents: SubAgentsPolicy,
                     inProgress: InProgressPolicy): Promise<void> {
    if (agents.length === 1) {
      await this.request("DELETE", `/api/agents/${agents[0].id}?subagents=${subagents}&inProgress=${inProgress}`,
        { ifMatch: `"${agents[0].version}"` });
      return;
    }
    await this.request("POST", "/api/agents/bulk-delete", { body: { agents, subagents, inProgress } });
  }

  agentTemplatePlan(key?: string): Promise<PlannedAgent[]> {
    return this.plain("GET", `/api/agent-templates/plan${key ? `?key=${encodeURIComponent(key)}` : ""}`);
  }

  async deleteReferences(projectId: number, paths: string[]): Promise<void> {
    if (paths.length === 1) {
      await this.request("DELETE", `/api/projects/${projectId}/references?path=${encodeURIComponent(paths[0])}`);
      return;
    }
    await this.request("POST", `/api/projects/${projectId}/references/bulk-delete`, { body: { paths } });
  }

  documentDeletionPreview(projectId: number, path: string): Promise<DeletionPreview> {
    return this.plain("GET", `/api/projects/${projectId}/files/deletion?path=${encodeURIComponent(path)}`);
  }

  async deleteDocument(projectId: number, path: string): Promise<void> {
    await this.request("DELETE", `/api/projects/${projectId}/files?path=${encodeURIComponent(path)}`);
  }

  resourceDeletionPreview(key: string): Promise<DeletionPreview> {
    return this.plain("GET", `/api/resources/${encodeURIComponent(key)}/deletion`);
  }

  async deleteResource(key: string, version: number): Promise<void> {
    await this.request("DELETE", `/api/resources/${encodeURIComponent(key)}`, { ifMatch: `"${version}"` });
  }

  projectResources(projectId: number): Promise<Resource[]> {
    return this.plain("GET", `/api/projects/${projectId}/resources`);
  }

  dropResource(projectId: number, key: string): Promise<void> {
    return this.plain("DELETE", `/api/projects/${projectId}/resources/${encodeURIComponent(key)}`);
  }

  blueprintDeletionPreview(projectId: number): Promise<DeletionPreview> {
    return this.plain("GET", `/api/projects/${projectId}/blueprint/deletion`);
  }

  async deleteBlueprint(projectId: number, layoutVersion: number, confirm: string): Promise<void> {
    await this.request("DELETE", `/api/projects/${projectId}/blueprint?confirm=${encodeURIComponent(confirm)}`,
      { ifMatch: `"${layoutVersion}"` });
  }

  async removeBudget(provider: string, version: number): Promise<void> {
    await this.request("DELETE", `/api/admin/costs/budgets/${encodeURIComponent(provider)}`, { ifMatch: `"${version}"` });
  }

  // --- the file-based library (ADR-026) -----------------------------------------

  library(): Promise<LibraryInfo> {
    return this.plain("GET", "/api/library");
  }

  syncLibrary(): Promise<LibrarySync> {
    return this.plain("POST", "/api/library/sync");
  }

  createLibraryEntry(content: string): Promise<Resource> {
    return this.plain("POST", "/api/library/entries", { body: { content } });
  }

  importSkill(url: string): Promise<Resource> {
    return this.plain("POST", "/api/library/import", { body: { url } });
  }

  resourceFile(key: string): Promise<Versioned<ResourceFile>> {
    return this.versioned("GET", `/api/resources/${encodeURIComponent(key)}/file`);
  }

  materializeResourceFile(key: string): Promise<Versioned<ResourceFile>> {
    return this.versioned("POST", `/api/resources/${encodeURIComponent(key)}/file`);
  }

  saveResourceFile(key: string, etag: string, content: string): Promise<Versioned<ResourceFile>> {
    return this.versioned("PUT", `/api/resources/${encodeURIComponent(key)}/file`, { body: { content }, ifMatch: etag });
  }

  openResource(key: string): Promise<{ folder: string; command: string[] }> {
    return this.plain("POST", `/api/resources/${encodeURIComponent(key)}/open`);
  }

  // --- the Knowledge Hub across applications and marketplaces (PHASE 31, ADR-036) ---

  hubInstalled(rescan = false): Promise<HubInstalled> {
    return this.plain("GET", `/api/skill-hub/installed${rescan ? "?rescan=true" : ""}`);
  }

  hubSkill(id: string): Promise<HubFoundDetail> {
    return this.plain("GET", `/api/skill-hub/installed/${encodeURIComponent(id)}`);
  }

  /** PHASE 32 (TD-76): the operator's category for a skill, by name; null gives it back to the heuristic. */
  async hubSetCategory(name: string, category: string | null): Promise<void> {
    await this.request("PUT", "/api/skill-hub/categories", { body: { name, category } });
  }

  /** PHASE 32 (TD-80): ties a library skill to its package online; its updates come from there. */
  hubLinkSource(key: string, provider: string, ref: string): Promise<{ key: string; provider: string; reference: string; version: string | null; inStep: boolean; message: string }> {
    return this.plain("PUT", `/api/skill-hub/library/${encodeURIComponent(key)}/source`, { body: { provider, ref } });
  }

  hubOpenSkill(id: string): Promise<{ folder: string }> {
    return this.plain("POST", `/api/skill-hub/installed/${encodeURIComponent(id)}/open`);
  }

  hubImportSkill(id: string, key?: string): Promise<Resource> {
    return this.plain("POST", `/api/skill-hub/installed/${encodeURIComponent(id)}/import`, { body: key ? { key } : {} });
  }

  hubSync(): Promise<HubSyncReport> {
    return this.plain("POST", "/api/skill-hub/sync");
  }

  hubEvents(limit = 200): Promise<HubSyncEvent[]> {
    return this.plain("GET", `/api/skill-hub/events?limit=${limit}`);
  }

  hubMarketplaces(): Promise<HubMarketplace[]> {
    return this.plain("GET", "/api/skill-hub/marketplaces");
  }

  hubSearch(q: string, providers: string[] = [], kind?: HubOfferKind): Promise<HubSearchResults> {
    const only = providers.map((p) => `&provider=${encodeURIComponent(p)}`).join("");
    const ofKind = kind ? `&kind=${kind}` : "";
    return this.plain("GET", `/api/skill-hub/marketplaces/search?q=${encodeURIComponent(q)}${only}${ofKind}`);
  }

  hubBrowse(provider: string): Promise<HubResult[]> {
    return this.plain("GET", `/api/skill-hub/marketplaces/${encodeURIComponent(provider)}/browse`);
  }

  hubPreview(provider: string, ref: string): Promise<HubPreview> {
    return this.plain("GET", `/api/skill-hub/marketplaces/${encodeURIComponent(provider)}/package?ref=${encodeURIComponent(ref)}`);
  }

  hubInstall(provider: string, ref: string, key: string | undefined, targets: string[]): Promise<HubInstallResult> {
    return this.plain("POST", "/api/skill-hub/install", { body: { provider, ref, key: key || undefined, targets } });
  }

  /** A plugin or connector of the Marketplace: what it asks, how each application would get it. */
  hubItem(provider: string, ref: string): Promise<HubExtensionPreview> {
    return this.plain("GET", `/api/skill-hub/marketplaces/${encodeURIComponent(provider)}/item?ref=${encodeURIComponent(ref)}`);
  }

  hubInstallPlugin(provider: string, ref: string, target: string, confirmation?: string): Promise<HubInstallOutcome> {
    return this.plain("POST", "/api/skill-hub/plugins/install", { body: { provider, ref, target, confirmation } });
  }

  /** The values (API keys...) go to the applications only: the server neither keeps nor returns them. */
  hubInstallConnector(request: {
    provider: string; ref: string; targets: string[]; name?: string; values: Record<string, string>; replace?: boolean;
  }): Promise<{ results: HubInstallOutcome[] }> {
    return this.plain("POST", "/api/skill-hub/connectors/install", { body: request });
  }

  hubCopies(key: string): Promise<HubCopyTarget[]> {
    return this.plain("GET", `/api/skill-hub/library/${encodeURIComponent(key)}/copies`);
  }

  hubDeploy(key: string, app: string, replace = false): Promise<HubDeployResult> {
    return this.plain("POST", `/api/skill-hub/library/${encodeURIComponent(key)}/copies/${encodeURIComponent(app)}`,
      { body: { replace } });
  }

  hubRemoveCopy(key: string, app: string): Promise<HubDeployResult> {
    return this.plain("DELETE", `/api/skill-hub/library/${encodeURIComponent(key)}/copies/${encodeURIComponent(app)}`);
  }

  /** The skill as a .zip for Claude Desktop, fetched with the token: an object URL to hand to a link. */
  async hubPackageUrl(key: string): Promise<string> {
    const response = await this.fetcher(this.session.baseUrl.replace(/\/+$/, "")
      + `/api/skill-hub/library/${encodeURIComponent(key)}/package.zip`,
      { headers: { Authorization: `Bearer ${this.session.token}` } });
    if (!response.ok) {
      const text = await response.text();
      throw new ApiProblem(response.status, (text ? safeJson(text) : {}) as object);
    }
    return URL.createObjectURL(await response.blob());
  }

  hubUpdates(): Promise<HubUpdates> {
    return this.plain("GET", "/api/skill-hub/updates");
  }

  hubCheckUpdates(): Promise<HubUpdates> {
    return this.plain("POST", "/api/skill-hub/updates/check");
  }

  hubApplyUpdates(ids: string[], overwrite = false): Promise<{ results: HubApplied[] }> {
    return this.plain("POST", "/api/skill-hub/updates/apply", { body: { ids, overwrite } });
  }

  // --- agent configuration and binding (ADR-025) --------------------------------

  agentConfiguration(id: number): Promise<Versioned<AgentConfiguration>> {
    return this.versioned("GET", `/api/agents/${id}/configuration`);
  }

  saveAgentConfiguration(id: number, etag: string, body: AgentConfigurationWrite): Promise<Versioned<AgentConfiguration>> {
    return this.versioned("PUT", `/api/agents/${id}/configuration`, { body, ifMatch: etag });
  }

  resetAgentConfiguration(id: number, etag: string): Promise<Versioned<AgentConfiguration>> {
    return this.versioned("POST", `/api/agents/${id}/configuration/reset`, { ifMatch: etag });
  }

  ecosystemBindings(): Promise<AgentConfiguration[]> {
    return this.plain("GET", "/api/ecosystem/bindings");
  }

  executionTargets(): Promise<ExecutionTarget[]> {
    return this.plain("GET", "/api/execution-targets");
  }

  checkBinding(target: string, model: string | null): Promise<Binding> {
    return this.plain("GET", `/api/execution-targets/${encodeURIComponent(target)}/check${model ? `?model=${encodeURIComponent(model)}` : ""}`);
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
