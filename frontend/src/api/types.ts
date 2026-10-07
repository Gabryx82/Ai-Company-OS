// Types of the control plane, derived from the generated contract
// (src/api/schema.d.ts, from docs/api/openapi.json -- never edited by hand).
//
// springdoc marks no response field as required, so every generated response
// type is all-optional. That is looser than the server: the fields below are
// always sent. `Wire<T>` makes every field present and possibly null (Jackson
// writes nulls), and each alias then pins the fields that are never null.
import type { components } from "./schema";

type S = components["schemas"];
type Wire<T> = { [K in keyof T]-?: Exclude<T[K], undefined> | null };

export type TaskStatus = NonNullable<S["TaskResponse"]["status"]>;
export type TaskPriority = NonNullable<S["TaskResponse"]["priority"]>;
export type RunStatus = NonNullable<S["RunResponse"]["status"]>;
export type ProjectStatus = NonNullable<S["ProjectResponse"]["status"]>;

export type Task = Wire<S["TaskResponse"]> & {
  id: number;
  title: string;
  status: TaskStatus;
  priority: TaskPriority;
};

export type Agent = Wire<S["AgentResponse"]> & {
  id: number;
  name: string;
  role: string;
  specialization: string;
  active: boolean;
  status: "ACTIVE" | "INACTIVE";
};

export type Project = Wire<S["ProjectResponse"]> & {
  id: number;
  name: string;
  status: ProjectStatus;
};

export type Run = Wire<S["RunResponse"]> & {
  id: number;
  taskId: number;
  /** Null once the agent has been deleted (PHASE 30): deletedAgentName then says who it was. */
  agentId: number | null;
  status: RunStatus;
  systemPrompt: string;
  userPrompt: string;
  correlationId: string;
  requestedBy: string;
  createdAt: string;
};

export type Suggestion = Wire<S["Suggestion"]> & {
  agentId: number;
  name: string;
  score: number;
  matchedTerms: string[];
};

export type ModelInfo = Wire<S["ModelInfo"]> & { id: string; provider: string; available: boolean; billed: boolean };
export type ModelList = { defaultModel: string | null; models: ModelInfo[] };

export type TaskCreate = S["TaskCreateRequest"];
export type TaskUpdate = S["TaskUpdateRequest"];
export type AgentWrite = S["AgentCreateRequest"];
export type ProjectWrite = S["ProjectCreateRequest"];

export const TASK_STATUSES: TaskStatus[] = ["OPEN", "IN_PROGRESS", "DONE"];
export const TASK_PRIORITIES: TaskPriority[] = ["LOW", "MEDIUM", "HIGH"];

/** The four edges of ADR-014, and where each one starts. */
export const TRANSITIONS = {
  start: "OPEN",
  complete: "IN_PROGRESS",
  stop: "IN_PROGRESS",
  reopen: "DONE",
} as const satisfies Record<string, TaskStatus>;
export type Transition = keyof typeof TRANSITIONS;

// --- PHASE 8: the ecosystem catalogs (ADR-018, ADR-019) -----------------------

export type Software = Wire<S["SoftwareResponse"]> & {
  id: number;
  key: string;
  name: string;
  category: NonNullable<S["SoftwareResponse"]["category"]>;
  role: string;
  capabilities: string[];
  projectTypes: string[];
  launchKind: NonNullable<S["SoftwareResponse"]["launchKind"]>;
  executableArgs: string[];
  openFolder: boolean;
  embeddable: boolean;
  executionTarget: boolean;
  enabled: boolean;
  availability: NonNullable<S["SoftwareResponse"]["availability"]>;
  launchable: boolean;
  version: number;
};
export type SoftwareCategory = Software["category"];
export type Availability = Software["availability"];
export type LaunchResult = Wire<S["LaunchResponse"]> & { key: string; command: string[]; folderOpened: boolean };

