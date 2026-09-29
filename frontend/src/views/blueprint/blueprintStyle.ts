// How the Blueprint looks (ADR-033): colours and monograms per kind, colours and
// dashes per relation. Presentation only -- what a kind *is* (its family, layer,
// column) comes from the server's vocabulary.
import type { EdgeOrigin, NodeKind, NodeStatus, PathState, RelationFamily } from "../../api/types";

interface KindLook { color: string; mono: string }

const FRONTEND = "#3b82f6";
const API = "#10b981";
const LOGIC = "#8b5cf6";
const DATA = "#f59e0b";
const DATABASE = "#f43f5e";
const TESTS = "#06b6d4";
const SUPPORT = "#64748b";
const CUSTOM = "#ec4899";

export const KIND_LOOK: Record<NodeKind, KindLook> = {
  COMPONENT: { color: FRONTEND, mono: "UI" },
  HOOK: { color: FRONTEND, mono: "HK" },
  STORE: { color: FRONTEND, mono: "ST" },
  ENDPOINT: { color: API, mono: "API" },
  CONTROLLER: { color: API, mono: "CT" },
  SERVICE: { color: LOGIC, mono: "SV" },
  CLASS: { color: LOGIC, mono: "C" },
  INTERFACE: { color: LOGIC, mono: "I" },
  FUNCTION: { color: LOGIC, mono: "fn" },
  REPOSITORY: { color: DATA, mono: "RP" },
  ENTITY: { color: DATA, mono: "EN" },
  DTO: { color: DATA, mono: "DT" },
  ENUM: { color: DATA, mono: "E" },
  DATABASE: { color: DATABASE, mono: "DB" },
  TEST: { color: TESTS, mono: "T" },
  CONFIGURATION: { color: SUPPORT, mono: "CF" },
  DOCUMENT: { color: SUPPORT, mono: "MD" },
  FILE: { color: SUPPORT, mono: "F" },
  SCRIPT: { color: SUPPORT, mono: "SH" },
  SNIPPET: { color: CUSTOM, mono: "{}" },
  CUSTOM: { color: CUSTOM, mono: "★" },
};

export const RELATION_COLOR: Record<RelationFamily, string> = {
  DEPENDENCY: "#8ba3d9",
  STRUCTURE: "#a78bfa",
  DATA: "#fbbf24",
  EVENTS: "#f472b6",
  TESTS: "#22d3ee",
  CUSTOM: "#94a3b8",
};

/** Detected: solid. Manual: dashed. Proposed: dotted and moving -- it is waiting for a person. */
export const ORIGIN_DASH: Record<EdgeOrigin, string | undefined> = {
  DETECTED: undefined,
  MANUAL: "7 4",
  PROPOSED: "2 5",
};

export const ORIGIN_LABEL: Record<EdgeOrigin, string> = {
  DETECTED: "Rilevata dal codice",
  MANUAL: "Manuale",
  PROPOSED: "Proposta, da confermare",
};

export const STATUS_LOOK: Record<NodeStatus, { label: string; dot: string }> = {
  PLANNED: { label: "Pianificato", dot: "dot-warn" },
  IN_PROGRESS: { label: "In corso", dot: "dot-warn" },
  IMPLEMENTED: { label: "Implementato", dot: "dot-ok" },
  DEPRECATED: { label: "Deprecato", dot: "dot-off" },
};

export const PATH_STATE_LABEL: Record<PathState, string> = {
  LINKED: "Collegato a un file reale",
  TO_CREATE: "File da creare",
  DOCUMENT_REFERENCE: "Riferimento documentale",
  VIRTUAL: "Componente virtuale",
  MISSING: "Non più presente nel codice",
};

export const LAYER_LABEL: Record<string, string> = {
  BACKEND: "Backend", FRONTEND: "Frontend", DATABASE: "Database", TESTS: "Test", DOCS: "Docs", OTHER: "Altro",
};

export const FAMILY_LABEL: Record<string, string> = {
  FILES: "File", CLASSES: "Classi", METHODS: "Metodi", APIS: "API", DATA: "Dati", TESTS: "Test", CUSTOM: "Custom",
};

/** The HTTP method of an endpoint gets its own colour, as in any API reference. */
export const HTTP_COLOR: Record<string, string> = {
  GET: "#34d399", POST: "#60a5fa", PUT: "#fbbf24", PATCH: "#f59e0b", DELETE: "#f87171",
};
