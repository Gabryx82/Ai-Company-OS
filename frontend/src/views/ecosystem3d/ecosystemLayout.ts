// Where every node of the 3D view sits (ADR-037). Deterministic -- the same graph
// always lands in the same place, so an unchanged poll moves nothing -- and
// structured, so the map reads before it is explored:
//  - agents on a flattened sphere, sub-agents beside their parent, outwards;
//  - what belongs to one agent around it, each category in its own cap, on the
//    side facing away from the centre;
//  - what several agents share between them, models, providers and targets pulled
//    towards the core: the infrastructure is the centre of the map;
//  - a Hub cluster's members on a shell around the cluster.
// A short relaxation then pushes apart whatever still overlaps.
import { CATEGORIES, type Category, type EcoNode, type Mode, type VisibleGraph } from "./ecosystemModel";

export type Vec3 = [number, number, number];

const GOLDEN = Math.PI * (3 - Math.sqrt(5));
const INFRA = new Set<Category>(["model", "provider", "target"]);

/** The radius of a node's shape, as the scene draws it. */
export function nodeRadius(node: Pick<EcoNode, "category" | "size" | "isGroup">): number {
  if (node.category === "agent") return 1.15 * node.size;
  return (node.isGroup ? 0.62 : 0.46) * node.size;
}

const add = (a: Vec3, b: Vec3): Vec3 => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a: Vec3, b: Vec3): Vec3 => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const scale = (a: Vec3, k: number): Vec3 => [a[0] * k, a[1] * k, a[2] * k];
const length = (a: Vec3) => Math.hypot(a[0], a[1], a[2]);
const cross = (a: Vec3, b: Vec3): Vec3 => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
function normalize(a: Vec3): Vec3 | null {
  const l = length(a);
  return l < 1e-6 ? null : scale(a, 1 / l);
}

interface Frame { o: Vec3; t: Vec3; b: Vec3; hemisphere: boolean }

/** A frame whose third axis points away from the centre; the world frame, all around, when there is no "away". */
function frameOf(outward: Vec3 | null): Frame {
  if (!outward) return { o: [0, 0, 1], t: [1, 0, 0], b: [0, 1, 0], hemisphere: false };
  const up: Vec3 = Math.abs(outward[1]) < 0.95 ? [0, 1, 0] : [1, 0, 0];
  const t = normalize(cross(up, outward))!;
  return { o: outward, t, b: cross(outward, t), hemisphere: true };
}

function toWorld(f: Frame, d: Vec3): Vec3 {
  const z = f.hemisphere ? 0.5 + 0.9 * (d[2] + 1) / 2 : d[2];
  return normalize(add(add(scale(f.t, d[0]), scale(f.b, d[1])), scale(f.o, z))) ?? f.o;
}

/** n points spread evenly on a unit sphere. */
function fibonacci(i: number, n: number): Vec3 {
  const y = n === 1 ? 0 : 1 - (2 * (i + 0.5)) / n;
  const r = Math.sqrt(Math.max(0, 1 - y * y));
  const theta = GOLDEN * i;
  return [r * Math.cos(theta), y, r * Math.sin(theta)];
}

// One fixed direction per category: the same category sits on the same side of every agent.
const CATEGORY_DIR = new Map<Category, Vec3>(
  CATEGORIES.filter((c) => c.key !== "agent").map((c, i, all) => [c.key, fibonacci(i, all.length)]),
);

function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
  return (h >>> 0) / 4294967295;
}

export interface LayoutOptions { mode: Mode; focus: number | null }