export type Provider = Wire<S["ProviderResponse"]> & {
  key: string;
  name: string;
  kind: NonNullable<S["ProviderResponse"]["kind"]>;
  billing: NonNullable<S["ProviderResponse"]["billing"]>;
  status: NonNullable<S["ProviderResponse"]["status"]>;
};
export type CatalogModel = Wire<S["ModelResponse"]> & {
  inputPricePerMtok?: number | null;
  outputPricePerMtok?: number | null;
  key: string;
  providerKey: string;
  displayName: string;
  role: NonNullable<S["ModelResponse"]["role"]>;
  capabilities: string[];
  lifecycle: NonNullable<S["ModelResponse"]["lifecycle"]>;
  version: number;
};
export type ModelCatalog = {
  models: CatalogModel[];
  uncatalogued: { key: string; provider: string; available: boolean; billed: boolean }[];
  engineReachable: boolean;
  engineDefault: string | null;
};

export type UsageWindow = Wire<S["UsageWindowResponse"]> & {
  key: string;
  name: string;
  subject: string;
  source: NonNullable<S["UsageWindowResponse"]["source"]>;
  windowKind: NonNullable<S["UsageWindowResponse"]["windowKind"]>;
  status: NonNullable<S["UsageWindowResponse"]["status"]>;
  byModel: { model: string; tokens: number }[];
  version: number;
};

// --- PHASE 9-11: workspace, plan, orchestration (ADR-020, 021, 022) -----------
//
// These responses nest objects, and `Wire<T>` only reaches the first level.
// `Deep<T>` makes every nested field present; fields the server may send as
// null are still read defensively where it matters (`?? "—"`), because the
// generated schema cannot say which ones are nullable.
type Deep<T> = T extends (infer U)[] ? Deep<U>[]
  : T extends object ? { [K in keyof T]-?: Deep<Exclude<T[K], undefined>> } : T;

export type ProjectTypeInfo = Deep<S["ProjectTypeInfo"]>;
export type ScaffoldEntry = Deep<S["ScaffoldEntry"]>;
export type WorkspaceDocument = Deep<S["Document"]>;
export type WorkspaceDocuments = Omit<Deep<S["Documents"]>, "masterPrompt" | "agents" | "implementationPlan" | "planJson"> & {
  masterPrompt: WorkspaceDocument | null;
  agents: WorkspaceDocument | null;
  implementationPlan: WorkspaceDocument | null;
  planJson: WorkspaceDocument | null;
};
export type Phase = Deep<S["PhaseResponse"]> & { tasks: Task[] };
export type PlanRun = Deep<S["PlanRunResponse"]>;
export type Plan = { project: Project; phases: Phase[]; runs: PlanRun[] };
export type Orchestration = Deep<S["Orchestration"]>;
export type HandoffOutcome = Deep<S["HandoffResult"]>;
export type Handoff = Deep<S["HandoffResponse"]>;
export type Review = Deep<S["ReviewResponse"]>;
export type AutonomyLevel = NonNullable<S["ProjectResponse"]["autonomyLevel"]>;
export type ProjectType = NonNullable<S["ProjectResponse"]["projectType"]>;

// --- PHASE 12-13: agent ecosystem and daily work (ADR-023) ---------------------

export type Resource = Deep<S["ResourceResponse"]>;
export type AgentProfile = Omit<Deep<S["AgentProfileResponse"]>, "parentId" | "model"> & {
  parentId: number | null;
  model: string | null;
};
export type AgentTemplate = Deep<S["Template"]>;
export type InstalledAgent = Deep<S["Installed"]>;
export type DailyItem = Omit<Deep<S["DailyResponse"]>, "task"> & { task: Deep<S["TaskRef"]> | null };

// --- PHASE 15: people and sessions (ADR-024) ------------------------------------

export type Role = "ADMIN" | "OPERATOR";
export interface UserInfo {
  id: number;
  username: string;
  displayName: string | null;
  role: Role;
  enabled: boolean;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  passwordChangedAt: string | null;
  version: number;
}
export interface Me { name: string; role: Role; kind: "USER" | "SERVICE"; user: UserInfo | null }
export interface SecurityEventInfo {
  id: number; occurredAt: string; type: string; principal: string | null; source: string | null; detail: string | null;
}

// --- PHASE 16: Agent -> Model -> Provider -> Execution Target (ADR-025) -----------

