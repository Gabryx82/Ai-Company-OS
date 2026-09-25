import { useEffect, useRef, useState } from "react";
import { forceCenter, forceLink, forceManyBody, forceSimulation, type SimulationLinkDatum, type SimulationNodeDatum } from "d3-force";
import { useApi } from "../context";
import type { CodeGraph } from "../api/types";
import { ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";

const COLORS: Record<string, string> = {
  typescript: "#3b82f6", javascript: "#fbbf24", java: "#f87171", python: "#34d399", manifest: "#a78bfa",
  npm: "#64748b", pypi: "#64748b", java_ext: "#64748b",
};

type Node = SimulationNodeDatum & { id: string; label: string; kind: string; language: string; weight: number };
type Link = SimulationLinkDatum<Node> & { kind: string };

/**
 * Graph engineering for a project (ADR-030): its files and their imports, the
 * external packages, the import cycles, and which tasks name which files -- the
 * same map agents read as `.aicos/CODE_GRAPH.md`.
 */
export function CodeGraphView({ projectId }: { projectId: number }) {
  const api = useApi();
  const [graph, setGraph] = useState<CodeGraph | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [showExternal, setShowExternal] = useState(false);
  const [hover, setHover] = useState<string | null>(null);
  const canvas = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    api.codeGraph(projectId).then((g) => { setGraph(g); setLoaded(true); }).catch((e) => { setProblem(e); setLoaded(true); });
  }, [api, projectId]);

  async function scan() {
    setBusy(true);
    setProblem(null);
    try { setGraph(await api.scanCodeGraph(projectId)); } catch (error) { setProblem(error); } finally { setBusy(false); }
  }

  useEffect(() => {
    if (!graph || !canvas.current) return;
    const el = canvas.current;
    const width = el.clientWidth;
    const height = 460;
    el.width = width * devicePixelRatio;
    el.height = height * devicePixelRatio;
    const ctx = el.getContext("2d")!;
    ctx.scale(devicePixelRatio, devicePixelRatio);
    const inDegree = new Map<string, number>();
    graph.edges.forEach((e) => inDegree.set(e.target, (inDegree.get(e.target) ?? 0) + 1));
    const nodes: Node[] = graph.nodes
      .filter((n) => showExternal || n.kind !== "EXTERNAL")
      .slice(0, 600)
      .map((n) => ({ id: n.id, label: n.label, kind: n.kind, language: n.kind === "EXTERNAL" ? "npm" : n.language,
        weight: inDegree.get(n.id) ?? 0 }));
    const ids = new Set(nodes.map((n) => n.id));
    const links: Link[] = graph.edges.filter((e) => ids.has(e.source) && ids.has(e.target))
      .map((e) => ({ source: e.source, target: e.target, kind: e.kind }));
    const inCycle = new Set(graph.cycles.flat());
    const simulation = forceSimulation(nodes)
      .force("link", forceLink<Node, Link>(links).id((d) => d.id).distance(40))
      .force("charge", forceManyBody().strength(-60))
      .force("center", forceCenter(width / 2, height / 2));
    const draw = () => {
      ctx.clearRect(0, 0, width, height);
      ctx.lineWidth = 1;
      for (const l of links) {
        const s = l.source as Node;
        const t = l.target as Node;
        ctx.strokeStyle = l.kind === "IMPORT" && inCycle.has(s.id) && inCycle.has(t.id) ? "rgba(248,113,113,0.7)" : "rgba(139,152,184,0.25)";
        ctx.beginPath();
        ctx.moveTo(s.x ?? 0, s.y ?? 0);
        ctx.lineTo(t.x ?? 0, t.y ?? 0);
        ctx.stroke();
      }
      for (const n of nodes) {
        const r = 3 + Math.min(9, Math.sqrt(n.weight) * 2);
        ctx.fillStyle = COLORS[n.language] ?? "#8b98b8";
        ctx.beginPath();
        ctx.arc(n.x ?? 0, n.y ?? 0, r, 0, Math.PI * 2);
        ctx.fill();
        if (inCycle.has(n.id)) {
          ctx.strokeStyle = "#f87171";
          ctx.stroke();
        }
        if (n.weight >= 3 || n.id === hover) {
          ctx.fillStyle = "#e7ecf7";
          ctx.font = "11px Inter, sans-serif";
          ctx.fillText(n.label, (n.x ?? 0) + r + 2, (n.y ?? 0) + 3);
        }
      }
    };
    simulation.on("tick", draw);
    const onMove = (event: MouseEvent) => {
      const rect = el.getBoundingClientRect();
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      const found = nodes.find((n) => Math.hypot((n.x ?? 0) - x, (n.y ?? 0) - y) < 8);
      setHover(found ? found.id : null);
    };
    el.addEventListener("mousemove", onMove);
    return () => { simulation.stop(); el.removeEventListener("mousemove", onMove); };
  }, [graph, showExternal]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!loaded) return <div className="muted">Carico…</div>;

  return (
    <div className="stack">
      <div className="row">
        <div className="muted" style={{ fontSize: 12.5 }}>
          {graph ? <>Ultima analisi {when(graph.generatedAt)} · anche in <code>.aicos/CODE_GRAPH.md</code>, letto dagli agenti come contesto.</>
            : "Nessuna analisi ancora: legge i file del progetto, senza eseguire nulla."}
        </div>
        <span className="spacer" />
        {graph && <label className="row muted" style={{ gap: 6, fontSize: 12.5 }}><input type="checkbox" style={{ width: "auto" }}
               checked={showExternal} onChange={(e) => setShowExternal(e.target.checked)} /> dipendenze esterne</label>}
        <button className="btn btn-primary" disabled={busy} onClick={scan}><Icon name="graph" size={14} /> {busy ? "Analizzo…" : graph ? "Rianalizza" : "Analizza il codice"}</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {graph && (
        <>
          <div className="grid grid-4">
            <div className="card card-body"><div className="muted">File sorgente</div><strong style={{ fontSize: 22 }}>{graph.files}</strong>
              <div className="muted" style={{ fontSize: 12 }}>{Object.entries(graph.languages).map(([l, n]) => `${l} ${n}`).join(" · ")}</div></div>
            <div className="card card-body"><div className="muted">Import interni</div><strong style={{ fontSize: 22 }}>{graph.edges.filter((e) => e.kind === "IMPORT").length}</strong></div>
            <div className="card card-body"><div className="muted">Pacchetti esterni</div><strong style={{ fontSize: 22 }}>{graph.externals}</strong></div>
            <div className="card card-body"><div className="muted">Cicli di import</div><strong style={{ fontSize: 22, color: graph.cycles.length ? "var(--danger)" : undefined }}>{graph.cycles.length}</strong></div>
          </div>
          <div className="card" style={{ padding: 8 }}>
            <canvas ref={canvas} style={{ width: "100%", height: 460, display: "block" }} aria-label="Grafo del codice" />
            {hover && <div className="mono muted" style={{ fontSize: 11.5 }}>{hover}</div>}
          </div>
          <div className="grid grid-2">
            <div className="card card-body stack" style={{ gap: 4 }}>
              <div className="section-title" style={{ margin: 0 }}>I file più importati</div>
              {graph.mostImported.map((h) => <div key={h.id} className="row" style={{ fontSize: 12.5 }}><code>{h.id}</code><span className="spacer" /><span className="muted">{h.importedBy}</span></div>)}
              {graph.mostImported.length === 0 && <span className="muted">Nessun import interno.</span>}
            </div>
            <div className="card card-body stack" style={{ gap: 4 }}>
              <div className="section-title" style={{ margin: 0 }}>Cicli di import</div>
              {graph.cycles.map((c) => <div key={c.join("|")} style={{ fontSize: 12.5 }}>{c.map((f) => <code key={f} style={{ marginRight: 6 }}>{f}</code>)}</div>)}
              {graph.cycles.length === 0 && <span className="muted">Nessun ciclo.</span>}
              {graph.taskLinks.length > 0 && (
                <>
                  <div className="section-title">Task e file</div>
                  {graph.taskLinks.map((l) => <div key={`${l.taskId}-${l.file}`} style={{ fontSize: 12.5 }}>{l.code ?? `#${l.taskId}`} {l.title} → <code>{l.file}</code></div>)}
                </>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
