import { useEffect, useMemo, useRef, useState } from "react";
import {
  forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY,
  type Simulation, type SimulationLinkDatum, type SimulationNodeDatum,
} from "d3-force";
import { useApi } from "../context";
import { ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

type GraphNode = { id: string; type: string; label: string; status: string | null; meta: Record<string, unknown> };
type GraphEdge = { source: string; target: string; kind: string };
type SimNode = SimulationNodeDatum & GraphNode & { degree: number };
type SimLink = SimulationLinkDatum<SimNode> & { kind: string };

export const NODE_STYLE: Record<string, { color: string; label: string }> = {
  project: { color: "#8b5cf6", label: "Progetto" },
  phase: { color: "#3b82f6", label: "Fase" },
  task: { color: "#22c55e", label: "Task" },
  agent: { color: "#f97316", label: "Agente" },
  subagent: { color: "#fdba74", label: "Sottoagente" },
  software: { color: "#06b6d4", label: "Software" },
  model: { color: "#eab308", label: "Modello" },
  provider: { color: "#94a3b8", label: "Provider" },
  document: { color: "#fde047", label: "Documento" },
  skill: { color: "#ec4899", label: "Skill" },
  knowledge: { color: "#14b8a6", label: "Knowledge" },
  mcp: { color: "#6366f1", label: "MCP" },
  tool: { color: "#84cc16", label: "Tool" },
  framework: { color: "#0ea5e9", label: "Framework" },
  template_provider: { color: "#f43f5e", label: "Template" },
  daily: { color: "#10b981", label: "Daily" },
};

const EDGE_LABEL: Record<string, string> = {
  contains: "contiene", "assigned-to": "assegnata a", "subagent-of": "sottoagente di", "uses-model": "usa il modello",
  "served-by": "servito da", "equipped-with": "equipaggiato con", adopts: "adotta", "uses-software": "usa",
  "handed-to": "consegnata a", "refers-to": "riferita a", "has-document": "documento",
};

const DEFAULT_HIDDEN = new Set(["provider", "tool", "template_provider", "framework"]);

/**
 * The Second Brain (directive §17): the ecosystem as a living graph, in the
 * spirit of Obsidian's graph view -- forces keep it arranged, the operator
 * drags, zooms and follows the links, and every node opens what it is.
 */
export function SecondBrain({ navigate }: { navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const canvas = useRef<HTMLCanvasElement>(null);
  const [data, setData] = useState<{ nodes: GraphNode[]; edges: GraphEdge[] } | null>(null);
  const [hidden, setHidden] = useState<Set<string>>(DEFAULT_HIDDEN);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<SimNode | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const hovered = useRef<SimNode | null>(null);
  const view = useRef({ x: 0, y: 0, k: 1 });
  const sim = useRef<Simulation<SimNode, SimLink> | null>(null);
  // Read by the drawing loop; kept out of the simulation's dependencies, so that
  // selecting or searching never reheats the layout.
  const selectedRef = useRef<SimNode | null>(null);
  const needleRef = useRef("");

  useEffect(() => {
    api.graph().then(setData).catch(setProblem);
  }, [api]);

  const graph = useMemo(() => {
    if (!data) return null;
    const nodes: SimNode[] = data.nodes.filter((n) => !hidden.has(n.type)).map((n) => ({ ...n, degree: 0 }));
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const links: SimLink[] = data.edges.filter((e) => byId.has(e.source) && byId.has(e.target))
      .map((e) => ({ source: e.source, target: e.target, kind: e.kind }));
    links.forEach((l) => { byId.get(l.source as string)!.degree++; byId.get(l.target as string)!.degree++; });
    return { nodes, links, byId };
  }, [data, hidden]);

  const needle = query.trim().toLowerCase();
  useEffect(() => { selectedRef.current = selected; }, [selected]);
  useEffect(() => { needleRef.current = needle; }, [needle]);

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

    const simulation = forceSimulation<SimNode>(graph.nodes)
      .force("link", forceLink<SimNode, SimLink>(graph.links).id((n) => n.id)
        .distance((l) => (l.kind === "contains" ? 45 : l.kind === "served-by" ? 60 : 80)).strength(0.5))
      .force("charge", forceManyBody().strength(-110))
      .force("center", forceCenter(0, 0))
      .force("x", forceX(0).strength(0.03))
      .force("y", forceY(0).strength(0.03))
      .force("collide", forceCollide<SimNode>().radius((n) => radius(n) + 4));
    sim.current = simulation;

    let frame = 0;
    let tick = 0;
    const resize = () => {
      const ratio = window.devicePixelRatio || 1;
      el.width = el.clientWidth * ratio;
      el.height = el.clientHeight * ratio;
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    };
    resize();
    window.addEventListener("resize", resize);

    const draw = () => {
      tick++;
      const w = el.clientWidth;
      const h = el.clientHeight;
      const { x, y, k } = view.current;
      ctx.save();
      ctx.clearRect(0, 0, w, h);
      ctx.translate(w / 2 + x, h / 2 + y);
      ctx.scale(k, k);
      const selectedNow = selectedRef.current;
      const needleNow = needleRef.current;
      const focus = hovered.current ?? selectedNow;
      const near = focus ? neighbours.get(focus.id) ?? new Set<string>() : null;

      for (const l of graph.links) {
        const s = l.source as SimNode;
        const t = l.target as SimNode;
        const lit = focus && (s.id === focus.id || t.id === focus.id);
        ctx.strokeStyle = lit ? "rgba(147,197,253,0.9)" : focus ? "rgba(100,116,139,0.10)" : "rgba(100,116,139,0.28)";
        ctx.lineWidth = lit ? 1.6 / k : 0.8 / k;
        ctx.beginPath();
        ctx.moveTo(s.x!, s.y!);
        ctx.lineTo(t.x!, t.y!);
        ctx.stroke();
        if (lit) {
          // a particle travelling along the edge: the link is alive
          const p = ((tick % 90) / 90);
          ctx.fillStyle = "#bfdbfe";
          ctx.beginPath();
          ctx.arc(s.x! + (t.x! - s.x!) * p, s.y! + (t.y! - s.y!) * p, 2 / k, 0, Math.PI * 2);
          ctx.fill();
        }
      }
      for (const n of graph.nodes) {
        const style = NODE_STYLE[n.type] ?? { color: "#cbd5e1" };
        const dim = focus && n.id !== focus.id && !near!.has(n.id);
        const match = needleNow && n.label.toLowerCase().includes(needleNow);
        const r = radius(n) * (match ? 1.5 : 1);
        ctx.globalAlpha = dim ? 0.18 : 1;
        ctx.shadowColor = style.color;
        ctx.shadowBlur = dim ? 0 : (n.type === "project" ? 18 : 8);
        ctx.fillStyle = style.color;
        ctx.beginPath();
        ctx.arc(n.x!, n.y!, r, 0, Math.PI * 2);
        ctx.fill();
        ctx.shadowBlur = 0;
        if (n === selectedNow || match) {
          ctx.strokeStyle = "#ffffff";
          ctx.lineWidth = 2 / k;
          ctx.stroke();
        }
        const showLabel = !dim && (n.type === "project" || n.type === "agent" || k > 1.3 || n === focus
          || (near && near.has(n.id)) || match);
        if (showLabel) {
          ctx.fillStyle = "#e2e8f0";
          ctx.font = `${Math.max(10, 11 / k)}px Inter, Segoe UI, sans-serif`;
          ctx.textAlign = "center";
          ctx.fillText(n.label.length > 42 ? n.label.slice(0, 40) + "…" : n.label, n.x!, n.y! + r + 12 / k);
        }
        ctx.globalAlpha = 1;
      }
      ctx.restore();
      frame = requestAnimationFrame(draw);
    };
    frame = requestAnimationFrame(draw);

    // --- interaction: pan, zoom, drag, hover, select ---
    const toWorld = (event: MouseEvent) => {
      const rect = el.getBoundingClientRect();
      const { x, y, k } = view.current;
      return { x: (event.clientX - rect.left - rect.width / 2 - x) / k, y: (event.clientY - rect.top - rect.height / 2 - y) / k };
    };
    const find = (event: MouseEvent) => {
      const p = toWorld(event);
      let best: SimNode | null = null;
      let bestDistance = Infinity;
      for (const n of graph.nodes) {
        const d = Math.hypot(n.x! - p.x, n.y! - p.y);
        if (d < radius(n) + 6 / view.current.k && d < bestDistance) { best = n; bestDistance = d; }
      }
      return best;
    };
    let dragging: SimNode | null = null;
    let panning: { x: number; y: number } | null = null;
    let moved = false;
    const down = (event: MouseEvent) => {
      moved = false;
      const hit = find(event);
      if (hit) {
        dragging = hit;
        simulation.alphaTarget(0.25).restart();
      } else {
        panning = { x: event.clientX, y: event.clientY };
      }
    };
    const move = (event: MouseEvent) => {
      if (dragging) {
        const p = toWorld(event);
        dragging.fx = p.x;
        dragging.fy = p.y;
        moved = true;
      } else if (panning) {
        view.current.x += event.clientX - panning.x;
        view.current.y += event.clientY - panning.y;
        panning = { x: event.clientX, y: event.clientY };
        moved = true;
      } else {
        hovered.current = find(event);
        el.style.cursor = hovered.current ? "pointer" : "grab";
      }
    };
    const up = (event: MouseEvent) => {
      if (dragging) {
        if (!moved) setSelected(dragging);
        dragging.fx = null;
        dragging.fy = null;
        simulation.alphaTarget(0);
      } else if (panning && !moved) {
        setSelected(find(event));
      }
      dragging = null;
      panning = null;
    };
    const wheel = (event: WheelEvent) => {
      event.preventDefault();
      const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12;
      view.current.k = Math.min(4, Math.max(0.2, view.current.k * factor));
    };
    el.addEventListener("mousedown", down);
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
    el.addEventListener("wheel", wheel, { passive: false });

    return () => {
      cancelAnimationFrame(frame);
      simulation.stop();
      window.removeEventListener("resize", resize);
      el.removeEventListener("mousedown", down);
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
      el.removeEventListener("wheel", wheel);
    };
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
    setSelected(null);
  };

  const links = selected && data ? data.edges.filter((e) => e.source === selected.id || e.target === selected.id) : [];
  const nodeOf = (id: string) => data?.nodes.find((n) => n.id === id);

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

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Second Brain</h1><p>La mappa viva dell'ecosistema: trascina, zooma, segui i collegamenti. Clic su un nodo per il dettaglio.</p></div>
        <div className="search" style={{ maxWidth: 300 }}><Icon name="search" size={15} />
          <input placeholder="Evidenzia un nodo…" value={query} onChange={(e) => setQuery(e.target.value)} /></div>
      </div>
      <ProblemNote problem={problem} />
      <div className="split" style={{ gridTemplateColumns: "1fr 280px" }}>
        <div className="card" style={{ padding: 0, overflow: "hidden", position: "relative",
          background: "radial-gradient(900px 500px at 50% 40%, rgba(59,130,246,.10), transparent 70%), #060b16" }}>
          <canvas ref={canvas} style={{ width: "100%", height: "calc(100vh - 220px)", minHeight: 520, display: "block", cursor: "grab" }} />
          {!data && <div className="muted" style={{ position: "absolute", top: 16, left: 16 }}>Carico il grafo…</div>}
          <div style={{ position: "absolute", bottom: 10, left: 12 }} className="row">
            <button className="btn btn-small" onClick={() => { view.current = { x: 0, y: 0, k: 1 }; sim.current?.alpha(0.6).restart(); }}>Riallinea</button>
            <span className="muted" style={{ fontSize: 11 }}>{graph?.nodes.length ?? 0} nodi · {graph?.links.length ?? 0} collegamenti</span>
          </div>
        </div>
        <div className="stack">
          <div className="card card-body" style={{ gap: 4, display: "grid" }}>
            <div className="section-title">Legenda e filtri</div>
            {Object.entries(NODE_STYLE).filter(([type]) => counts[type]).map(([type, style]) => (
              <label key={type} className="row" style={{ cursor: "pointer", fontSize: 12.5 }}>
                <input type="checkbox" style={{ width: "auto" }} checked={!hidden.has(type)} onChange={() => toggle(type)} />
                <span className="dot" style={{ background: style.color, boxShadow: `0 0 6px ${style.color}` }} />
                {style.label}<span className="spacer" /><span className="muted">{counts[type]}</span>
              </label>
            ))}
          </div>
          {selected && (
            <div className="card card-body stack">
              <div className="row"><span className="dot" style={{ background: NODE_STYLE[selected.type]?.color }} />
                <span className="chip">{NODE_STYLE[selected.type]?.label ?? selected.type}</span></div>
              <strong>{selected.label}</strong>
              {selected.status && <span className="muted">Stato: {selected.status}</span>}
              {Object.entries(selected.meta).filter(([, v]) => v !== null && v !== "").map(([k, v]) => (
                <div key={k} style={{ fontSize: 12 }}><span className="muted">{k}:</span> {String(v)}</div>
              ))}
              <div className="section-title">Collegamenti ({links.length})</div>
              <div style={{ maxHeight: 260, overflowY: "auto" }}>
                {links.map((e, i) => {
                  const other = nodeOf(e.source === selected.id ? e.target : e.source);
                  return other ? (
                    <div key={i} style={{ fontSize: 12, padding: "2px 0" }}>
                      <span className="muted">{EDGE_LABEL[e.kind] ?? e.kind}</span> {e.source === selected.id ? "→" : "←"}{" "}
                      <span style={{ color: NODE_STYLE[other.type]?.color }}>●</span> {other.label}
                    </div>
                  ) : null;
                })}
              </div>
              <button className="btn btn-small btn-primary" onClick={() => open(selected)}>Apri</button>
            </div>
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