export type Delivery = "ENGINE_RUN" | "CLI_PROMPT" | "IDE_FOLDER" | "APP_PASTE" | "WEB_PASTE" | "MANUAL";
export interface ExecutionTarget {
  key: string; name: string; software: string | null; delivery: Delivery; providers: string[];
  contextFile: string | null; howItWorks: string;
}
export interface Binding {
  model: { key: string; displayName: string; role: string | null; lifecycle: string | null; replacedBy: string | null;
    catalogued: boolean; engineRunnable: boolean } | null;
  provider: { key: string; name: string; kind: string | null; billing: string | null; status: string | null } | null;
  target: { key: string; name: string; delivery: Delivery | null; software: string | null; availability: string | null;
    providers: string[]; contextFile: string | null; howItWorks: string | null };
  valid: boolean; problems: string[]; warnings: string[]; summary: string;
}
export interface AgentRef { id: number; name: string; role: string }
export interface ResourceRef { key: string; name: string; kind: string; description: string | null; configuration: string | null }
export type AgentOrigin = "SEED" | "TEMPLATE" | "USER";
export interface AgentConfiguration {
  id: number; name: string; role: string; specialization: string; description: string | null; capabilities: string[];
  status: "ACTIVE" | "INACTIVE"; parent: AgentRef | null; children: AgentRef[];
  domain: string | null; systemPrompt: string | null; responsibilities: string | null; limits: string | null;
  outputFormat: string | null; directives: string[]; contextPolicy: string | null;
  resources: Record<string, ResourceRef[]>; software: { key: string; name: string }[];
  model: string | null; executionTarget: string; binding: Binding;
  origin: AgentOrigin; defaults: Record<string, unknown> | null; modified: string[];
  customizedAt: string | null; customizedBy: string | null; version: number;
}
export interface AgentConfigurationWrite {
  name?: string; role: string; specialization: string; description?: string | null; capabilities?: string;
  domain?: string | null; systemPrompt?: string | null; responsibilities?: string | null; limits?: string | null;
  outputFormat?: string | null; directives?: string; contextPolicy?: string | null; parentId?: number | null;
  model?: string | null; executionTarget: string;
}

// --- PHASE 19: skills and knowledge as files (ADR-026) -----------------------------

export interface LibraryInfo { root: string; exists: boolean; skills: number; knowledge: number; layout: string }
export interface ResourceFile {
  key: string; relativePath: string; absolutePath: string; exists: boolean; content: string | null;
  modified: string | null; version: number;
}
export interface LibrarySync { root: string; created: string[]; updated: string[]; invalid: string[] }

// --- PHASE 20: deleting for real (ADR-027) ------------------------------------------

export interface DeletionImpact {
  kind: "TASK" | "PROJECT"; id: number; name: string; tasks: number; runs: number; handoffs: number; reviews: number;
  dailyItems: number; phases: number; planRuns: number; resources: number; runInProgress: boolean; folder: string | null;
  notes: string[];
}
export type TasksPolicy = "DETACH" | "DELETE";

// --- PHASE 30: everything created here can be removed here (ADR-035) -------------------

/**
 * What a delete would do, the same shape for every kind of thing: what goes,
 * what is detached and stays, what is kept, which files leave the disk, and --
 * when it cannot happen -- why (`blockers`, and `allowed` false).
 */
export interface DeletionPreview {
  kind: string; subjects: { id: string; name: string }[]; deletes: string[]; detaches: string[]; keeps: string[];
  files: string[]; blockers: string[]; allowed: boolean;
}
export type SubAgentsPolicy = "DETACH" | "DELETE";
export type InProgressPolicy = "BLOCK" | "STOP";
export interface PlannedAgent {
  templateKey: string; name: string; role: string; parentName: string | null; outcome: "CREATED" | "EXISTING";
}

// --- PHASE 21: the ecosystem starts with AI Company OS (ADR-028) -----------------------

export type EcosystemStatus = "NEVER" | "ALREADY_RUNNING" | "STARTING" | "RUNNING" | "FAILED";
export interface EcosystemServiceInfo {
  key: string; name: string; url: string | null; healthUrl: string | null; command: string | null; availability: string;
  autostart: boolean; position: number; timeoutSeconds: number; lastStatus: EcosystemStatus; lastMessage: string | null;
  lastAttemptAt: string | null; version: number;
}

