// The Blueprint's view model (ADR-033): which blocks and relations are on the
// canvas under the current filters, what a search finds, how much detail a zoom
// level shows, and the relation chips inside each block. Pure functions of the
// graph the server sent -- no classification of its own, and no DOM.
import type {
  BlueprintBlock, BlueprintFamily, BlueprintGraph, BlueprintLayer, BlueprintRelation, BlueprintRelationInfo, EdgeOrigin,
} from "../../api/types";

export interface Filters {
  /** Empty: every layer. */
  layers: BlueprintLayer[];
  /** Empty: every family. */
  families: BlueprintFamily[];
  origins: EdgeOrigin[];
  showMissing: boolean;
  showMethods: boolean;
}

export const ALL_ORIGINS: EdgeOrigin[] = ["DETECTED", "MANUAL", "PROPOSED"];

export function defaultFilters(): Filters {
  return { layers: [], families: [], origins: [...ALL_ORIGINS], showMissing: true, showMethods: true };
}

export function visibleBlocks(graph: BlueprintGraph, f: Filters): BlueprintBlock[] {
  return graph.nodes.filter((n) =>
    (f.layers.length === 0 || f.layers.includes(n.layer))
    && (f.families.length === 0 || f.families.includes(n.family))
    && (f.showMissing || n.pathState !== "MISSING")
    && (f.showMethods || n.parentId === null));
}

export function visibleRelations(graph: BlueprintGraph, visible: Set<number>, f: Filters): BlueprintRelation[] {
  return graph.edges.filter((e) => visible.has(e.source) && visible.has(e.target) && f.origins.includes(e.origin));
}

/** Blocks whose name, path or key contain the query; names that start with it first. */
export function search(blocks: BlueprintBlock[], query: string, limit = 12): BlueprintBlock[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const scored: { block: BlueprintBlock; score: number }[] = [];
  for (const b of blocks) {
    const name = b.name.toLowerCase();
    const path = (b.filePath ?? "").toLowerCase();
    let score = -1;
    if (name === q) score = 0;
    else if (name.startsWith(q)) score = 1;
    else if (name.includes(q)) score = 2;
    else if (path.includes(q) || b.key.toLowerCase().includes(q)) score = 3;
    if (score >= 0) scored.push({ block: b, score });
  }
  return scored.sort((a, b) => a.score - b.score || a.block.name.length - b.block.name.length
    || a.block.name.localeCompare(b.block.name)).slice(0, limit).map((s) => s.block);
}

/**
 * Level of detail for a zoom (directive §14): far away a block is its name and
 * kind; at medium zoom also its path and a short description; up close its
 * description, relation chips and a pseudocode preview.
 */
export type Detail = 0 | 1 | 2;

export function detailFor(zoom: number): Detail {
  if (zoom < 0.45) return 0;
  if (zoom < 0.9) return 1;
  return 2;
}

export interface Chip { edgeId: number; targetId: number; targetName: string; label: string; origin: EdgeOrigin }

/** The outgoing relations of each block, as the chips it shows: [USA → TaskService]. */
export function chipsBySource(graph: BlueprintGraph, relations: BlueprintRelationInfo[], perBlock = 4):
  Map<number, { chips: Chip[]; more: number }> {
  const label = new Map(relations.map((r) => [r.relation, r.label]));
  const names = new Map(graph.nodes.map((n) => [n.id, n.name]));
  const out = new Map<number, { chips: Chip[]; more: number }>();
  for (const e of graph.edges) {
    const entry = out.get(e.source) ?? { chips: [], more: 0 };
    if (entry.chips.length < perBlock) {
      entry.chips.push({ edgeId: e.id, targetId: e.target, targetName: names.get(e.target) ?? `#${e.target}`,
        label: label.get(e.relation) ?? e.relation, origin: e.origin });
    } else {
      entry.more += 1;
    }
    out.set(e.source, entry);
  }
  return out;
}

/** The blocks a block depends on (outgoing) or that depend on it (incoming). */
export function neighbours(graph: BlueprintGraph, id: number, direction: "out" | "in"): number[] {
  return [...new Set(graph.edges.filter((e) => (direction === "out" ? e.source : e.target) === id)
    .map((e) => (direction === "out" ? e.target : e.source)))];
}

/** A unified diff, line by line, for the viewer. */
export function diffLines(diff: string): { kind: "add" | "del" | "hunk" | "meta" | "ctx"; text: string }[] {
  return diff.replace(/\n$/, "").split("\n").map((text) => {
    if (text.startsWith("+++") || text.startsWith("---")) return { kind: "meta", text };
    if (text.startsWith("@@")) return { kind: "hunk", text };
    if (text.startsWith("+")) return { kind: "add", text };
    if (text.startsWith("-")) return { kind: "del", text };
    return { kind: "ctx", text };
  });
}

/** Where a new block goes when nothing says otherwise: the first free slot right of everything. */
export function freeSpot(positions: Record<string, { x: number; y: number }>): { x: number; y: number } {
  const xs = Object.values(positions);
  if (xs.length === 0) return { x: 0, y: 0 };
  return { x: Math.max(...xs.map((p) => p.x)) + 340, y: Math.min(...xs.map((p) => p.y)) };
}

export function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}
