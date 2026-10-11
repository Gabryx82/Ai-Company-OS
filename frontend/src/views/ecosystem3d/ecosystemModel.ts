// The agent ecosystem as a graph (ADR-037): one node per agent, one node per
// thing that defines it -- model, provider, execution target, skills, knowledge,
// MCP, tools, frameworks, software, prompts, directives, role, capabilities --
// and, through the target an agent works in, the skills, plugins and connectors
// of that application as the Knowledge Hub reads them.
//
// Everything here is read from what the control plane answers; nothing is
// invented. A thing two agents share is one node with two edges. What could hold
// a secret never reaches a node: a resource's configuration is reduced to
// "present", and every free text goes through redactSecrets.
import type { AgentConfiguration, Binding, Delivery, HubInstalled, HubSkill } from "../../api/types";

export type Category =
  | "agent" | "model" | "provider" | "target" | "skill" | "knowledge" | "mcp" | "tool" | "framework"
  | "plugin" | "software" | "prompt" | "directive" | "role" | "capability";

export type Shape =
  | "icosahedron" | "octahedron" | "hexagon" | "torus" | "tetrahedron" | "book" | "knot" | "cone" | "dodecahedron"
  | "cube" | "capsule" | "card" | "pyramid" | "coin" | "sphere";

export interface CategoryInfo {
  key: Category;
  label: string;
  color: string;
  shape: Shape;
  /** What the category is, for the legend's tooltip. */
  hint: string;
}

// The colours of the Second Brain (NODE_STYLE) where the two views share a kind,
// so that an agent, a model or a skill reads the same everywhere in the console.
export const CATEGORIES: CategoryInfo[] = [
  { key: "agent", label: "Agenti", color: "#f97316", shape: "icosahedron", hint: "Gli agenti e i sottoagenti" },
  { key: "model", label: "Modelli LLM", color: "#eab308", shape: "octahedron", hint: "Il modello a cui è legato l'agente" },
  { key: "provider", label: "Provider", color: "#94a3b8", shape: "hexagon", hint: "Chi serve il modello: locale o cloud" },
  { key: "target", label: "Esecuzione", color: "#8b5cf6", shape: "torus", hint: "Dove lavora: AI Engine, Claude Code, Codex…" },
  { key: "skill", label: "Skill", color: "#ec4899", shape: "tetrahedron", hint: "Skill dell'harness e delle app" },
  { key: "knowledge", label: "Knowledge", color: "#14b8a6", shape: "book", hint: "Knowledge base e documentazione" },
  { key: "mcp", label: "MCP", color: "#818cf8", shape: "knot", hint: "Server MCP e connettori" },
  { key: "tool", label: "Tool", color: "#84cc16", shape: "cone", hint: "Strumenti dell'harness" },
  { key: "framework", label: "Framework", color: "#0ea5e9", shape: "dodecahedron", hint: "Framework dell'harness" },
  { key: "plugin", label: "Plugin e integrazioni", color: "#fb7185", shape: "capsule", hint: "Plugin delle app in cui lavora, fonti di template" },
  { key: "software", label: "Software", color: "#06b6d4", shape: "cube", hint: "Software che l'agente può usare" },
  { key: "prompt", label: "Prompt", color: "#e2e8f0", shape: "card", hint: "System prompt, output atteso, politica di contesto" },
  { key: "directive", label: "Direttive", color: "#f87171", shape: "pyramid", hint: "Le direttive, nell'ordine in cui valgono" },
  { key: "role", label: "Ruoli e permessi", color: "#fde047", shape: "coin", hint: "Ruolo, dominio, responsabilità, limiti" },
  { key: "capability", label: "Capability", color: "#4ade80", shape: "sphere", hint: "Cosa sa fare" },
];
export const CATEGORY: Record<Category, CategoryInfo> = Object.fromEntries(CATEGORIES.map((c) => [c.key, c])) as Record<Category, CategoryInfo>;
export const SUBAGENT_COLOR = "#fdba74";

/** The detail categories an ecosystem view starts without: text that belongs to one agent and crowds the map. */
export const ECOSYSTEM_HIDDEN: Category[] = ["prompt", "directive", "role"];

export type Locality = "LOCAL" | "CLOUD" | "SUBSCRIPTION" | "TEST";

