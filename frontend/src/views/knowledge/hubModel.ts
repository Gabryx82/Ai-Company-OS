// The Knowledge Hub's view of what is installed (PHASE 31, ADR-036): pure
// functions over the server's answer -- labels, filters, and the grouping the
// operator asked for: by application, then by type (library, personal, plugin,
// built-in, instructions), plugins one by one.
import type { CopyStatus, HubApp, HubScope, HubSkill } from "../../api/types";

export const SCOPE_LABEL: Record<HubScope, string> = {
  LIBRARY: "Libreria",
  PERSONAL: "Personali",
  PLUGIN: "Plugin",
  SYSTEM: "Di sistema",
  KNOWLEDGE: "Knowledge",
  INSTRUCTIONS: "Istruzioni globali",
};

export const SCOPE_HINT: Record<HubScope, string> = {
  LIBRARY: "Le skill e le knowledge di AI Company OS: modificabili qui, associabili ad agenti e progetti.",
  PERSONAL: "Scritte da te (o dal Knowledge Hub) nella cartella personale dell'app.",
  PLUGIN: "Arrivano con un plugin installato nell'app: si aggiornano con il plugin.",
  SYSTEM: "Fornite dall'app stessa.",
  KNOWLEDGE: "Elementi di conoscenza che l'app conserva.",
  INSTRUCTIONS: "Le istruzioni globali che l'app legge a ogni sessione.",
};

export const SCOPE_ORDER: HubScope[] = ["LIBRARY", "PERSONAL", "PLUGIN", "SYSTEM", "KNOWLEDGE", "INSTRUCTIONS"];

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
  scope: HubScope | "";
  category: string;
  query: string;
  status: StatusFilter;
}

export const NO_FILTERS: Filters = { app: null, scope: "", category: "", query: "", status: "" };

/** A skill's name as a family key: the same skill in two applications is "the same" if this matches. */
export function family(name: string): string {
  return name.trim().toLowerCase().replace(/[\s_]+/g, "-");
}

export function matches(skill: HubSkill, filters: Filters): boolean {
  if (filters.app && skill.app !== filters.app) return false;
  if (filters.scope && skill.scope !== filters.scope) return false;
  if (filters.category && skill.category !== filters.category) return false;
  if (filters.status === "updates" && !skill.updateAvailable) return false;
  if (filters.status === "copies" && !skill.copyStatus && !(skill.copies && skill.copies.length > 0)) return false;
  if (filters.status === "disabled" && skill.enabled !== false) return false;
  const q = filters.query.trim().toLowerCase();
  if (q) {
    const text = [skill.name, skill.description, skill.plugin, skill.categoryLabel, skill.libraryKey]
      .filter(Boolean).join(" ").toLowerCase();
    if (!q.split(/\s+/).every((word) => text.includes(word))) return false;
  }
  return true;
}

export interface Group {
  key: string;
  scope: HubScope;
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
 * Applications in the server's order, each with its skills grouped by type --
 * plugins one group per plugin -- and sorted by name. Applications with nothing
 * left after filtering are left out.
 */
export function sections(apps: HubApp[], skills: HubSkill[]): AppSection[] {
  const out: AppSection[] = [];
  for (const app of apps) {
    const mine = skills.filter((s) => s.app === app.id);
    if (mine.length === 0) continue;
    const groups: Group[] = [];
    for (const scope of SCOPE_ORDER) {
      const ofScope = mine.filter((s) => s.scope === scope);
      if (ofScope.length === 0) continue;
      if (scope === "PLUGIN") {
        const byPlugin = new Map<string, HubSkill[]>();
        for (const skill of ofScope) {
          const name = skill.plugin ?? "plugin";
          byPlugin.set(name, [...(byPlugin.get(name) ?? []), skill]);
        }
        for (const [plugin, list] of [...byPlugin.entries()].sort(([a], [b]) => a.localeCompare(b))) {
          const version = list.find((s) => s.pluginVersion)?.pluginVersion;
          const market = list.find((s) => s.marketplace)?.marketplace;
          groups.push({
            key: `${app.id}:plugin:${plugin}`, scope, title: `Plugin ${plugin}`,
            subtitle: [version ? `v${version}` : null, market].filter(Boolean).join(" · ") || null,
            skills: byName(list),
          });
        }
      } else {
        groups.push({ key: `${app.id}:${scope}`, scope, title: SCOPE_LABEL[scope], subtitle: null, skills: byName(ofScope) });
      }
    }
    out.push({ app, groups, total: mine.length });
  }
  return out;
}

/** For every skill family, the applications that have it -- "also in Codex, Verdent". */
export function presence(skills: HubSkill[]): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const skill of skills) {
    const key = family(skill.name);
    const apps = out.get(key) ?? [];
    if (!apps.includes(skill.app)) apps.push(skill.app);
    out.set(key, apps);
  }
  return out;
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