// --- PHASE 22: the integrated terminal (ADR-029) --------------------------------------

export interface TerminalShell { key: string; name: string; available: boolean; detail: string | null }
export interface TerminalTicket { ticket: string; shell: string; directory: string; command: string[]; expiresAt: string }

// --- PHASE 23: the code graph of a project (ADR-030) ------------------------------------

export interface CodeNode { id: string; label: string; kind: "FILE" | "MANIFEST" | "EXTERNAL"; language: string; directory: string; lines: number }
export interface CodeEdge { source: string; target: string; kind: "IMPORT" | "USES" | "DECLARES" }
export interface CodeGraph {
  generatedAt: string; files: number; truncated: boolean; languages: Record<string, number>; nodes: CodeNode[];
  edges: CodeEdge[]; cycles: string[][]; mostImported: { id: string; importedBy: number }[]; externals: number;
  taskLinks: { taskId: number; code: string | null; title: string; file: string }[];
}

// --- PHASE 24: cost governance (ADR-031) --------------------------------------------------

export interface CostSummary {
  from: string; to: string; totalUsd: number;
  providers: { providerKey: string; providerName: string; billing: string; runs: number; inputTokens: number;
    outputTokens: number; costUsd: number; runsWithoutPrice: number }[];
  models: { model: string; providerKey: string; runs: number; inputTokens: number; outputTokens: number; costUsd: number;
    inputPricePerMtok: number | null; outputPricePerMtok: number | null }[];
  /** PHASE 30: a deleted agent's spend stays under its name, with agentId null. */
  agents: { agentId: number | null; agentName: string; runs: number; tokens: number; costUsd: number }[];
  budgets: { providerKey: string; providerName: string | null; monthlyLimitUsd: number; alertPercent: number;
    spentThisMonthUsd: number; percent: number; alert: boolean; exceeded: boolean; version: number }[];
}

// --- PHASE 28: the Project Blueprint (ADR-033) ---------------------------------------------

/** Every field present (possibly null), and the named ones never null. */
type Present<T, K extends keyof T> = Wire<T> & { [P in K]-?: NonNullable<T[P]> };

export type NodeKind = NonNullable<S["BlueprintBlock"]["kind"]>;
export type BlueprintLayer = NonNullable<S["BlueprintBlock"]["layer"]>;
export type BlueprintFamily = NonNullable<S["BlueprintBlock"]["family"]>;
export type NodeOrigin = NonNullable<S["BlueprintBlock"]["origin"]>;
export type NodeStatus = NonNullable<S["BlueprintBlock"]["status"]>;
export type PathState = NonNullable<S["BlueprintBlock"]["pathState"]>;
export type RelationType = NonNullable<S["BlueprintRelation"]["relation"]>;
export type RelationFamily = NonNullable<S["BlueprintRelation"]["family"]>;
export type EdgeOrigin = NonNullable<S["BlueprintRelation"]["origin"]>;
export type StructureSection = NonNullable<S["BlueprintBlockDetail"]["section"]>;
export type AiAction = NonNullable<S["BlueprintActionRequest"]["action"]>;
export type ProposalKind = NonNullable<S["BlueprintProposal"]["kind"]>;

export type CodeMember = Present<S["CodeMember"], "name" | "annotations">;
export type CodeMethod = Present<S["CodeMethod"], "name" | "parameters" | "annotations" | "exceptions" | "calls" | "callTargets" | "isStatic">;
/** The server normalises every list of a structure to [] (CodeStructure.normalized): they are never null. */
type StructureLists = "implementsTypes" | "annotations" | "fields" | "methods" | "dependencies" | "exceptions" | "relatedTests"
  | "inputs" | "calls" | "calledBy" | "statusCodes" | "exports" | "imports" | "props" | "hooks" | "columns";