export function localityOf(kind: string | null | undefined): Locality | null {
  switch (kind) {
    case "LOCAL": return "LOCAL";
    case "CLOUD_API": case "ROUTER": return "CLOUD";
    case "SUBSCRIPTION": return "SUBSCRIPTION";
    case "TEST": return "TEST";
    default: return null;
  }
}

export const LOCALITY_LABEL: Record<Locality, string> = {
  LOCAL: "locale", CLOUD: "cloud (API)", SUBSCRIPTION: "cloud (abbonamento)", TEST: "di test",
};

export type TextField = "systemPrompt" | "outputFormat" | "contextPolicy" | "responsibilities" | "limits" | "directive";

/** What the inspector shows of a node: already free of anything secret. */
export type NodeDetail =
  | { type: "agent"; agent: AgentConfiguration }
  | { type: "model"; key: string; displayName: string; role: string | null; lifecycle: string | null; replacedBy: string | null;
      catalogued: boolean; engineRunnable: boolean; providerName: string | null; locality: Locality | null }
  | { type: "provider"; key: string; name: string; kind: string | null; billing: string | null; status: string | null; locality: Locality | null }
  | { type: "target"; key: string; name: string; delivery: Delivery | null; software: string | null; availability: string | null;
      howItWorks: string | null; contextFile: string | null }
  | { type: "resource"; key: string; name: string; kind: string; description: string | null; configured: boolean }
  | { type: "software"; key: string; name: string }
  | { type: "text"; agentId: number; field: TextField; title: string; text: string; index: number | null }
  | { type: "value"; field: "role" | "domain" | "capability"; value: string }
  | { type: "hub"; skill: HubSkill; appName: string }
  | { type: "group"; app: string; appName: string; kind: string; count: number };

export interface EcoNode {
  id: string;
  category: Category;
  label: string;
  /** One line under the label, in the tooltip. */
  subtitle: string;
  /** A cluster of the Hub: drawn as one node until it is expanded. */
  isGroup: boolean;
  /** Relative size of the shape. */
  size: number;
  /** Inactive agents are drawn dimmed; a warning (invalid binding, not installed...) is ringed. */
  status: "active" | "inactive" | "warning";
  detail: NodeDetail;
  /** Lower-case text the search looks into. */
  search: string;
}

export type EdgeKind =
  | "subagent" | "uses-model" | "served-by" | "runs-on" | "equipped" | "uses-software" | "prompt" | "directive"
  | "role" | "capability" | "offers" | "member";

/** source owns target: the agent its properties, the model its provider, a target its Hub clusters, a cluster its members. */
export interface EcoEdge { id: string; source: string; target: string; kind: EdgeKind }

export interface EcoGraph {
  nodes: EcoNode[];
  edges: EcoEdge[];
  byId: Map<string, EcoNode>;
  /** Ownership: who a node owns, and who owns it. */
  out: Map<string, EcoEdge[]>;
  into: Map<string, EcoEdge[]>;
  agentIds: number[];
}

export const EDGE_LABEL: Record<EdgeKind, string> = {
  subagent: "sottoagente", "uses-model": "usa il modello", "served-by": "servito da", "runs-on": "lavora in",
  equipped: "equipaggiato con", "uses-software": "può usare", prompt: "prompt", directive: "direttiva", role: "ruolo",
  capability: "capability", offers: "offre", member: "contiene",
};

// --- secrets ---------------------------------------------------------------------------

const SECRET_PATTERNS: RegExp[] = [
  /\bsk-(?:ant-|proj-|or-)?[A-Za-z0-9_-]{16,}/g,
  /\bgh[pousr]_[A-Za-z0-9]{20,}/g,
  /\bgithub_pat_[A-Za-z0-9_]{20,}/g,
  /\bxox[abprs]-[A-Za-z0-9-]{10,}/g,
  /\bAKIA[0-9A-Z]{16}\b/g,
  /\bAIza[0-9A-Za-z_-]{30,}/g,
  /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g,
  /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)/g,
];
const BEARER = /\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{12,}/gi;
const ASSIGNED = /\b([A-Za-z0-9_.-]*(?:api[_-]?key|token|secret|password|passwd|pwd|credential|client[_-]?secret)[A-Za-z0-9_.-]*)(\s*["']?\s*[:=]\s*["']?)([^\s"',;]{4,})/gi;
const URL_CREDENTIALS = /\b([a-z][a-z0-9+.-]*:\/\/)([^\s/:@]+):([^\s/@]+)@/gi;
export const REDACTED = "••••••";

