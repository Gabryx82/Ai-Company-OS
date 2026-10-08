import { describe, expect, it } from "vitest";
import type { BlueprintBlock, BlueprintGraph, BlueprintRelation, BlueprintRelationInfo } from "../../api/types";
import {
  areaInView, blocksInArea, chipsBySource, defaultFilters, detailFor, diffLines, neighbours, overlayTexts, search, visibleBlocks,
  visibleRelations,
} from "./blueprintModel";

function block(id: number, name: string, extra: Partial<BlueprintBlock> = {}): BlueprintBlock {
  return { id, key: `java:${name}`, kind: "SERVICE", layer: "BACKEND", family: "CLASSES", origin: "DETECTED", status: "IMPLEMENTED",
    pathState: "LINKED", name, filePath: `backend/src/${name}.java`, language: "java", description: null, pseudocodePreview: null,
    tags: [], parentId: null, expanded: false, expandable: false, members: 0, version: 0, ...extra };
}

function relation(id: number, source: number, target: number, extra: Partial<BlueprintRelation> = {}): BlueprintRelation {
  return { id, source, target, relation: "USES", family: "DEPENDENCY", origin: "DETECTED", label: null, description: null, code: null,
    version: 0, ...extra };
}

const graph: BlueprintGraph = {
  projectId: 1, archived: false, workspaceReady: true,
  nodes: [
    block(1, "TaskController", { kind: "CONTROLLER", family: "APIS" }),
    block(2, "TaskService"),
    block(3, "TaskRepository", { kind: "REPOSITORY", layer: "DATABASE", family: "DATA" }),
    block(4, "App", { kind: "COMPONENT", layer: "FRONTEND", filePath: "web/src/App.tsx", language: "typescript" }),
    block(5, "OldThing", { pathState: "MISSING" }),
    block(6, "find()", { kind: "FUNCTION", family: "METHODS", parentId: 2 }),
  ],
  edges: [
    relation(10, 1, 2, { relation: "CALLS", label: "find()" }),
    relation(11, 2, 3, { relation: "SERVICE_TO_REPOSITORY" }),
    relation(12, 4, 1, { relation: "CALLS", origin: "MANUAL" }),
    relation(13, 2, 5, { origin: "PROPOSED" }),
  ],
  layout: { positions: {}, version: 0 }, lastScan: null,
  metrics: { totalNodes: 6, files: 5, classes: 3, endpoints: 0, databaseEntities: 0, tests: 0, detectedRelations: 2,
    manualRelations: 1, proposedRelations: 1, unresolvedRelations: 0, missingNodes: 1, byLayer: {}, byFamily: {} },
  pendingProposals: 0,
};

const relations: BlueprintRelationInfo[] = [
  { relation: "USES", label: "usa", family: "DEPENDENCY", description: "" },
  { relation: "CALLS", label: "chiama", family: "DEPENDENCY", description: "" },
  { relation: "SERVICE_TO_REPOSITORY", label: "service → repository", family: "DEPENDENCY", description: "" },
];

describe("the Blueprint view model", () => {
  it("filters by layer, family, missing blocks and methods", () => {
    const f = defaultFilters();
    expect(visibleBlocks(graph, f)).toHaveLength(6);
    expect(visibleBlocks(graph, { ...f, layers: ["FRONTEND"] }).map((b) => b.name)).toEqual(["App"]);
    expect(visibleBlocks(graph, { ...f, layers: ["BACKEND", "DATABASE"], families: ["DATA"] }).map((b) => b.name)).toEqual(["TaskRepository"]);
    expect(visibleBlocks(graph, { ...f, showMissing: false }).map((b) => b.id)).not.toContain(5);
    expect(visibleBlocks(graph, { ...f, showMethods: false }).map((b) => b.id)).not.toContain(6);
  });

  it("shows a relation only when both ends are visible and its origin is wanted", () => {
    const f = defaultFilters();
    const visible = new Set([1, 2, 3]);
    expect(visibleRelations(graph, visible, f).map((e) => e.id)).toEqual([10, 11]);
    expect(visibleRelations(graph, new Set([1, 2, 3, 4, 5]), { ...f, origins: ["MANUAL", "PROPOSED"] }).map((e) => e.id)).toEqual([12, 13]);
  });

  it("finds blocks by name first, then by path", () => {
    expect(search(graph.nodes, "task").map((b) => b.name)).toEqual(["TaskService", "TaskController", "TaskRepository"]);
    expect(search(graph.nodes, "web/src").map((b) => b.name)).toEqual(["App"]);
    expect(search(graph.nodes, "   ")).toEqual([]);
  });

  it("shows more of a block the closer the zoom", () => {
    expect(detailFor(0.2)).toBe(0);
    expect(detailFor(0.6)).toBe(1);
    expect(detailFor(1.2)).toBe(2);
  });

  it("turns outgoing relations into chips, capped per block", () => {
    const chips = chipsBySource(graph, relations, 1);
    expect(chips.get(1)?.chips).toEqual([{ edgeId: 10, targetId: 2, targetName: "TaskService", label: "chiama", origin: "DETECTED" }]);
    expect(chips.get(2)?.chips).toHaveLength(1);
    expect(chips.get(2)?.more).toBe(1);
    expect(chips.get(3)).toBeUndefined();
  });

  it("knows dependencies and dependents", () => {
    expect(neighbours(graph, 2, "out")).toEqual([3, 5]);
    expect(neighbours(graph, 2, "in")).toEqual([1]);
  });

  it("reads a unified diff line by line", () => {
    expect(diffLines("--- a/X.java\n+++ b/X.java\n@@ -1,2 +1,3 @@\n class X {\n+    private final Y y;\n-    old\n").map((l) => l.kind))
      .toEqual(["meta", "meta", "hunk", "ctx", "add", "del"]);
  });
});

describe("progressive loading (TD-61)", () => {
  const placed: BlueprintGraph = { ...graph, layout: { positions: { 1: { x: 0, y: 0 }, 2: { x: 400, y: 0 }, 3: { x: 2000, y: 2000 } }, version: 1 } };

  it("lays the texts read over the outline, until the row changes", () => {
    const full = block(2, "TaskService", { description: "Regole delle task.", version: 0 });
    const texts = { blocks: new Map([[2, full]]), edges: new Map([[11, relation(11, 2, 3, { label: "save()" })]]) };
    const over = overlayTexts(graph, texts);
    expect(over.nodes.find((n) => n.id === 2)!.description).toBe("Regole delle task.");
    expect(over.edges.find((e) => e.id === 11)!.label).toBe("save()");
    const changed = { ...graph, nodes: graph.nodes.map((n) => (n.id === 2 ? { ...n, version: 1 } : n)) };
    expect(overlayTexts(changed, texts).nodes.find((n) => n.id === 2)!.description).toBeNull();
  });

  it("knows the area in view and the blocks in it", () => {
    // zoom 0.5, panned by (100, 50), on a 800x600 pane: canvas x from -200 to 1400, y from -100 to 1100
    expect(areaInView([100, 50, 0.5], 800, 600, 0)).toEqual([-200, -100, 1400, 1100]);
    expect(blocksInArea(placed, [-200, -100, 1400, 1100]).map((b) => b.id)).toEqual([1, 2]);
    expect(blocksInArea(placed, [2100, 2100, 2150, 2150]).map((b) => b.id)).toEqual([3]);
    expect(blocksInArea(placed, [1900, 1900, 1950, 1950])).toEqual([]);
  });
});
