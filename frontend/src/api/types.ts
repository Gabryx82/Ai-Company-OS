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
  agentId: number;
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