/**
 * Masks what looks like a credential in a text an operator wrote: provider keys,
 * bearer tokens, JWTs, private keys, `password=…`, a password in a URL. The text
 * stays readable; the secret does not reach the screen.
 */
export function redactSecrets(text: string): string {
  let out = text;
  for (const pattern of SECRET_PATTERNS) out = out.replace(pattern, REDACTED);
  out = out.replace(BEARER, (_, scheme: string) => `${scheme} ${REDACTED}`);
  out = out.replace(ASSIGNED, (match: string, key: string, sep: string, value: string) =>
    value === REDACTED ? match : `${key}${sep}${REDACTED}`);
  out = out.replace(URL_CREDENTIALS, (_, scheme: string, user: string) => `${scheme}${user}:${REDACTED}@`);
  return out;
}

function clean(text: string | null | undefined): string | null {
  if (text === null || text === undefined) return null;
  const trimmed = text.trim();
  return trimmed === "" ? null : redactSecrets(trimmed);
}

/** The agent as the 3D view keeps it: no resource configuration, every free text redacted. */
export function sanitizeAgent(a: AgentConfiguration): AgentConfiguration {
  const resources: AgentConfiguration["resources"] = {};
  for (const [kind, refs] of Object.entries(a.resources ?? {})) {
    resources[kind] = refs.map((r) => ({ ...r, description: clean(r.description), configuration: r.configuration ? REDACTED : null }));
  }
  return {
    ...a,
    description: clean(a.description), systemPrompt: clean(a.systemPrompt), responsibilities: clean(a.responsibilities),
    limits: clean(a.limits), outputFormat: clean(a.outputFormat), contextPolicy: clean(a.contextPolicy), domain: clean(a.domain),
    capabilities: a.capabilities.map((c) => redactSecrets(c)), directives: a.directives.map((d) => redactSecrets(d)),
    resources,
    // The baseline the agent was born with holds the same fields: the 3D view never shows it.
    defaults: null,
  };
}

function sanitizeHubSkill(s: HubSkill): HubSkill {
  const details = s.details
    ? Object.fromEntries(Object.entries(s.details).map(([k, v]) => [k, redactSecrets(String(v))]))
    : null;
  // Paths name folders of this machine's account: they stay in the Knowledge Hub.
  return { ...s, description: clean(s.description), details, path: null, copies: null, provenance: null };
}

// --- building ----------------------------------------------------------------------------

const RESOURCE_CATEGORY: Record<string, Category> = {
  SKILL: "skill", KNOWLEDGE: "knowledge", MCP: "mcp", TOOL: "tool", FRAMEWORK: "framework", TEMPLATE_PROVIDER: "plugin",
};
const HUB_CATEGORY: Record<string, Category> = { PLUGIN: "plugin", CONNECTOR: "mcp", KNOWLEDGE: "knowledge", SKILL: "skill" };
const HUB_KIND_LABEL: Record<string, string> = { PLUGIN: "Plugin", CONNECTOR: "Connettori MCP", KNOWLEDGE: "Knowledge", SKILL: "Skill" };
const HUB_ITEM_LABEL: Record<string, string> = { PLUGIN: "Plugin", CONNECTOR: "Connettore MCP", KNOWLEDGE: "Knowledge", SKILL: "Skill" };
const RESOURCE_LABEL: Record<string, string> = {
  SKILL: "Skill", KNOWLEDGE: "Knowledge", MCP: "Server MCP", TOOL: "Tool", FRAMEWORK: "Framework", TEMPLATE_PROVIDER: "Fonte di template",
};
/** The Hub's own library is the harness: its entries are already nodes when an agent is equipped with them. */
const LIBRARY_APP = "aicos";

const norm = (text: string) => text.trim().toLowerCase().replace(/\s+/g, " ");
const short = (text: string, max = 46) => (text.length > max ? `${text.slice(0, max - 1).trimEnd()}…` : text);
const firstLine = (text: string) => text.split(/\r?\n/).find((l) => l.trim() !== "")?.trim() ?? text;

