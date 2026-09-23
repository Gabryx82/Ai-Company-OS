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
