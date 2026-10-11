import { describe, expect, it } from "vitest";
import { INITIAL_VIEW, buildGraph, visibleGraph, type ViewState } from "./ecosystemModel";
import { bounds, layoutGraph, nodeRadius, type Vec3 } from "./ecosystemLayout";
import { agent, binding, hub, resource, team, QWEN } from "./fixtures";

const ALL: ViewState = { ...INITIAL_VIEW, hidden: [] };
const distance = (a: Vec3, b: Vec3) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);

/** Twenty agents, each with a dozen things of its own and two shared by all. */
function crowd() {
  return Array.from({ length: 20 }, (_, i) => agent(i + 1, `Agente ${i + 1}`, {
    model: QWEN.key, binding: binding("engine", QWEN), role: `Ruolo ${i % 4}`,
    capabilities: Array.from({ length: 5 }, (_, k) => `capacità ${i}-${k}`),
    directives: ["Prima i test", "Poi il codice"], systemPrompt: "Sei un agente.",
    resources: {
      SKILL: [resource("shared-skill", "SKILL"), ...Array.from({ length: 3 }, (_, k) => resource(`skill-${i}-${k}`, "SKILL"))],
      KNOWLEDGE: [resource("shared-docs", "KNOWLEDGE")],
    },
  }));
}

describe("the 3D layout (ADR-037)", () => {
  it("is the same for the same graph, and every coordinate is a number", () => {
    const g = buildGraph(team(), hub());
    const v = visibleGraph(g, { ...ALL, expanded: ["hubgroup:claude-code:SKILL"] });
    const a = layoutGraph(v, { mode: "ecosystem", focus: null });
    const b = layoutGraph(v, { mode: "ecosystem", focus: null });
    expect([...a.entries()]).toEqual([...b.entries()]);
    expect(a.size).toBe(v.nodes.length);
    for (const p of a.values()) expect(p.every(Number.isFinite)).toBe(true);
  });

  it("keeps nodes apart even when the ecosystem is crowded", () => {
    const g = buildGraph(crowd());
    const v = visibleGraph(g, ALL);
    const started = performance.now();
    const pos = layoutGraph(v, { mode: "ecosystem", focus: null });
    expect(performance.now() - started).toBeLessThan(2000);
    let overlaps = 0;
    for (let i = 0; i < v.nodes.length; i++) for (let j = i + 1; j < v.nodes.length; j++) {
      const a = v.nodes[i], b = v.nodes[j];
      if (distance(pos.get(a.id)!, pos.get(b.id)!) < (nodeRadius(a) + nodeRadius(b)) * 0.9) overlaps++;
    }
    expect(v.nodes.length).toBeGreaterThan(240);
    expect(overlaps / v.nodes.length).toBeLessThan(0.02);
  });

  it("puts what belongs to one agent near it, and what all share among them", () => {
    const g = buildGraph(crowd());
    const v = visibleGraph(g, ALL);
    const pos = layoutGraph(v, { mode: "ecosystem", focus: null });
    for (let i = 1; i <= 20; i += 5) {
      const own = pos.get(`res:skill-${i - 1}-0`)!;
      const mine = distance(own, pos.get(`agent:${i}`)!);
      for (let j = 1; j <= 20; j++) if (j !== i) expect(mine).toBeLessThan(distance(own, pos.get(`agent:${j}`)!));
    }
    const { center, radius } = bounds([...pos.entries()].filter(([id]) => id.startsWith("agent:")).map(([, p]) => p));
    expect(distance(pos.get("res:shared-skill")!, center)).toBeLessThan(radius * 0.6);
  });

  it("centres the single agent and puts its parent above it", () => {
    const g = buildGraph(team());
    const v = visibleGraph(g, { ...ALL, mode: "agent", focus: 3 });
    const pos = layoutGraph(v, { mode: "agent", focus: 3 });
    expect(pos.get("agent:3")).toEqual([0, 0, 0]);
    expect(pos.get("agent:1")![1]).toBeGreaterThan(5);
  });

  it("frames an empty scene with a sensible sphere", () => {
    expect(bounds([])).toEqual({ center: [0, 0, 0], radius: 10, core: 10 });
    const spread: Vec3[] = [...Array.from({ length: 19 }, (_, i): Vec3 => [i % 3, 0, 0]), [100, 0, 0]];
    const { radius, core } = bounds(spread);
    expect(radius).toBeGreaterThan(90);
    expect(core).toBeLessThan(10);
  });
});