export function buildGraph(configurations: AgentConfiguration[], hub: HubInstalled | null = null): EcoGraph {
  const nodes = new Map<string, EcoNode>();
  const edges = new Map<string, EcoEdge>();
  const agents = configurations.map(sanitizeAgent);
  const present = new Set(agents.map((a) => a.id));

  const add = (node: EcoNode): EcoNode => {
    const existing = nodes.get(node.id);
    if (existing) return existing;
    nodes.set(node.id, node);
    return node;
  };
  const link = (source: string, target: string, kind: EdgeKind) => {
    const id = `${source}>${target}`;
    if (source !== target && !edges.has(id)) edges.set(id, { id, source, target, kind });
  };
  const search = (...parts: (string | null | undefined)[]) => parts.filter(Boolean).join(" \n ").toLowerCase();

  for (const a of agents) {
    const id = agentId(a.id);
    add({
      id, category: "agent", label: a.name, subtitle: a.role, isGroup: false, size: a.parent && present.has(a.parent.id) ? 0.8 : 1,
      status: a.status !== "ACTIVE" ? "inactive" : a.binding.valid ? "active" : "warning",
      detail: { type: "agent", agent: a },
      search: search(a.name, a.role, a.specialization, a.description, a.domain),
    });
  }

  for (const a of agents) {
    const owner = agentId(a.id);
    if (a.parent && present.has(a.parent.id)) link(agentId(a.parent.id), owner, "subagent");
    bindingNodes(a.binding, a.model, owner, add, link, search);

    for (const [kind, refs] of Object.entries(a.resources)) {
      const category = RESOURCE_CATEGORY[kind];
      if (!category) continue;
      for (const r of refs) {
        const node = add({
          id: `res:${r.key}`, category, label: r.name, subtitle: RESOURCE_LABEL[kind], isGroup: false, size: 1,
          status: "active", detail: { type: "resource", key: r.key, name: r.name, kind: r.kind, description: r.description, configured: r.configuration !== null },
          search: search(r.name, r.key, r.description),
        });
        link(owner, node.id, "equipped");
      }
    }
    for (const s of a.software) {
      add({ id: `sw:${s.key}`, category: "software", label: s.name, subtitle: "Software", isGroup: false, size: 1, status: "active",
        detail: { type: "software", key: s.key, name: s.name }, search: search(s.name, s.key) });
      link(owner, `sw:${s.key}`, "uses-software");
    }

    const text = (field: TextField, title: string, value: string | null) => {
      if (!value) return;
      const nid = `${field}:${a.id}`;
      add({ id: nid, category: field === "responsibilities" || field === "limits" ? "role" : "prompt", label: title,
        subtitle: `${a.name} · ${short(firstLine(value), 60)}`, isGroup: false, size: 1, status: "active",
        detail: { type: "text", agentId: a.id, field, title, text: value, index: null }, search: search(title, value) });
      link(owner, nid, field === "responsibilities" || field === "limits" ? "role" : "prompt");
    };
    text("systemPrompt", "System prompt", a.systemPrompt);
    text("outputFormat", "Output atteso", a.outputFormat);
    text("contextPolicy", "Politica di contesto", a.contextPolicy);
    text("responsibilities", "Responsabilità", a.responsibilities);
    text("limits", "Limiti e permessi", a.limits);

    a.directives.map((d) => d.trim()).filter(Boolean).forEach((d, index) => {
      const nid = `directive:${a.id}:${index}`;
      add({ id: nid, category: "directive", label: `Direttiva ${index + 1}`, subtitle: short(firstLine(d), 70), isGroup: false,
        size: 1, status: "active", detail: { type: "text", agentId: a.id, field: "directive", title: `Direttiva ${index + 1}`, text: d, index },
        search: search(d) });
      link(owner, nid, "directive");
    });

    // Role, domain and capabilities are values: two agents with the same one share its node.
    const value = (field: "role" | "domain" | "capability", raw: string | null, category: Category, kind: EdgeKind) => {
      if (!raw || !raw.trim()) return;
      const v = raw.trim();
      const nid = `${field}:${norm(v)}`;
      add({ id: nid, category, label: short(v), subtitle: field === "role" ? "Ruolo" : field === "domain" ? "Dominio" : "Capability",
        isGroup: false, size: field === "capability" ? 0.9 : 1, status: "active", detail: { type: "value", field, value: v }, search: search(v) });
      link(owner, nid, kind);
    };
    value("role", a.role, "role", "role");
    value("domain", a.domain, "role", "role");
    for (const c of a.capabilities) value("capability", c, "capability", "capability");
  }

  if (hub) hubNodes(hub, nodes, add, link, search);

  const list = [...nodes.values()];
  const edgeList = [...edges.values()].filter((e) => nodes.has(e.source) && nodes.has(e.target));
  const out = new Map<string, EcoEdge[]>();
  const into = new Map<string, EcoEdge[]>();
  for (const e of edgeList) {
    (out.get(e.source) ?? out.set(e.source, []).get(e.source)!).push(e);
    (into.get(e.target) ?? into.set(e.target, []).get(e.target)!).push(e);
  }
  return { nodes: list, edges: edgeList, byId: nodes, out, into, agentIds: agents.map((a) => a.id) };
}