export function layoutGraph(visible: VisibleGraph, options: LayoutOptions): Map<string, Vec3> {
  const pos = new Map<string, Vec3>();
  const index = new Map(visible.nodes.map((n, i) => [n.id, i]));
  const agents = visible.nodes.filter((n) => n.category === "agent");
  const parentOf = new Map<string, string>();
  for (const e of visible.edges) if (e.kind === "subagent") parentOf.set(e.target, e.source);
  const single = options.mode === "agent" && options.focus !== null;
  const focusId = single ? `agent:${options.focus}` : null;

  // --- agents ---
  if (single && focusId && index.has(focusId)) {
    pos.set(focusId, [0, 0, 0]);
    const parent = parentOf.get(focusId);
    if (parent) pos.set(parent, [0, 12, 0]);
    const children = agents.filter((a) => parentOf.get(a.id) === focusId);
    children.forEach((c, i) => {
      const angle = (2 * Math.PI * i) / children.length + 0.4;
      pos.set(c.id, [Math.cos(angle) * 12, -9, Math.sin(angle) * 12]);
    });
  } else {
    const roots = agents.filter((a) => !parentOf.has(a.id));
    const radius = roots.length <= 1 ? 0 : 7 + 4.2 * Math.sqrt(roots.length);
    roots.forEach((a, i) => {
      const p = fibonacci(i, roots.length);
      pos.set(a.id, [p[0] * radius, p[1] * radius * 0.62, p[2] * radius]);
    });
    // Sub-agents, generation after generation, beside their parent and further out.
    const placeChildren = (parent: string, depth: number) => {
      const children = agents.filter((a) => parentOf.get(a.id) === parent);
      const p = pos.get(parent)!;
      const f = frameOf(normalize(p));
      children.forEach((c, i) => {
        const phi = (2 * Math.PI * i) / Math.max(1, children.length) + 0.3 * depth;
        const spread = children.length === 1 ? 0.45 : 0.95;
        const dir = normalize(add(f.o, add(scale(f.t, Math.cos(phi) * spread), scale(f.b, Math.sin(phi) * spread)))) ?? f.o;
        pos.set(c.id, add(p, scale(dir, 6.5)));
        placeChildren(c.id, depth + 1);
      });
    };
    roots.forEach((r) => placeChildren(r.id, 1));
  }
  for (const a of agents) if (!pos.has(a.id)) pos.set(a.id, [0, 0, 0]);

  // --- everything else, owners first ---
  const placedOwners = (n: EcoNode) => (visible.owners.get(n.id) ?? []).filter((o) => (index.get(o) ?? Infinity) < index.get(n.id)!);
  const homeCount = new Map<string, number>();
  const sharedCount = new Map<string, number>();
  const others = visible.nodes.filter((n) => n.category !== "agent");
  const homes = new Map<string, string[]>();
  for (const n of others) {
    const owners = placedOwners(n);
    homes.set(n.id, owners);
    const key = owners.length === 1 ? `${owners[0]}|${n.category}` : owners.slice().sort().join("+");
    const counter = owners.length === 1 ? homeCount : sharedCount;
    counter.set(key, (counter.get(key) ?? 0) + 1);
  }
  const homeSeen = new Map<string, number>();
  const sharedSeen = new Map<string, number>();
  const outwardOf = (id: string): Vec3 | null => {
    if (id === focusId) return null;
    const p = pos.get(id) ?? [0, 0, 0];
    const node = visible.nodes[index.get(id)!];
    if (node && node.category !== "agent") {
      const up = homes.get(id)?.[0];
      if (up && pos.has(up)) return normalize(sub(p, pos.get(up)!)) ?? normalize(p);
    }
    return normalize(p);
  };

  for (const n of others) {
    const owners = homes.get(n.id)!;
    if (owners.length === 0) { pos.set(n.id, [0, 0, 0]); continue; }
    if (owners.length === 1) {
      const owner = owners[0];
      const ownerNode = visible.nodes[index.get(owner)!];
      const key = `${owner}|${n.category}`;
      const k = homeSeen.get(key) ?? 0;
      homeSeen.set(key, k + 1);
      const total = homeCount.get(key)!;
      const f = frameOf(outwardOf(owner));
      const origin = pos.get(owner)!;
      if (ownerNode.isGroup) {
        // A cluster's members: a shell around it, open towards the outside.
        const r = Math.max(2.4, 0.82 * Math.sqrt(total));
        const d = fibonacci(k, total);
        pos.set(n.id, add(origin, scale(toWorld(f, [d[0], d[2], d[1]]), r)));
        continue;
      }
      const agentOwner = ownerNode.category === "agent";
      const big = single && owner === focusId;
      const dir = toWorld(f, CATEGORY_DIR.get(n.category) ?? [0, 0, 1]);
      const cf = frameOf(dir);
      const reach = (agentOwner ? (big ? 9 : 3.8) : single ? 3.4 : 2.7) + 0.3 * Math.sqrt(total);
      const spacing = big ? 1.75 : 1.0;
      const rho = spacing * Math.sqrt(k);
      const angle = k * GOLDEN;
      const offset = add(scale(cf.t, rho * Math.cos(angle)), scale(cf.b, rho * Math.sin(angle)));
      pos.set(n.id, add(add(origin, scale(dir, reach - rho * rho * 0.03)), offset));
      continue;
    }
    // Shared: between its owners; the infrastructure nearer the core.
    let c: Vec3 = [0, 0, 0];
    for (const o of owners) c = add(c, pos.get(o)!);
    c = scale(c, (1 / owners.length) * (INFRA.has(n.category) ? 0.5 : 0.82));
    const key = owners.slice().sort().join("+");
    const j = sharedSeen.get(key) ?? 0;
    sharedSeen.set(key, j + 1);
    const f = frameOf(normalize(c) ?? normalize([hash(key) - 0.5, 0.3, hash(`${key}#`) - 0.5]));
    const rho = 1.1 * Math.sqrt(j + (sharedCount.get(key)! > 1 ? 0.5 : 0));
    const angle = j * GOLDEN + hash(key) * Math.PI * 2;
    pos.set(n.id, add(c, add(scale(f.t, rho * Math.cos(angle)), scale(f.b, rho * Math.sin(angle)))));
  }

  relax(visible.nodes, pos, new Set(agents.map((a) => a.id)));
  return pos;
}

