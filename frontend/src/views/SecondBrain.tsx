import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import {
  forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY,
  type SimulationLinkDatum, type SimulationNodeDatum,
} from "d3-force";
import { useApi } from "../context";
import { ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";
import { GraphKeys, GraphNav, GraphTrail } from "../components/GraphNav";
import { twoFingers, wheelView, type Point } from "../components/graphGestures";

type GraphNode = { id: string; type: string; label: string; status: string | null; meta: Record<string, unknown> };
type GraphEdge = { source: string; target: string; kind: string };
type SimNode = SimulationNodeDatum & GraphNode & { degree: number };
type SimLink = SimulationLinkDatum<SimNode> & { kind: string };

export const NODE_STYLE: Record<string, { color: string; label: string }> = {
  project: { color: "#b7a8ec", label: "Progetto" },
  phase: { color: "#97b6ec", label: "Fase" },
  task: { color: "#a0dcb2", label: "Task" },
  agent: { color: "#f2ae86", label: "Agente" },
  subagent: { color: "#f6cdb0", label: "Sottoagente" },
  software: { color: "#8ad1e2", label: "Software" },
  model: { color: "#e6cf8c", label: "Modello" },
  provider: { color: "#aab6c8", label: "Provider" },
  document: { color: "#ecdfa4", label: "Documento" },
  skill: { color: "#eba3c4", label: "Skill" },
  knowledge: { color: "#8dd3c5", label: "Knowledge" },
  mcp: { color: "#a4adf0", label: "MCP" },
  tool: { color: "#b9da94", label: "Tool" },
  framework: { color: "#91c7ea", label: "Framework" },
  template_provider: { color: "#efa8b0", label: "Template" },
  daily: { color: "#94d6c0", label: "Daily" },
};
const FALLBACK = "#cdd5e1";

const EDGE_LABEL: Record<string, string> = {
  contains: "contiene", "assigned-to": "assegnata a", "subagent-of": "sottoagente di", "uses-model": "usa il modello",
  "served-by": "servito da", "equipped-with": "equipaggiato con", adopts: "adotta", "uses-software": "usa",
  "handed-to": "consegnata a", "refers-to": "riferita a", "has-document": "documento",
};

const DEFAULT_HIDDEN = new Set(["provider", "tool", "template_provider", "framework"]);
const MIN_ZOOM = 0.08;
const MAX_ZOOM = 6;

/** The keys the graph answers to while it has the focus. */
const KEYS: [string, string][] = [
  ["← → ↑ ↓", "Sposta la vista"],
  ["Due dita ← →", "Sposta a destra e a sinistra (touchpad; col mouse Maiusc + rotella)"],
  ["Due dita sullo schermo", "Trascina la vista e ingrandisci"],
  ["+  −", "Avvicina, allontana"],
  ["F  ·  Home", "Inquadra tutto"],
  ["Invio", "Apri l'elemento scelto"],
  ["Backspace", "Torna all'elemento precedente"],
  ["Esc", "Deseleziona"],
  ["/", "Cerca un nodo"],
  ["V", "Solo i vicini dell'elemento scelto"],
];

type View = { x: number; y: number; k: number };

/**
 * The Second Brain (directive §17): the ecosystem as a living graph, in the
 * spirit of Obsidian's graph view -- forces keep it arranged, the operator
 * drags, zooms and follows the links, and every node opens what it is.
 * Drawn on a 2D canvas: translucent pastel spheres pre-rendered once per colour,
 * hairline edges tinted by what they join, transitions eased frame by frame.
 */
export function SecondBrain({ navigate }: { navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const canvas = useRef<HTMLCanvasElement>(null);
  const minimap = useRef<HTMLCanvasElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  const tooltip = useRef<HTMLDivElement>(null);
  const searchInput = useRef<HTMLInputElement>(null);
  const [data, setData] = useState<{ nodes: GraphNode[]; edges: GraphEdge[] } | null>(null);
  const [hidden, setHidden] = useState<Set<string>>(DEFAULT_HIDDEN);
  const [query, setQuery] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [local, setLocal] = useState(false);
  const [showKeys, setShowKeys] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [hoveredNode, setHoveredNode] = useState<GraphNode | null>(null);
  const hovered = useRef<SimNode | null>(null);
  const view = useRef<View>({ x: 0, y: 0, k: 1 });
  /** Where the view glides to; null when the operator is moving it by hand. */
  const goal = useRef<View | null>(null);
  /** Where every node was last: a filter or a refresh does not throw the map in the air. */
  const placed = useRef(new Map<string, { x: number; y: number }>());
  const fitted = useRef(false);
  // Read by the drawing loop; kept out of the simulation's dependencies, so that
  // selecting or searching never reheats the layout.
  const selectedRef = useRef<string | null>(null);
  const needleRef = useRef("");
  const openRef = useRef<(node: GraphNode) => void>(() => {});

  useEffect(() => {
    api.graph().then(setData).catch(setProblem);
  }, [api]);

  // The whole graph's neighbours, for the local view and the tooltip.
  const adjacency = useMemo(() => {
    const map = new Map<string, Set<string>>();
    for (const e of data?.edges ?? []) {
      (map.get(e.source) ?? map.set(e.source, new Set()).get(e.source)!).add(e.target);
      (map.get(e.target) ?? map.set(e.target, new Set()).get(e.target)!).add(e.source);
    }
    return map;
  }, [data]);

  const localRoot = local ? selectedId : null;
  /** Bumped to lay the graph out again from scratch. */
  const [seed, setSeed] = useState(0);
  const current = useRef<{ byId: Map<string, SimNode> } | null>(null);
  const graph = useMemo(() => {
    if (!data) return null;
    let keep = (n: GraphNode) => !hidden.has(n.type);
    if (localRoot) {
      // The local graph: the chosen node, its neighbours and theirs.
      const near = new Set([localRoot]);
      for (const a of adjacency.get(localRoot) ?? []) {
        near.add(a);
        for (const b of adjacency.get(a) ?? []) near.add(b);
      }
      const base = keep;
      keep = (n) => n.id === localRoot || (near.has(n.id) && base(n));
    }
    // Where each node is now on screen, or was when last shown.
    const live = current.current?.byId;
    const nodes: SimNode[] = data.nodes.filter(keep).map((n) => {
      const now = live?.get(n.id);
      const was = now && now.x !== undefined ? { x: now.x, y: now.y! } : placed.current.get(n.id);
      return { ...n, degree: 0, ...(was ? { x: was.x, y: was.y } : {}) };
    });
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const links: SimLink[] = data.edges.filter((e) => byId.has(e.source) && byId.has(e.target))
      .map((e) => ({ source: e.source, target: e.target, kind: e.kind }));
    links.forEach((l) => { byId.get(l.source as string)!.degree++; byId.get(l.target as string)!.degree++; });
    return { nodes, links, byId };
  }, [data, hidden, localRoot, adjacency, seed]);

  const needle = query.trim().toLowerCase();
  useEffect(() => { selectedRef.current = selectedId; }, [selectedId]);
  useEffect(() => { needleRef.current = needle; }, [needle]);

  /** The view that shows every node, for a canvas of this size. */
  const fitView = useCallback((): View | null => {
    const el = canvas.current;
    const nodes = (graph?.nodes ?? []).filter((n) => n.x !== undefined);
    if (!el || nodes.length === 0) return null;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const n of nodes) {
      const r = radius(n);
      x0 = Math.min(x0, n.x! - r); y0 = Math.min(y0, n.y! - r); x1 = Math.max(x1, n.x! + r); y1 = Math.max(y1, n.y! + r);
    }
    const w = el.clientWidth, h = el.clientHeight;
    const k = clamp(Math.min((w - 90) / Math.max(1, x1 - x0), (h - 120) / Math.max(1, y1 - y0)), MIN_ZOOM, 2.2);
    return { k, x: -((x0 + x1) / 2) * k, y: -((y0 + y1) / 2) * k };
  }, [graph]);

  const fit = useCallback(() => { const v = fitView(); if (v) goal.current = v; }, [fitView]);

  /** Fly to a node, close enough to read it and its neighbours. */
  const flyTo = useCallback((id: string) => {
    const n = graph?.byId.get(id);
    if (!n || n.x === undefined || n.y === undefined) return;
    const k = Math.max(view.current.k, 1.35);
    goal.current = { k, x: -n.x * k, y: -n.y * k };
  }, [graph]);

  const zoomBy = useCallback((factor: number) => {
    const from = goal.current ?? view.current;
    const k = clamp(from.k * factor, MIN_ZOOM, MAX_ZOOM);
    const s = k / from.k;
    goal.current = { k, x: from.x * s, y: from.y * s };
  }, []);

  const panBy = useCallback((dx: number, dy: number) => {
    const from = goal.current ?? view.current;
    goal.current = { ...from, x: from.x + dx, y: from.y + dy };
  }, []);

  useEffect(() => {
    const el = canvas.current;
    if (!graph || !el) return;
    const context = el.getContext("2d");
    if (!context) return;
    const ctx: CanvasRenderingContext2D = context;
    const neighbours = new Map<string, Set<string>>();
    graph.links.forEach((l) => {
      const s = typeof l.source === "string" ? l.source : (l.source as SimNode).id;
      const t = typeof l.target === "string" ? l.target : (l.target as SimNode).id;
      if (!neighbours.has(s)) neighbours.set(s, new Set());
      if (!neighbours.has(t)) neighbours.set(t, new Set());
      neighbours.get(s)!.add(t);
      neighbours.get(t)!.add(s);
    });

    // A graph that was already on screen settles from where it was; a new one is framed once calm.
    const warm = graph.nodes.some((n) => n.x !== undefined);
    const simulation = forceSimulation<SimNode>(graph.nodes)
      .force("link", forceLink<SimNode, SimLink>(graph.links).id((n) => n.id)
        .distance((l) => (l.kind === "contains" ? 46 : l.kind === "served-by" ? 60 : 80)).strength(0.5))
      .force("charge", forceManyBody().strength(-130).distanceMax(520))
      .force("x", forceX(0).strength(0.035))
      .force("y", forceY(0).strength(0.035))
      .force("collide", forceCollide<SimNode>().radius((n) => radius(n) + 5));
    if (warm) simulation.alpha(0.3);
    current.current = graph;

    const sprites = new SpriteCache();
    const fade = new Map<string, number>();
    let frame = 0;
    let tick = 0;
    let last = performance.now();
    const resize = () => {
      const ratio = window.devicePixelRatio || 1;
      el.width = Math.max(1, Math.round(el.clientWidth * ratio));
      el.height = Math.max(1, Math.round(el.clientHeight * ratio));
    };
    resize();
    const observer = typeof ResizeObserver === "function" ? new ResizeObserver(resize) : null;
    observer?.observe(el);

    const draw = (now: number) => {
      tick++;
      const dt = Math.min(0.1, Math.max(0, (now - last) / 1000));
      last = now;
      const ease = 1 - Math.exp(-dt * 9);
      const ratio = window.devicePixelRatio || 1;
      const w = el.clientWidth;
      const h = el.clientHeight;

      // The view glides to its goal; the first framing comes once the layout has calmed.
      if (!fitted.current && (simulation.alpha() < 0.12 || (warm && tick > 2))) {
        fitted.current = true;
        const v = fitView();
        if (v) goal.current = v;
      }
      if (goal.current) {
        const g = goal.current, v = view.current;
        const kz = 1 - Math.exp(-dt * 7);
        // Zoom in log space, so a big jump feels even.
        const k = Math.exp(Math.log(v.k) + (Math.log(g.k) - Math.log(v.k)) * kz);
        v.x += (g.x - v.x) * kz;
        v.y += (g.y - v.y) * kz;
        v.k = k;
        if (Math.abs(v.k - g.k) < 1e-3 && Math.abs(v.x - g.x) < 0.3 && Math.abs(v.y - g.y) < 0.3) {
          view.current = { ...g };
          goal.current = null;
        }
      }
      const { x, y, k } = view.current;

      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.clearRect(0, 0, w, h);
      drawGrid(ctx, w, h, view.current);
      ctx.translate(w / 2 + x, h / 2 + y);
      ctx.scale(k, k);

      const selectedNow = selectedRef.current ? graph.byId.get(selectedRef.current) ?? null : null;
      const needleNow = needleRef.current;
      const focus = hovered.current ?? selectedNow;
      const near = focus ? neighbours.get(focus.id) ?? new Set<string>() : null;
      const searching = needleNow.length > 0;
      const matchOf = (n: SimNode) => searching && n.label.toLowerCase().includes(needleNow);

      // How present each node is: eased, so focus and search fade instead of snapping.
      for (const n of graph.nodes) {
        const want = focus ? (n === focus || near!.has(n.id) ? 1 : 0.14) : searching ? (matchOf(n) ? 1 : 0.2) : 1;
        const was = fade.get(n.id) ?? want;
        fade.set(n.id, was + (want - was) * ease);
      }

      // Edges: hairlines tinted by what they join; the focused node's edges brighter, with a signal.
      ctx.lineCap = "round";
      for (const l of graph.links) {
        const s = l.source as SimNode;
        const t = l.target as SimNode;
        const lit = focus !== null && (s.id === focus.id || t.id === focus.id);
        const a = Math.min(fade.get(s.id) ?? 1, fade.get(t.id) ?? 1);
        const color = NODE_STYLE[s.type]?.color ?? FALLBACK;
        ctx.strokeStyle = withAlpha(color, lit ? 0.7 : 0.2 * a + 0.02);
        ctx.lineWidth = (lit ? 1.6 : 0.9) / Math.sqrt(Math.max(k, 0.25));
        ctx.beginPath();
        ctx.moveTo(s.x!, s.y!);
        ctx.lineTo(t.x!, t.y!);
        ctx.stroke();
        if (lit) {
          const p = ((tick + (s.index ?? 0) * 7) % 110) / 110;
          ctx.fillStyle = withAlpha("#eef3ff", Math.sin(p * Math.PI) * 0.9);
          ctx.beginPath();
          ctx.arc(s.x! + (t.x! - s.x!) * p, s.y! + (t.y! - s.y!) * p, 2.2 / Math.sqrt(k), 0, Math.PI * 2);
          ctx.fill();
        }
      }

      // Nodes: a soft halo and a glassy sphere, both from a sprite made once per colour.
      for (const n of graph.nodes) {
        const style = NODE_STYLE[n.type] ?? { color: FALLBACK };
        const a = fade.get(n.id) ?? 1;
        const match = matchOf(n);
        const r = radius(n) * (match ? 1.3 : 1) * (n === focus ? 1.12 : 1);
        const reach = r * SPRITE_SPAN;
        ctx.globalAlpha = 0.3 + 0.7 * a;
        ctx.drawImage(sprites.get(style.color), n.x! - reach, n.y! - reach, reach * 2, reach * 2);
        ctx.globalAlpha = 1;
        if (n === selectedNow || match) {
          ctx.strokeStyle = n === selectedNow ? "rgba(255, 255, 255, 0.85)" : withAlpha(style.color, 0.9);
          ctx.lineWidth = 1.6 / k;
          ctx.beginPath();
          ctx.arc(n.x!, n.y!, r + 3.5 / k, 0, Math.PI * 2);
          ctx.stroke();
        }
        if (n === selectedNow) {
          const pulse = (tick % 90) / 90;
          ctx.strokeStyle = withAlpha(style.color, 0.5 * (1 - pulse));
          ctx.lineWidth = 1.2 / k;
          ctx.beginPath();
          ctx.arc(n.x!, n.y!, r + (4 + pulse * 10) / k, 0, Math.PI * 2);
          ctx.stroke();
        }
      }

      // Labels: the important ones always, the rest as the zoom brings them close; text on a soft halo.
      const fontPx = 11.5 * clamp(Math.pow(k, 0.3), 0.85, 1.35);
      ctx.font = `500 ${fontPx / k}px "Geist Variable", Inter, "Segoe UI", sans-serif`;
      ctx.textAlign = "center";
      ctx.textBaseline = "top";
      ctx.lineJoin = "round";
      const byZoom = clamp((k - 0.95) / 0.5, 0, 1);
      for (const n of graph.nodes) {
        const a = fade.get(n.id) ?? 1;
        const match = matchOf(n);
        const important = n.type === "project" || n.type === "agent" || n === focus || (near !== null && near.has(n.id)) || match;
        const alpha = (important ? 1 : byZoom) * (a < 0.5 ? a * 0.6 : a);
        if (alpha < 0.04) continue;
        const r = radius(n) * (match ? 1.3 : 1);
        const text = n.label.length > 42 ? `${n.label.slice(0, 40)}…` : n.label;
        const ty = n.y! + r + 5 / k;
        ctx.globalAlpha = alpha;
        ctx.lineWidth = 3.2 / k;
        ctx.strokeStyle = "rgba(14, 20, 33, 0.78)";
        ctx.strokeText(text, n.x!, ty);
        ctx.fillStyle = n === focus ? "#ffffff" : "#dfe6f1";
        ctx.fillText(text, n.x!, ty);
      }
      ctx.globalAlpha = 1;

      if (tick % 4 === 0) drawMinimap(minimap.current, graph.nodes, view.current, w, h);
      frame = requestAnimationFrame(draw);
    };
    frame = requestAnimationFrame(draw);

    // --- interaction: pan, zoom towards the pointer, drag, hover, select, open ---
    const toWorld = (event: { clientX: number; clientY: number }) => {
      const rect = el.getBoundingClientRect();
      const { x, y, k } = view.current;
      return { x: (event.clientX - rect.left - rect.width / 2 - x) / k, y: (event.clientY - rect.top - rect.height / 2 - y) / k };
    };
    const find = (event: { clientX: number; clientY: number }) => {
      const p = toWorld(event);
      let best: SimNode | null = null;
      let bestDistance = Infinity;
      for (const n of graph.nodes) {
        const d = Math.hypot(n.x! - p.x, n.y! - p.y);
        if (d < radius(n) + 7 / view.current.k && d < bestDistance) { best = n; bestDistance = d; }
      }
      return best;
    };
    const placeTooltip = (event: PointerEvent) => {
      const box = el.getBoundingClientRect();
      if (tooltip.current) tooltip.current.style.transform = `translate(${event.clientX - box.left + 14}px, ${event.clientY - box.top + 14}px)`;
    };
    let dragging: SimNode | null = null;
    let panning: { x: number; y: number } | null = null;
    let moved = false;
    let downAt = { x: 0, y: 0 };
    // Fingers on a touch screen, from the canvas centre: with two, they carry and pinch the map.
    const fingers = new Map<number, Point>();
    let pair: [Point, Point] | null = null;
    const fromCentre = (event: PointerEvent): Point => {
      const rect = el.getBoundingClientRect();
      return { x: event.clientX - rect.left - rect.width / 2, y: event.clientY - rect.top - rect.height / 2 };
    };
    const firstTwo = (): [Point, Point] => { const [a, b] = [...fingers.values()]; return [a, b]; };
    const down = (event: PointerEvent) => {
      if (event.pointerType === "touch") {
        fingers.set(event.pointerId, fromCentre(event));
        if (fingers.size === 2) {
          // The second finger turns a drag or a pan into a two-finger gesture.
          el.setPointerCapture?.(event.pointerId);
          if (dragging) { dragging.fx = null; dragging.fy = null; simulation.alphaTarget(0); }
          dragging = null;
          panning = null;
          moved = true;
          goal.current = null;
          pair = firstTwo();
          return;
        }
        if (fingers.size > 2) return;
      }
      if (event.button !== 0 && event.button !== 1) return;
      moved = false;
      downAt = { x: event.clientX, y: event.clientY };
      el.setPointerCapture?.(event.pointerId);
      goal.current = null;
      const hit = event.button === 0 ? find(event) : null;
      if (hit) {
        dragging = hit;
        simulation.alphaTarget(0.25).restart();
      } else {
        panning = { x: event.clientX, y: event.clientY };
        el.style.cursor = "grabbing";
      }
    };
    const move = (event: PointerEvent) => {
      if (fingers.has(event.pointerId)) fingers.set(event.pointerId, fromCentre(event));
      if (pair && fingers.size >= 2) {
        const now = firstTwo();
        view.current = twoFingers(view.current, pair, now, MIN_ZOOM, MAX_ZOOM);
        pair = now;
        return;
      }
      const far = Math.hypot(event.clientX - downAt.x, event.clientY - downAt.y) > 3;
      if (dragging) {
        if (!far && !moved) return;
        const p = toWorld(event);
        dragging.fx = p.x;
        dragging.fy = p.y;
        moved = true;
      } else if (panning) {
        view.current.x += event.clientX - panning.x;
        view.current.y += event.clientY - panning.y;
        panning = { x: event.clientX, y: event.clientY };
        if (far) moved = true;
      } else {
        const hit = find(event);
        if (hit !== hovered.current) {
          hovered.current = hit;
          setHoveredNode(hit);
        }
        if (hit) placeTooltip(event);
        el.style.cursor = hit ? "pointer" : "grab";
      }
    };
    const up = (event: PointerEvent) => {
      if (el.hasPointerCapture?.(event.pointerId)) el.releasePointerCapture(event.pointerId);
      fingers.delete(event.pointerId);
      if (pair) {
        // A two-finger gesture never selects; the finger left behind does nothing until lifted.
        if (fingers.size < 2) pair = null;
        dragging = null;
        panning = null;
        return;
      }
      if (dragging) {
        if (!moved) setSelectedId(dragging.id);
        dragging.fx = null;
        dragging.fy = null;
        simulation.alphaTarget(0);
      } else if (panning && !moved) {
        setSelectedId(find(event)?.id ?? null);
      }
      dragging = null;
      panning = null;
      el.style.cursor = hovered.current ? "pointer" : "grab";
    };
    const leave = () => {
      if (dragging || panning) return;
      hovered.current = null;
      setHoveredNode(null);
    };
    const wheel = (event: WheelEvent) => {
      // Two fingers sideways on a touchpad move along x; up and down (and a pinch) zoom towards the pointer.
      // Never left to the browser: a sideways swipe would otherwise go back a page.
      event.preventDefault();
      goal.current = null;
      const rect = el.getBoundingClientRect();
      view.current = wheelView(view.current, event, event.clientX - rect.left - rect.width / 2,
        event.clientY - rect.top - rect.height / 2, MIN_ZOOM, MAX_ZOOM);
    };
    const dblclick = (event: MouseEvent) => {
      const hit = find(event);
      if (hit) openRef.current(hit);
      else {
        const v = fitView();
        if (v) goal.current = v;
      }
    };
    el.addEventListener("pointerdown", down);
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
    el.addEventListener("pointerleave", leave);
    el.addEventListener("wheel", wheel, { passive: false });
    el.addEventListener("dblclick", dblclick);

    return () => {
      cancelAnimationFrame(frame);
      simulation.stop();
      for (const n of graph.nodes) if (n.x !== undefined && n.y !== undefined) placed.current.set(n.id, { x: n.x, y: n.y });
      observer?.disconnect();
      el.removeEventListener("pointerdown", down);
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
      el.removeEventListener("pointerleave", leave);
      el.removeEventListener("wheel", wheel);
      el.removeEventListener("dblclick", dblclick);
    };
  }, [graph, fitView]);

  // The minimap: a click or a drag there moves the view.
  useEffect(() => {
    const el = minimap.current;
    if (!el || !graph) return;
    let dragging = false;
    const go = (event: PointerEvent) => {
      const map = frames.get(el);
      if (!map) return;
      const rect = el.getBoundingClientRect();
      const wx = map.x0 + (event.clientX - rect.left - map.ox) / map.s;
      const wy = map.y0 + (event.clientY - rect.top - map.oy) / map.s;
      goal.current = null;
      view.current = { ...view.current, x: -wx * view.current.k, y: -wy * view.current.k };
    };
    const down = (event: PointerEvent) => { dragging = true; el.setPointerCapture?.(event.pointerId); go(event); };
    const move = (event: PointerEvent) => { if (dragging) go(event); };
    const up = (event: PointerEvent) => { dragging = false; if (el.hasPointerCapture?.(event.pointerId)) el.releasePointerCapture(event.pointerId); };
    el.addEventListener("pointerdown", down);
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    return () => { el.removeEventListener("pointerdown", down); el.removeEventListener("pointermove", move); el.removeEventListener("pointerup", up); };
  }, [graph]);

  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    data?.nodes.forEach((n) => { c[n.type] = (c[n.type] ?? 0) + 1; });
    return c;
  }, [data]);

  const toggle = (type: string) => {
    const next = new Set(hidden);
    if (next.has(type)) next.delete(type); else next.add(type);
    setHidden(next);
  };

  const byId = useMemo(() => new Map((data?.nodes ?? []).map((n) => [n.id, n])), [data]);
  const selected = selectedId ? byId.get(selectedId) ?? null : null;
  const links = useMemo(() => (selected && data ? data.edges.filter((e) => e.source === selected.id || e.target === selected.id) : []),
    [selected, data]);

  // --- the trail of chosen nodes; Backspace and the arrow go back along it ---
  const [trail, setTrail] = useState<string[]>([]);
  const goingBack = useRef(false);
  useEffect(() => {
    if (!selectedId) return;
    if (goingBack.current) { goingBack.current = false; return; }
    setTrail((t) => (t[t.length - 1] === selectedId ? t : [...t.filter((id) => id !== selectedId), selectedId].slice(-30)));
  }, [selectedId]);
  const known = useMemo(() => trail.filter((id) => byId.has(id)), [trail, byId]);
  const canBack = known.length >= 2 || (known.length === 1 && selectedId !== known[0]);

  // A node chosen before it is in the graph (a hidden type, outside the local view) is flown to once it is.
  const flyPending = useRef<string | null>(null);
  useEffect(() => {
    const id = flyPending.current;
    if (!id) return;
    let frames = 0, handle = 0;
    const wait = () => {
      const n = graph?.byId.get(id);
      if (n && n.x !== undefined) { flyPending.current = null; flyTo(id); return; }
      if (++frames < 30) handle = requestAnimationFrame(wait);
    };
    handle = requestAnimationFrame(wait);
    return () => cancelAnimationFrame(handle);
  }, [graph, flyTo, selectedId]);

  /** Choose a node and fly there; a hidden type is shown first, a local view moves to it. */
  const goTo = useCallback((id: string) => {
    const node = byId.get(id);
    if (!node) return;
    if (hidden.has(node.type)) setHidden((h) => { const next = new Set(h); next.delete(node.type); return next; });
    setSelectedId(id);
    setSearchOpen(false);
    flyPending.current = id;
    flyTo(id);
  }, [byId, hidden, flyTo]);

  const back = () => {
    if (!canBack) return;
    const top = known[known.length - 1];
    const rest = selectedId === top ? known.slice(0, -1) : known;
    setTrail(rest);
    goingBack.current = true;
    goTo(rest[rest.length - 1]);
  };

  function open(node: GraphNode) {
    const [kind, ...rest] = node.id.split(":");
    const key = rest.join(":");
    if (kind === "project") navigate("projects", key);
    else if (kind === "software") navigate("software", key);
    else if (kind === "task" || kind === "phase") navigate("tasks");
    else if (kind === "agent") navigate("agents");
    else if (kind === "model" || kind === "provider") navigate("models");
    else if (kind === "resource") navigate("knowledge");
    else if (kind === "daily") navigate("daily");
    else if (kind === "document") navigate("projects", key.split(":")[0]);
  }
  openRef.current = open;

  const hits = useMemo(() => {
    if (!data || needle.length < 2) return [];
    return data.nodes.filter((n) => n.label.toLowerCase().includes(needle))
      .sort((a, b) => Number(!a.label.toLowerCase().startsWith(needle)) - Number(!b.label.toLowerCase().startsWith(needle))
        || a.label.length - b.label.length)
      .slice(0, 40);
  }, [data, needle]);
  const [hit, setHit] = useState(0);
  useEffect(() => setHit(0), [needle]);

  const onSearchKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown") { event.preventDefault(); setHit((i) => Math.min(i + 1, Math.min(hits.length, 12) - 1)); }
    if (event.key === "ArrowUp") { event.preventDefault(); setHit((i) => Math.max(i - 1, 0)); }
    if (event.key === "Enter" && hits[hit]) goTo(hits[hit].id);
    if (event.key === "Escape") { setQuery(""); setSearchOpen(false); stage.current?.focus(); }
  };

  const onStageKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if ((event.target as HTMLElement).closest("button, input, select, textarea, a")) return;
    const done = () => { event.preventDefault(); event.stopPropagation(); };
    const step = 90;
    switch (event.key) {
      case "ArrowLeft": done(); panBy(step, 0); return;
      case "ArrowRight": done(); panBy(-step, 0); return;
      case "ArrowUp": done(); panBy(0, step); return;
      case "ArrowDown": done(); panBy(0, -step); return;
      case "+": case "=": done(); zoomBy(1.3); return;
      case "-": case "_": done(); zoomBy(1 / 1.3); return;
      case "f": case "F": case "Home": case "0": done(); fit(); return;
      case "v": case "V": done(); if (selectedId) setLocal((on) => !on); return;
      case "Enter": if (selected) { done(); open(selected); } return;
      case "Backspace": done(); back(); return;
      case "Escape": done(); if (showKeys) setShowKeys(false); else setSelectedId(null); return;
      case "/": done(); searchInput.current?.focus(); return;
      case "?": done(); setShowKeys((on) => !on); return;
    }
  };

  // The local view follows the chosen node, and frames itself when it changes; with none it has nothing to show.
  useEffect(() => { if (!selectedId) setLocal(false); }, [selectedId]);
  useEffect(() => { fitted.current = false; }, [localRoot]);

  const grouped = useMemo(() => {
    const map = new Map<string, { other: GraphNode; out: boolean }[]>();
    for (const e of links) {
      const other = byId.get(e.source === selected?.id ? e.target : e.source);
      if (!other) continue;
      const label = EDGE_LABEL[e.kind] ?? e.kind;
      (map.get(label) ?? map.set(label, []).get(label)!).push({ other, out: e.source === selected?.id });
    }
    return [...map.entries()];
  }, [links, selected, byId]);

  const earlier = known.filter((id) => id !== selectedId).slice(-3);

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Second Brain</h1><p>La mappa viva del tuo ecosistema: trascina, ingrandisci e segui i collegamenti. Fai clic su un nodo per vederne i dettagli, doppio clic per aprirlo.</p></div>
        <div className="search brain-search">
          <Icon name="search" size={15} />
          <input ref={searchInput} aria-label="Cerca un nodo" placeholder="Cerca un nodo…" value={query}
                 onChange={(e) => { setQuery(e.target.value); setSearchOpen(true); }} onFocus={() => setSearchOpen(true)}
                 onBlur={() => window.setTimeout(() => setSearchOpen(false), 150)} onKeyDown={onSearchKey} />
          {query && <button className="btn btn-small" aria-label="Svuota la ricerca" onClick={() => { setQuery(""); setSearchOpen(false); }}>
            <Icon name="close" size={12} /></button>}
          {searchOpen && needle.length >= 2 && (
            <div className="search-results" role="listbox" aria-label="Risultati">
              {hits.length === 0 && <div className="muted" style={{ padding: "8px 12px" }}>Nessun nodo trovato.</div>}
              {hits.slice(0, 12).map((n, i) => (
                <button key={n.id} role="option" aria-selected={i === hit} className={i === hit ? "is-active" : undefined}
                        onMouseDown={(e) => e.preventDefault()} onClick={() => goTo(n.id)}>
                  <span className="dot" style={{ background: NODE_STYLE[n.type]?.color ?? FALLBACK }} />
                  <span className="eco-link-label">{n.label}</span>
                  <small className="muted">{NODE_STYLE[n.type]?.label ?? n.type}{hidden.has(n.type) ? " · nascosto" : ""}</small>
                </button>
              ))}
              {hits.length > 12 && <div className="muted" style={{ padding: "6px 12px", fontSize: 12 }}>
                Altri {hits.length - 12} risultati: restringi la ricerca.</div>}
            </div>
          )}
        </div>
      </div>
      <ProblemNote problem={problem} />
      <div className="split brain-split">
        <div ref={stage} className="card brain-stage graph-stage-focus" role="region" tabIndex={0} onKeyDown={onStageKey}
             aria-label={`Grafo: ${graph?.nodes.length ?? 0} nodi e ${graph?.links.length ?? 0} collegamenti`}
             aria-keyshortcuts="ArrowLeft ArrowRight ArrowUp ArrowDown + - F V Enter Backspace Escape /">
          <canvas ref={canvas} className="brain-canvas" />
          {!data && !problem && <div className="brain-loading">Carico il grafo…</div>}
          <GraphTrail canBack={canBack} onBack={back} steps={[
            { key: "all", label: "Tutto il grafo", current: !selected,
              onClick: () => { setLocal(false); setSelectedId(null); fit(); } },
            ...earlier.map((id) => {
              const n = byId.get(id)!;
              return { key: id, label: n.label, color: NODE_STYLE[n.type]?.color, onClick: () => goTo(id) };
            }),
            ...(selected ? [{ key: `sel:${selected.id}`, label: selected.label, color: NODE_STYLE[selected.type]?.color, current: true,
              onClick: () => flyTo(selected.id) }] : []),
          ]} />
          <div className="brain-stats">
            {graph?.nodes.length ?? 0} nodi · {graph?.links.length ?? 0} collegamenti{localRoot ? " · solo i vicini" : ""}
          </div>
          <div ref={tooltip} className="eco-tooltip" hidden={!hoveredNode} role="tooltip">
            {hoveredNode && (
              <>
                <span className="eco-dot" style={{ background: NODE_STYLE[hoveredNode.type]?.color ?? FALLBACK }} />
                <strong>{hoveredNode.label}</strong>
                <small>{NODE_STYLE[hoveredNode.type]?.label ?? hoveredNode.type} · {adjacency.get(hoveredNode.id)?.size ?? 0} collegamenti</small>
              </>
            )}
          </div>
          <canvas ref={minimap} className="brain-minimap" role="img" aria-label="Minimappa: fai clic o trascina per spostare la vista" />
          <GraphNav label="Navigazione del grafo" groups={[
            [
              { glyph: "plus", label: "Avvicina (+)", onClick: () => zoomBy(1.4) },
              { glyph: "minus", label: "Allontana (−)", onClick: () => zoomBy(1 / 1.4) },
              { glyph: "fit", label: "Inquadra tutto (F)", onClick: fit },
            ],
            [
              { glyph: "layout", label: "Solo i vicini dell'elemento scelto (V)", pressed: local, disabled: !selectedId,
                onClick: () => setLocal(!local) },
              { glyph: "rotate", label: "Ridisponi il grafo", onClick: () => {
                current.current = null;
                placed.current.clear();
                fitted.current = false;
                setSeed((s) => s + 1);
              } },
              { glyph: "keys", label: "Scorciatoie da tastiera (?)", pressed: showKeys, onClick: () => setShowKeys(!showKeys) },
            ],
          ]} />
          {showKeys && <GraphKeys keys={KEYS} onClose={() => setShowKeys(false)} />}
          <div className="brain-hint">
            Trascina per spostare · rotella per lo zoom verso il puntatore · due dita a destra o a sinistra per spostarti · doppio clic sul vuoto per inquadrare tutto · <kbd>?</kbd> per i tasti
          </div>
        </div>
        <div className="stack">
          <div className="card card-body brain-legend">
            <div className="section-title">Legenda e filtri</div>
            {Object.entries(NODE_STYLE).filter(([type]) => counts[type]).map(([type, style]) => (
              <label key={type} className="row brain-legend-row">
                <input type="checkbox" checked={!hidden.has(type)} onChange={() => toggle(type)} />
                <span className="brain-swatch" style={{ background: style.color }} />
                {style.label}<span className="spacer" /><span className="muted">{counts[type]}</span>
              </label>
            ))}
          </div>
          {selected && (
            <section className="card card-body stack brain-panel" aria-label={`Dettagli di ${selected.label}`}>
              <div className="row">
                <span className="brain-swatch" style={{ background: NODE_STYLE[selected.type]?.color ?? FALLBACK }} />
                <span className="chip">{NODE_STYLE[selected.type]?.label ?? selected.type}</span>
                <span className="spacer" />
                <button className="btn btn-small btn-ghost" aria-label="Chiudi i dettagli" onClick={() => setSelectedId(null)}>
                  <Icon name="close" size={12} /></button>
              </div>
              <strong>{selected.label}</strong>
              {selected.status && <span className="muted">Stato: {selected.status}</span>}
              {Object.entries(selected.meta).filter(([, v]) => v !== null && v !== "").map(([k, v]) => (
                <div key={k} style={{ fontSize: 12 }}><span className="muted">{k}:</span> {String(v)}</div>
              ))}
              <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
                <button className="btn btn-small btn-primary" onClick={() => open(selected)}>Apri</button>
                <button className="btn btn-small" onClick={() => flyTo(selected.id)}>Centra</button>
                <button className="btn btn-small" aria-pressed={local} onClick={() => setLocal(!local)}>
                  {local ? "Mostra tutto" : "Solo i vicini"}</button>
              </div>
              <div className="section-title">Collegamenti ({links.length})</div>
              <div className="brain-links">
                {grouped.map(([label, list]) => (
                  <div key={label}>
                    <div className="brain-links-kind">{label}</div>
                    {list.map(({ other, out }, i) => (
                      <button key={`${other.id}:${i}`} className="eco-link brain-link" onClick={() => goTo(other.id)}
                              title={`${out ? "→" : "←"} ${other.label}`}>
                        <span className="muted" aria-hidden="true">{out ? "→" : "←"}</span>
                        <span className="dot" style={{ background: NODE_STYLE[other.type]?.color ?? FALLBACK }} />
                        <span className="eco-link-label">{other.label}</span>
                      </button>
                    ))}
                  </div>
                ))}
              </div>
            </section>
          )}
        </div>
      </div>
    </div>
  );
}