export const agentId = (id: number) => `agent:${id}`;
export const agentOf = (nodeId: string): number | null => (nodeId.startsWith("agent:") ? Number(nodeId.slice(6)) : null);

function bindingNodes(binding: Binding, modelKey: string | null, owner: string, add: (n: EcoNode) => EcoNode,
                      link: (s: string, t: string, k: EdgeKind) => void, search: (...p: (string | null | undefined)[]) => string) {
  const provider = binding.provider;
  const locality = localityOf(provider?.kind);
  if (provider) {
    add({ id: `provider:${provider.key}`, category: "provider", label: provider.name,
      subtitle: locality ? `Provider ${LOCALITY_LABEL[locality]}` : "Provider", isGroup: false, size: 1,
      status: provider.status && provider.status !== "ACTIVE" ? "warning" : "active",
      detail: { type: "provider", key: provider.key, name: provider.name, kind: provider.kind, billing: provider.billing,
        status: provider.status, locality },
      search: search(provider.name, provider.key) });
  }
  const model = binding.model;
  const key = model?.key ?? modelKey;
  if (key) {
    const id = `model:${key}`;
    add({ id, category: "model", label: model?.displayName ?? key,
      subtitle: locality ? `Modello ${LOCALITY_LABEL[locality]}` : "Modello", isGroup: false, size: 1,
      status: model?.lifecycle === "DEPRECATED" || (model !== null && !model.catalogued) ? "warning" : "active",
      detail: { type: "model", key, displayName: model?.displayName ?? key, role: model?.role ?? null, lifecycle: model?.lifecycle ?? null,
        replacedBy: model?.replacedBy ?? null, catalogued: model?.catalogued ?? false, engineRunnable: model?.engineRunnable ?? false,
        providerName: provider?.name ?? null, locality },
      search: search(model?.displayName, key, provider?.name) });
    link(owner, id, "uses-model");
    if (provider) link(id, `provider:${provider.key}`, "served-by");
  }
  const target = binding.target;
  const missing = target.availability === "NOT_INSTALLED";
  add({ id: `target:${target.key}`, category: "target", label: target.name, subtitle: "Dove lavora", isGroup: false, size: 1,
    status: missing ? "warning" : "active",
    detail: { type: "target", key: target.key, name: target.name, delivery: target.delivery, software: target.software,
      availability: target.availability, howItWorks: target.howItWorks, contextFile: target.contextFile },
    search: search(target.name, target.key, target.howItWorks) });
  link(owner, `target:${target.key}`, "runs-on");
}

/**
 * The applications agents work in bring their own skills, plugins and connectors:
 * an agent whose target is Claude Code reaches what Claude Code has installed.
 * Each application becomes clusters -- one per kind -- hung on the targets the
 * Hub says it serves, and only on targets some agent works in. A copy of a library
 * skill is that skill, not a second one.
 */
