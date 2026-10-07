// The Knowledge Hub's view of what is installed (PHASE 31, ADR-036): pure
// functions over the server's answer -- labels, filters, and the grouping the
// operator asked for: by application, then by kind and type -- the library,
// plugins, connectors, personal and built-in skills, each plugin's skills,
// knowledge and memory.
import type { CopyStatus, HubApp, HubKind, HubScope, HubSkill } from "../../api/types";

export const SCOPE_LABEL: Record<HubScope, string> = {
  LIBRARY: "Libreria",
  PERSONAL: "Personali",
  PLUGIN: "Dai plugin",
  SYSTEM: "Di sistema",
  ACCOUNT: "Dell'account",
  KNOWLEDGE: "Knowledge",
  INSTRUCTIONS: "Istruzioni globali",
};

export const SCOPE_HINT: Record<HubScope, string> = {
  LIBRARY: "Le skill e le knowledge di AI Company OS: modificabili qui, associabili ad agenti e progetti.",
  PERSONAL: "Scritte o configurate da te (o dal Knowledge Hub) per l'app.",
  PLUGIN: "Arrivano con un plugin installato nell'app: si aggiornano con il plugin.",
  SYSTEM: "Fornite dall'app stessa.",
  ACCOUNT: "Arrivano con il tuo account (per esempio i connettori di claude.ai).",
  KNOWLEDGE: "Elementi di conoscenza e memoria che l'app conserva.",
  INSTRUCTIONS: "Le istruzioni globali che l'app legge a ogni sessione.",
};

export const SCOPE_ORDER: HubScope[] = ["LIBRARY", "PERSONAL", "PLUGIN", "SYSTEM", "ACCOUNT", "KNOWLEDGE", "INSTRUCTIONS"];

export const KIND_LABEL: Record<HubKind, string> = {
  SKILL: "Skill",
  PLUGIN: "Plugin",
  CONNECTOR: "Connettori",
  KNOWLEDGE: "Knowledge",
};

export const KIND_ORDER: HubKind[] = ["SKILL", "PLUGIN", "CONNECTOR", "KNOWLEDGE"];

export type CopyState = CopyStatus | "NONE" | "FOREIGN";

export const COPY_LABEL: Record<CopyState, { label: string; tone: string }> = {
  IN_SYNC: { label: "sincronizzata", tone: "badge badge-ok" },
  OUTDATED: { label: "da aggiornare", tone: "badge badge-warn" },
  MODIFIED: { label: "modificata nell'app", tone: "badge badge-accent" },
  CONFLICT: { label: "conflitto", tone: "badge badge-danger" },
  ORPHAN: { label: "non più in libreria", tone: "badge" },
  NONE: { label: "non installata", tone: "badge" },
  FOREIGN: { label: "cartella non del Hub", tone: "badge badge-warn" },
};

export type StatusFilter = "" | "updates" | "copies" | "disabled";

export interface Filters {
  app: string | null;
  kind: HubKind | "";
  scope: HubScope | "";
  category: string;
  query: string;
  status: StatusFilter;
}

export const NO_FILTERS: Filters = { app: null, kind: "", scope: "", category: "", query: "", status: "" };

/** A skill's name as a family key: the same skill in two applications is "the same" if this matches. */
export function family(name: string): string {
  return name.trim().toLowerCase().replace(/[\s_]+/g, "-");
}

export function kindOf(skill: HubSkill): HubKind {
  return KIND_ORDER.includes(skill.kind as HubKind) ? skill.kind as HubKind : "SKILL";
}

/** For every location, the applications that read it: a shared folder (~/.agents/skills) serves several. */
export function readersByLocation(apps: HubApp[]): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const app of apps) for (const location of app.locations ?? []) out.set(location.id, location.readers ?? []);
  return out;
}

/** An item is an application's when it is in one of its folders -- or in a shared folder that application reads. */
export function belongsTo(skill: HubSkill, app: string, readers: Map<string, string[]> = new Map()): boolean {
  return skill.app === app || (readers.get(skill.location) ?? []).includes(app);
}

export function matches(skill: HubSkill, filters: Filters, readers?: Map<string, string[]>): boolean {
  if (filters.app && !belongsTo(skill, filters.app, readers)) return false;
  if (filters.kind && kindOf(skill) !== filters.kind) return false;
  if (filters.scope && skill.scope !== filters.scope) return false;
  if (filters.category && skill.category !== filters.category) return false;
  if (filters.status === "updates" && !skill.updateAvailable) return false;
  if (filters.status === "copies" && !skill.copyStatus && !(skill.copies && skill.copies.length > 0)) return false;
  if (filters.status === "disabled" && skill.enabled !== false) return false;
  const q = filters.query.trim().toLowerCase();
  if (q) {
    const text = [skill.name, skill.description, skill.plugin, skill.categoryLabel, skill.libraryKey,
      ...Object.values(skill.details ?? {})].filter(Boolean).join(" ").toLowerCase();
    if (!q.split(/\s+/).every((word) => text.includes(word))) return false;
  }
  return true;
}

export interface Group {
  key: string;
  scope: HubScope;
  kind: HubKind | "LIBRARY";
  title: string;
  subtitle: string | null;
  skills: HubSkill[];
}