export type CodeStructure = Omit<Wire<S["CodeStructure"]>, StructureLists> & {
  implementsTypes: string[]; annotations: string[]; fields: CodeMember[]; methods: CodeMethod[]; dependencies: string[];
  exceptions: string[]; relatedTests: string[]; inputs: string[]; calls: string[]; calledBy: string[];
  statusCodes: string[]; exports: string[]; imports: string[]; props: string[]; hooks: string[]; columns: string[];
};

export type BlueprintBlock = Present<S["BlueprintBlock"], "id" | "key" | "kind" | "layer" | "family" | "origin" | "status"
  | "pathState" | "name" | "tags" | "expanded" | "expandable" | "members" | "version">;
export type BlueprintConnection = Present<S["BlueprintConnection"], "edgeId" | "relation" | "origin" | "nodeId" | "nodeName" | "nodeKind">;
export type BlueprintBlockDetail = Omit<Present<S["BlueprintBlockDetail"], "id" | "key" | "kind" | "layer" | "family" | "section"
  | "origin" | "status" | "pathState" | "name" | "tags" | "overrides" | "expanded" | "expandable" | "version">,
  "structure" | "incoming" | "outgoing"> & { structure: CodeStructure; incoming: BlueprintConnection[]; outgoing: BlueprintConnection[] };
export type BlueprintRelation = Present<S["BlueprintRelation"], "id" | "source" | "target" | "relation" | "family" | "origin" | "version">;
export interface BlueprintPoint { x: number; y: number }
export interface BlueprintCanvasLayout { positions: Record<string, BlueprintPoint>; version: number }
export type BlueprintScanReport = Present<S["BlueprintScanReport"], "id" | "startedAt" | "finishedAt" | "files" | "nodesDetected"
  | "edgesDetected" | "nodesAdded" | "nodesMissing" | "unresolved" | "languages">;
export type BlueprintMetrics = Present<S["BlueprintMetrics"], "totalNodes" | "files" | "classes" | "endpoints" | "databaseEntities"
  | "tests" | "detectedRelations" | "manualRelations" | "proposedRelations" | "unresolvedRelations" | "missingNodes"
  | "byLayer" | "byFamily">;
export interface BlueprintGraph {
  projectId: number; archived: boolean; workspaceReady: boolean; nodes: BlueprintBlock[]; edges: BlueprintRelation[];
  layout: BlueprintCanvasLayout; lastScan: BlueprintScanReport | null; metrics: BlueprintMetrics; pendingProposals: number;
}
export type BlueprintSnippet = Present<S["BlueprintSnippet"], "path" | "startLine" | "endLine" | "text" | "truncated">;
export type BlueprintProposal = Present<S["BlueprintProposal"], "id" | "kind" | "status" | "filePath" | "title" | "diff"
  | "proposedBy" | "createdAt" | "version">;
export type BlueprintActionOutcome = Present<S["BlueprintActionOutcome"], "taskId" | "taskTitle" | "action">;
export type BlueprintKindInfo = Present<S["BlueprintKindInfo"], "kind" | "label" | "family" | "defaultLayer" | "section" | "column">;
export type BlueprintRelationInfo = Present<S["BlueprintRelationInfo"], "relation" | "label" | "family" | "description">;
export type BlueprintActionInfo = Present<S["BlueprintActionInfo"], "action" | "label" | "description">;
export interface BlueprintVocabulary {
  kinds: BlueprintKindInfo[]; relations: BlueprintRelationInfo[]; actions: BlueprintActionInfo[];
  statuses: NodeStatus[]; pathStates: PathState[]; proposalKinds: ProposalKind[];
}
/** What a client sends for a block (PUT semantics: every field). */
export type BlueprintBlockWrite = S["BlueprintBlockRequest"];
export type BlueprintRelationWrite = S["BlueprintRelationRequest"];
export type BlueprintProposalWrite = S["BlueprintProposalRequest"];

// --- PHASE 31: the Knowledge Hub across applications and marketplaces (ADR-036) ---------------