function radius(n: { type: string; degree: number }): number {
  const base = n.type === "project" ? 11 : n.type === "agent" ? 8 : n.type === "phase" ? 7 : 5;
  return base + Math.min(6, Math.sqrt(n.degree));
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}

function withAlpha(hex: string, alpha: number): string {
  const v = parseInt(hex.slice(1), 16);
  return `rgba(${(v >> 16) & 255}, ${(v >> 8) & 255}, ${v & 255}, ${clamp(alpha, 0, 1).toFixed(3)})`;
}

/** How far a sprite reaches, in node radii: the sphere is 1, the halo the rest. */
const SPRITE_SPAN = 2.1;

/** One translucent sphere per colour, drawn once at a size that stays sharp when zoomed. */
class SpriteCache {
  private sprites = new Map<string, HTMLCanvasElement>();

  get(color: string): HTMLCanvasElement {
    const found = this.sprites.get(color);
    if (found) return found;
    const size = 168;
    const c = document.createElement("canvas");
    c.width = size;
    c.height = size;
    const g = c.getContext("2d");
    this.sprites.set(color, c);
    if (!g) return c;
    const mid = size / 2;
    const r = mid / SPRITE_SPAN;
    // The halo: a wide, faint breath of the colour.
    const halo = g.createRadialGradient(mid, mid, r * 0.6, mid, mid, mid);
    halo.addColorStop(0, withAlpha(color, 0.26));
    halo.addColorStop(0.45, withAlpha(color, 0.07));
    halo.addColorStop(1, withAlpha(color, 0));
    g.fillStyle = halo;
    g.fillRect(0, 0, size, size);
    // The body: lit from the top left, see-through towards the edge.
    const body = g.createRadialGradient(mid - r * 0.38, mid - r * 0.42, r * 0.06, mid, mid, r);
    body.addColorStop(0, "rgba(255, 255, 255, 0.9)");
    body.addColorStop(0.3, withAlpha(color, 0.92));
    body.addColorStop(0.85, withAlpha(color, 0.6));
    body.addColorStop(1, withAlpha(color, 0.42));
    g.fillStyle = body;
    g.beginPath();
    g.arc(mid, mid, r, 0, Math.PI * 2);
    g.fill();
    // A shade at the bottom, and a thin rim of light: the sphere sits in the space.
    const shadow = g.createLinearGradient(0, mid - r, 0, mid + r);
    shadow.addColorStop(0.55, "rgba(10, 16, 30, 0)");
    shadow.addColorStop(1, "rgba(10, 16, 30, 0.26)");
    g.fillStyle = shadow;
    g.beginPath();
    g.arc(mid, mid, r, 0, Math.PI * 2);
    g.fill();
    g.strokeStyle = "rgba(255, 255, 255, 0.4)";
    g.lineWidth = 1.4;
    g.beginPath();
    g.arc(mid, mid, r - 0.7, 0, Math.PI * 2);
    g.stroke();
    return c;
  }
}