function hubNodes(hub: HubInstalled, nodes: Map<string, EcoNode>, add: (n: EcoNode) => EcoNode,
                  link: (s: string, t: string, k: EdgeKind) => void, search: (...p: (string | null | undefined)[]) => string) {
  for (const app of hub.apps) {
    if (app.id === LIBRARY_APP) continue;
    const targets = app.targets.map((t) => `target:${t}`).filter((t) => nodes.has(t));
    if (targets.length === 0) continue;
    const items = hub.skills.filter((s) => s.app === app.id && s.scope !== "LIBRARY");
    const byKind = new Map<string, HubSkill[]>();
    for (const s of items) {
      const kind = HUB_CATEGORY[s.kind] ? s.kind : "SKILL";
      (byKind.get(kind) ?? byKind.set(kind, []).get(kind)!).push(s);
    }
    for (const [kind, skills] of byKind) {
      const category = HUB_CATEGORY[kind];
      const groupId = `hubgroup:${app.id}:${kind}`;
      const label = `${HUB_KIND_LABEL[kind]} · ${app.name}`;
      add({ id: groupId, category, label, subtitle: `${skills.length} in ${app.name}`, isGroup: true,
        size: Math.min(2.2, 0.9 + Math.sqrt(skills.length) * 0.12), status: "active",
        detail: { type: "group", app: app.id, appName: app.name, kind, count: skills.length },
        search: search(label, app.name) });
      for (const t of targets) link(t, groupId, "offers");
      for (const raw of skills) {
        const library = raw.libraryKey ? `res:${raw.libraryKey}` : null;
        if (library && nodes.has(library)) {
          link(groupId, library, "member");
          continue;
        }
        const s = sanitizeHubSkill(raw);
        const id = `hub:${s.id}`;
        add({ id, category, label: s.name, subtitle: `${HUB_ITEM_LABEL[kind]} · ${app.name}`,
          isGroup: false, size: 0.85, status: s.enabled === false ? "inactive" : s.updateAvailable ? "warning" : "active",
          detail: { type: "hub", skill: s, appName: app.name }, search: search(s.name, s.description, s.plugin, s.categoryLabel, app.name) });
        link(groupId, id, "member");
      }
    }
  }
}

// --- what is shown -----------------------------------------------------------------------

export type Mode = "ecosystem" | "agent";

export interface ViewState {
  mode: Mode;
  /** The agent of the single-agent view. */
  focus: number | null;
  /** The agents shown in the ecosystem view; null: all of them. */
  agents: number[] | null;
  hidden: Category[];
  expanded: string[];
  collapsed: number[];
}

export const INITIAL_VIEW: ViewState = { mode: "ecosystem", focus: null, agents: null, hidden: [...ECOSYSTEM_HIDDEN], expanded: [], collapsed: [] };

export interface VisibleGraph {
  /** Agents first, then everything in the order it was reached: owners before what they own. */
  nodes: EcoNode[];
  edges: EcoEdge[];
  ids: Set<string>;
  /** The visible nodes a node hangs from: where the layout puts it. */
  owners: Map<string, string[]>;
  /** In the single-agent view, the parent and sub-agents shown around it, without their properties. */
  context: Set<number>;
  /** Per visible agent, how many of its own nodes are not shown now. */
  hiddenOf: Map<number, number>;
}

/**
 * Which part of the graph the view shows: the agents chosen, and what they own --
 * walking the ownership edges, stopping at a hidden category, a collapsed agent or a
 * cluster not expanded. Edges are drawn between any two nodes shown.
 */
export function visibleGraph(graph: EcoGraph, view: ViewState): VisibleGraph {
  const hidden = new Set(view.hidden);
  const expanded = new Set(view.expanded);
  const collapsed = new Set(view.collapsed);
  const context = new Set<number>();
  let seeds: number[];
  if (view.mode === "agent" && view.focus !== null && graph.byId.has(agentId(view.focus))) {
    seeds = [view.focus];
    for (const e of graph.into.get(agentId(view.focus)) ?? []) if (e.kind === "subagent") context.add(agentOf(e.source)!);
    for (const e of graph.out.get(agentId(view.focus)) ?? []) if (e.kind === "subagent") context.add(agentOf(e.target)!);
  } else {
    const chosen = view.agents ? new Set(view.agents) : null;
    seeds = graph.agentIds.filter((id) => !chosen || chosen.has(id));
  }

  const ids = new Set<string>();
  const order: EcoNode[] = [];
  const owners = new Map<string, string[]>();
  const queue: string[] = [];
  for (const id of [...seeds, ...context]) {
    const nid = agentId(id);
    if (ids.has(nid) || !graph.byId.has(nid)) continue;
    ids.add(nid);
    order.push(graph.byId.get(nid)!);
    if (!context.has(id) && !collapsed.has(id)) queue.push(nid);
  }
  for (let i = 0; i < queue.length; i++) {
    const u = queue[i];
    for (const e of graph.out.get(u) ?? []) {
      if (e.kind === "subagent") continue;
      const v = graph.byId.get(e.target)!;
      if (v.category === "agent" || hidden.has(v.category)) continue;
      (owners.get(v.id) ?? owners.set(v.id, []).get(v.id)!).push(u);
      if (ids.has(v.id)) continue;
      ids.add(v.id);
      order.push(v);
      if (!v.isGroup || expanded.has(v.id)) queue.push(v.id);
    }
  }
  const edges = graph.edges.filter((e) => ids.has(e.source) && ids.has(e.target));

  const hiddenOf = new Map<number, number>();
  for (const node of order) {
    const id = agentOf(node.id);
    if (id === null) continue;
    hiddenOf.set(id, (graph.out.get(node.id) ?? []).filter((e) => e.kind !== "subagent" && !ids.has(e.target)).length);
  }
  return { nodes: order, edges, ids, owners, context, hiddenOf };
}