/**
 * Pushes overlapping nodes apart -- a spatial hash keeps it linear -- while a
 * spring holds each one near where the structure put it. Agents do not move.
 */
function relax(nodes: EcoNode[], pos: Map<string, Vec3>, fixed: Set<string>, iterations = 36) {
  const n = nodes.length;
  if (n < 2) return;
  const p = new Float64Array(n * 3);
  const anchor = new Float64Array(n * 3);
  const radius = new Float64Array(n);
  const pinned = new Uint8Array(n);
  nodes.forEach((node, i) => {
    const v = pos.get(node.id)!;
    p.set(v, i * 3);
    anchor.set(v, i * 3);
    radius[i] = nodeRadius(node);
    pinned[i] = fixed.has(node.id) ? 1 : 0;
  });
  const cell = 2.6;
  const keyOf = (x: number, y: number, z: number) =>
    ((Math.floor(x / cell) + 4096) * 8192 + (Math.floor(y / cell) + 4096)) * 8192 + (Math.floor(z / cell) + 4096);
  for (let it = 0; it < iterations; it++) {
    const grid = new Map<number, number[]>();
    for (let i = 0; i < n; i++) {
      const key = keyOf(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
      (grid.get(key) ?? grid.set(key, []).get(key)!).push(i);
    }
    for (let i = 0; i < n; i++) {
      const cx = Math.floor(p[i * 3] / cell), cy = Math.floor(p[i * 3 + 1] / cell), cz = Math.floor(p[i * 3 + 2] / cell);
      for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) {
        const bucket = grid.get(((cx + dx + 4096) * 8192 + (cy + dy + 4096)) * 8192 + (cz + dz + 4096));
        if (!bucket) continue;
        for (const j of bucket) {
          if (j <= i || (pinned[i] && pinned[j])) continue;
          let ddx = p[j * 3] - p[i * 3], ddy = p[j * 3 + 1] - p[i * 3 + 1], ddz = p[j * 3 + 2] - p[i * 3 + 2];
          let d = Math.hypot(ddx, ddy, ddz);
          const min = radius[i] + radius[j] + 0.42;
          if (d >= min) continue;
          if (d < 1e-6) {
            // Two nodes on the same point: a direction of their own, the same at every run.
            const h = hash(nodes[i].id + nodes[j].id) * Math.PI * 2;
            ddx = Math.cos(h); ddy = 0.3; ddz = Math.sin(h); d = Math.hypot(ddx, ddy, ddz);
          }
          const push = (min - Math.min(d, min)) / d;
          const wi = pinned[i] ? 0 : pinned[j] ? 1 : 0.5;
          const wj = pinned[j] ? 0 : pinned[i] ? 1 : 0.5;
          p[i * 3] -= ddx * push * wi; p[i * 3 + 1] -= ddy * push * wi; p[i * 3 + 2] -= ddz * push * wi;
          p[j * 3] += ddx * push * wj; p[j * 3 + 1] += ddy * push * wj; p[j * 3 + 2] += ddz * push * wj;
        }
      }
    }
    for (let i = 0; i < n * 3; i++) if (!pinned[Math.floor(i / 3)]) p[i] += (anchor[i] - p[i]) * 0.06;
  }
  nodes.forEach((node, i) => pos.set(node.id, [p[i * 3], p[i * 3 + 1], p[i * 3 + 2]]));
}

/**
 * The sphere that holds every position, and the smaller one that holds nine in
 * ten of them: the camera frames the second, so a few far nodes do not shrink the map.
 */
export function bounds(positions: Iterable<Vec3>): { center: Vec3; radius: number; core: number } {
  const list = [...positions];
  if (list.length === 0) return { center: [0, 0, 0], radius: 10, core: 10 };
  let c: Vec3 = [0, 0, 0];
  for (const p of list) c = add(c, p);
  c = scale(c, 1 / list.length);
  const distances = list.map((p) => length(sub(p, c))).sort((x, y) => x - y);
  const radius = Math.max(distances[distances.length - 1] + 2, 6);
  const core = Math.max(distances[Math.min(distances.length - 1, Math.floor(distances.length * 0.9))] + 1.5, 6);
  return { center: c, radius, core };
}

/** Where a node first appears: on what it hangs from, so the map grows out of its owners. */
export function spawnPoint(visible: VisibleGraph, id: string, current: (id: string) => Vec3 | undefined): Vec3 | null {
  for (const owner of visible.owners.get(id) ?? []) {
    const p = current(owner);
    if (p) return p;
  }
  return null;
}