export type HubScope = "LIBRARY" | "PERSONAL" | "PLUGIN" | "SYSTEM" | "INSTRUCTIONS" | "KNOWLEDGE";
export type CopyStatus = "IN_SYNC" | "OUTDATED" | "MODIFIED" | "CONFLICT" | "ORPHAN";
export interface HubCategory { key: string; label: string }
export interface HubAgentRef { id: number; name: string; target: string }
export interface HubLocation {
  id: string; label: string; scope: HubScope; path: string; exists: boolean; writable: boolean; readers: string[]; count: number;
}
export interface HubApp {
  id: string; name: string; description: string; detected: boolean; software: string | null; targets: string[];
  agents: HubAgentRef[]; locations: HubLocation[]; count: number; installable: boolean;
}
export interface HubProvenance {
  provider: string; providerName: string; reference: string; sourceUrl: string | null; installedVersion: string | null;
  latestVersion: string | null; installedAt: string | null; checkedAt: string | null; updateAvailable: boolean;
  localChanges: boolean; checkError: string | null;
}
export interface HubCopy { app: string; location: string; skillId: string; path: string; status: CopyStatus; deployedAt: string | null }
export interface HubSkill {
  id: string; app: string; location: string; scope: HubScope; kind: string; name: string;
  description: string | null; category: string; categoryLabel: string; plugin: string | null; pluginVersion: string | null;
  marketplace: string | null; enabled: boolean | null; version: string | null; path: string | null; writable: boolean;
  modified: string | null; libraryKey: string | null; copyStatus: CopyStatus | null; provenance: HubProvenance | null;
  copies: HubCopy[] | null; updateAvailable: boolean;
}
export interface HubSummary { total: number; apps: number; libraryEntries: number; updates: number; copies: number }
export interface HubInstalled {
  scannedAt: string; lastSync: string | null; syncIntervalSeconds: number; apps: HubApp[]; skills: HubSkill[];
  categories: HubCategory[]; notes: string[]; summary: HubSummary;
}
export interface HubPackageFile { path: string; size: number }
export interface HubFoundDetail { skill: HubSkill; content: string; truncated: boolean; files: HubPackageFile[] | null; folder: string }
export interface HubSyncEvent {
  id: number; at: string; change: "ADDED" | "REMOVED" | "MODIFIED"; app: string; location: string; name: string; path: string;
  detail: string | null;
}
export interface HubSyncReport {
  at: string; firstRun: boolean; total: number; added: number; removed: number; modified: number; events: HubSyncEvent[];
  libraryCreated: string[]; libraryUpdated: string[]; libraryInvalid: string[];
}
export interface HubMarketplace {
  key: string; name: string; description: string; homepage: string; searchUrl: string | null;
  api: "SKILLS_SH" | "CLAWHUB" | "GITHUB" | null; repo: string | null; official: boolean; community: boolean;
}
export interface HubResult {
  provider: string; ref: string; name: string; description: string | null; author: string | null; source: string | null;
  installs: number | null; version: string | null; url: string | null; suspicious: boolean;
}
export interface HubSearchResults { results: HubResult[]; errors: { provider: string; message: string }[] }
export interface HubPreview {
  provider: string; ref: string; name: string; description: string | null; version: string | null; sourceUrl: string | null;
  files: HubPackageFile[] | null; skillMd: string | null; security: string | null; warnings: string[] | null;
}
export interface HubDeployResult { app: string; outcome: string; path: string | null; backup: string | null; message: string | null }
export interface HubInstallResult { key: string; name: string; filePath: string | null; copies: HubDeployResult[] }
export interface HubCopyTarget {
  app: string; appName: string; available: boolean; path: string;
  status: "NONE" | "FOREIGN" | CopyStatus; deployedAt: string | null; skillId: string | null;
}
export type HubUpdateType = "MARKETPLACE" | "SOURCE" | "COPY" | "PULL" | "PLUGIN";
export interface HubUpdateItem {
  id: string; type: HubUpdateType; app: string; name: string; key: string | null; from: string | null; to: string | null;
  detail: string | null; applicable: boolean; needsOverwrite: boolean; software: string | null; command: string | null;
}
export interface HubUpdates { checkedAt: string | null; items: HubUpdateItem[]; errors: string[] }
export interface HubApplied { id: string; outcome: "UPDATED" | "CONFLICT" | "FAILED" | "SKIPPED"; message: string | null }