/** Every node joined to this one by an edge, either way. */
export function neighbours(graph: EcoGraph, id: string): Set<string> {
  const near = new Set<string>([id]);
  for (const e of graph.out.get(id) ?? []) near.add(e.target);
  for (const e of graph.into.get(id) ?? []) near.add(e.source);
  return near;
}

/** The agents a node belongs to: itself for an agent, its owners' agents otherwise. */
export function agentsOf(graph: EcoGraph, id: string): number[] {
  const found = new Set<number>();
  const seen = new Set<string>();
  const walk = (nid: string) => {
    if (seen.has(nid)) return;
    seen.add(nid);
    const a = agentOf(nid);
    if (a !== null) { found.add(a); return; }
    for (const e of graph.into.get(nid) ?? []) walk(e.source);
  };
  walk(id);
  return [...found].sort((x, y) => x - y);
}

export interface SearchHit { node: EcoNode; where: "name" | "text" }

/** Name first, prefix before substring, then what the text says; at most `limit`. */
export function searchNodes(graph: EcoGraph, query: string, limit = 30): SearchHit[] {
  const q = query.trim().toLowerCase();
  if (q.length < 2) return [];
  const scored: { node: EcoNode; score: number; where: SearchHit["where"] }[] = [];
  for (const node of graph.nodes) {
    const label = node.label.toLowerCase();
    let score = -1;
    let where: SearchHit["where"] = "name";
    if (label === q) score = 0;
    else if (label.startsWith(q)) score = 1;
    else if (label.includes(q)) score = 2;
    else if (node.search.includes(q)) { score = 3; where = "text"; }
    if (score < 0) continue;
    if (node.category === "agent") score -= 0.5;
    scored.push({ node, score, where });
  }
  scored.sort((a, b) => a.score - b.score || a.node.label.localeCompare(b.node.label));
  return scored.slice(0, limit).map(({ node, where }) => ({ node, where }));
}

/**
 * The view that shows this node: its category shown, its cluster expanded, one of
 * its agents shown and not collapsed. A node already shown leaves the view as it is.
 */
export function reveal(graph: EcoGraph, view: ViewState, id: string): ViewState {
  if (visibleGraph(graph, view).ids.has(id)) return view;
  const node = graph.byId.get(id);
  if (!node) return view;
  let next: ViewState = { ...view, hidden: view.hidden.filter((c) => c !== node.category) };
  // Clusters on the way: the node's own, and the cluster of a member it hangs from.
  const groups = (graph.into.get(id) ?? []).filter((e) => e.kind === "member").map((e) => e.source);
  if (groups.length && !(graph.into.get(id) ?? []).some((e) => e.kind !== "member")) {
    next = { ...next, expanded: [...new Set([...next.expanded, groups[0]])] };
  }
  const agents = agentsOf(graph, id);
  if (next.mode === "agent") {
    if (next.focus !== null && agents.includes(next.focus) && visibleGraph(graph, next).ids.has(id)) return next;
    const focus = agents[0] ?? next.focus;
    next = { ...next, focus };
  } else {
    if (next.agents && !agents.some((a) => next.agents!.includes(a)) && agents.length) {
      next = { ...next, agents: [...next.agents, agents[0]] };
    }
    if (agents.length && agents.every((a) => next.collapsed.includes(a))) {
      next = { ...next, collapsed: next.collapsed.filter((a) => a !== agents[0]) };
    }
  }
  return next;
}

/** Counts per category of the whole graph, for the legend. */
export function countByCategory(nodes: Iterable<EcoNode>): Record<Category, number> {
  const counts = Object.fromEntries(CATEGORIES.map((c) => [c.key, 0])) as Record<Category, number>;
  for (const n of nodes) if (!n.isGroup) counts[n.category]++;
  return counts;
}

/** A short fingerprint of the data: an unchanged poll leaves the scene as it is. */
export function signature(configurations: AgentConfiguration[], hub: HubInstalled | null): string {
  return JSON.stringify([configurations, hub ? [hub.apps, hub.skills] : null]);
}