export interface AppSection {
  app: HubApp;
  groups: Group[];
  total: number;
}

/**
 * Applications in the server's order, each with its items grouped: the
 * library; the plugins; the connectors; personal skills; built-in and account
 * skills; each plugin's skills (with the plugin's version); knowledge and
 * memory. Sorted by name; applications with nothing left are left out.
 */
export function sections(apps: HubApp[], skills: HubSkill[], focus: string | null = null): AppSection[] {
  const out: AppSection[] = [];
  for (const app of apps) {
    if (focus && app.id !== focus) continue;
    const mine = skills.filter((s) => s.app === app.id);
    // Looking at one application: what it reads from a shared folder is listed with it, under that folder's name.
    const shared = focus ? skills.filter((s) => s.app !== app.id) : [];
    if (mine.length === 0 && shared.length === 0) continue;
    const groups: Group[] = [];
    const add = (key: string, scope: HubScope, kind: Group["kind"], title: string, list: HubSkill[], subtitle: string | null = null) => {
      if (list.length > 0) groups.push({ key: `${app.id}:${key}`, scope, kind, title, subtitle, skills: byName(list) });
    };
    const library = mine.filter((s) => s.scope === "LIBRARY");
    const rest = mine.filter((s) => s.scope !== "LIBRARY");
    add("library", "LIBRARY", "LIBRARY", SCOPE_LABEL.LIBRARY, library);
    add("plugins", "PLUGIN", "PLUGIN", "Plugin installati", rest.filter((s) => kindOf(s) === "PLUGIN"));
    add("connectors", "PERSONAL", "CONNECTOR", "Connettori (MCP)", rest.filter((s) => kindOf(s) === "CONNECTOR"));
    const skillsOnly = rest.filter((s) => kindOf(s) === "SKILL");
    add("personal", "PERSONAL", "SKILL", "Skill personali", skillsOnly.filter((s) => s.scope === "PERSONAL"));
    add("system", "SYSTEM", "SKILL", "Skill di sistema", skillsOnly.filter((s) => s.scope === "SYSTEM"));
    add("account", "ACCOUNT", "SKILL", "Skill dell'account", skillsOnly.filter((s) => s.scope === "ACCOUNT"));
    const byPlugin = new Map<string, HubSkill[]>();
    for (const skill of skillsOnly.filter((s) => s.scope === "PLUGIN")) {
      const name = skill.plugin ?? "plugin";
      byPlugin.set(name, [...(byPlugin.get(name) ?? []), skill]);
    }
    for (const [plugin, list] of [...byPlugin.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      const version = list.find((s) => s.pluginVersion)?.pluginVersion;
      const market = list.find((s) => s.marketplace)?.marketplace;
      add(`plugin:${plugin}`, "PLUGIN", "SKILL", `Skill del plugin ${plugin}`, list,
        [version ? `v${version}` : null, market].filter(Boolean).join(" · ") || null);
    }
    add("knowledge", "KNOWLEDGE", "KNOWLEDGE", "Knowledge, istruzioni e memoria", rest.filter((s) => kindOf(s) === "KNOWLEDGE"));
    const byLocation = new Map<string, HubSkill[]>();
    for (const skill of shared) byLocation.set(skill.location, [...(byLocation.get(skill.location) ?? []), skill]);
    for (const [location, list] of byLocation) {
      const owner = apps.find((a) => a.id === list[0].app);
      const where = owner?.locations?.find((l) => l.id === location);
      add(`shared:${location}`, list[0].scope, "SKILL", `Lette anche da qui · ${where?.label ?? location}`, list,
        [owner?.name, where?.path].filter(Boolean).join(" · ") || null);
    }
    out.push({ app, groups, total: mine.length + shared.length });
  }
  return out;
}

/** For every skill family, the applications that have it -- "also in Codex, Verdent". Plugins and connectors by their own kind. */
export function presence(skills: HubSkill[]): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const skill of skills) {
    const key = presenceKey(skill);
    const apps = out.get(key) ?? [];
    if (!apps.includes(skill.app)) apps.push(skill.app);
    out.set(key, apps);
  }
  return out;
}

export function presenceKey(skill: HubSkill): string {
  const kind = kindOf(skill);
  return `${kind === "KNOWLEDGE" ? "SKILL" : kind}:${family(skill.name)}`;
}

export function countBy<T>(items: T[], key: (item: T) => string): Map<string, number> {
  const out = new Map<string, number>();
  for (const item of items) out.set(key(item), (out.get(key(item)) ?? 0) + 1);
  return out;
}

/** A key the library accepts, from any name: lower case, dashes, 2-64 characters. */
export function slug(name: string): string {
  return name.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-+|-+$)/g, "").slice(0, 64).replace(/-+$/, "");
}

export function formatCount(n: number | null | undefined): string {
  if (n === null || n === undefined) return "";
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1).replace(".0", "")} M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1).replace(".0", "")} k`;
  return String(n);
}

function byName(list: HubSkill[]): HubSkill[] {
  return [...list].sort((a, b) => a.name.localeCompare(b.name));
}