/** A dot grid that moves and scales with the view: the plane has a texture, the eye has a reference. */
function drawGrid(ctx: CanvasRenderingContext2D, w: number, h: number, view: View) {
  let step = 40 * view.k;
  while (step < 18) step *= 2;
  while (step > 72) step /= 2;
  const ox = ((w / 2 + view.x) % step + step) % step;
  const oy = ((h / 2 + view.y) % step + step) % step;
  ctx.fillStyle = "rgba(170, 186, 214, 0.09)";
  for (let x = ox; x < w; x += step) {
    for (let y = oy; y < h; y += step) ctx.fillRect(x - 0.75, y - 0.75, 1.5, 1.5);
  }
}

interface MinimapFrame { x0: number; y0: number; s: number; ox: number; oy: number }
/** How the last minimap maps its pixels to the world, for its clicks. */
const frames = new WeakMap<HTMLCanvasElement, MinimapFrame>();

/** Every node as a dot, and the frame the canvas shows now. */
function drawMinimap(el: HTMLCanvasElement | null, nodes: SimNode[], view: View, w: number, h: number) {
  if (!el || nodes.length === 0) return;
  const ctx = el.getContext("2d");
  if (!ctx) return;
  const ratio = window.devicePixelRatio || 1;
  const mw = el.clientWidth, mh = el.clientHeight;
  if (el.width !== Math.round(mw * ratio) || el.height !== Math.round(mh * ratio)) {
    el.width = Math.round(mw * ratio);
    el.height = Math.round(mh * ratio);
  }
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, mw, mh);
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const n of nodes) { x0 = Math.min(x0, n.x!); y0 = Math.min(y0, n.y!); x1 = Math.max(x1, n.x!); y1 = Math.max(y1, n.y!); }
  if (!Number.isFinite(x0)) return;
  const pad = 10;
  const s = Math.min((mw - pad * 2) / Math.max(1, x1 - x0), (mh - pad * 2) / Math.max(1, y1 - y0));
  const ox = (mw - (x1 - x0) * s) / 2, oy = (mh - (y1 - y0) * s) / 2;
  frames.set(el, { x0, y0, s, ox, oy });
  for (const n of nodes) {
    ctx.fillStyle = withAlpha(NODE_STYLE[n.type]?.color ?? FALLBACK, 0.85);
    const r = n.type === "project" || n.type === "agent" ? 2 : 1.3;
    ctx.beginPath();
    ctx.arc(ox + (n.x! - x0) * s, oy + (n.y! - y0) * s, r, 0, Math.PI * 2);
    ctx.fill();
  }
  // The rectangle of the world the canvas shows now.
  const vx0 = (-w / 2 - view.x) / view.k, vy0 = (-h / 2 - view.y) / view.k;
  const rx = ox + (vx0 - x0) * s, ry = oy + (vy0 - y0) * s;
  const rw = (w / view.k) * s, rh = (h / view.k) * s;
  ctx.fillStyle = "rgba(220, 230, 250, 0.06)";
  ctx.fillRect(rx, ry, rw, rh);
  ctx.strokeStyle = "rgba(220, 230, 250, 0.7)";
  ctx.lineWidth = 1;
  ctx.strokeRect(rx + 0.5, ry + 0.5, Math.max(0, rw - 1), Math.max(0, rh - 1));
}
