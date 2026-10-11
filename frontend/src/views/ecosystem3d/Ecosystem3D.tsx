import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useApi } from "../../context";
import type { AgentConfiguration, HubInstalled } from "../../api/types";
import { ProblemNote } from "../../components/ui";
import { Icon } from "../../components/icons";
import type { Navigate } from "../../App";
import EcosystemScene, { type CameraRequest } from "./EcosystemScene";
import { webglAvailable } from "./webgl";
import { NodeInspector } from "./NodeInspector";
import {
  CATEGORIES, CATEGORY, ECOSYSTEM_HIDDEN, INITIAL_VIEW, agentId, agentOf, buildGraph, countByCategory, neighbours, reveal,
  searchNodes, signature, visibleGraph, type Category, type Mode, type ViewState,
} from "./ecosystemModel";
import { layoutGraph } from "./ecosystemLayout";
import "./ecosystem3d.css";

/** How often the view reads the agents again, and every how many reads the Hub too. */
export const REFRESH_MS = 15_000;
const HUB_EVERY = 4;

const reducedMotion = () => typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/**
 * Ecosistema 3D (ADR-037): every agent and everything that defines it, as an
 * interactive three-dimensional map read from the same API the Agents tab and the
 * Knowledge Hub use. Read-only: it shows and links, the other tabs edit.
 */
export function Ecosystem3D({ navigate }: { navigate: Navigate }) {
  const api = useApi();
  const [data, setData] = useState<{ agents: AgentConfiguration[]; hub: HubInstalled | null } | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [hubProblem, setHubProblem] = useState<unknown>(null);
  const [live, setLive] = useState(true);
  const [busy, setBusy] = useState(false);

  const [nav, setNav] = useState<Omit<ViewState, "hidden">>({ ...INITIAL_VIEW });
  const [hidden, setHidden] = useState<Record<Mode, Category[]>>({ ecosystem: [...ECOSYSTEM_HIDDEN], agent: [] });
  const view: ViewState = useMemo(() => ({ ...nav, hidden: hidden[nav.mode] }), [nav, hidden]);
  const apply = useCallback((next: ViewState) => {
    const { hidden: h, ...rest } = next;
    setNav(rest);
    setHidden((current) => ({ ...current, [next.mode]: h }));
  }, []);

  const [selected, setSelected] = useState<string | null>(null);
  const [hovered, setHovered] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [labels, setLabels] = useState<"auto" | "all">("auto");
  const [animate, setAnimate] = useState(() => !reducedMotion());
  const [autoRotate, setAutoRotate] = useState(false);
  const [camera, setCamera] = useState<CameraRequest | null>(null);
  const [showAgents, setShowAgents] = useState(false);
  const cameraSeq = useRef(0);
  const ask = useCallback((request: { kind: "fit"; instant?: boolean } | { kind: "focus"; id: string }) =>
    setCamera({ ...request, seq: ++cameraSeq.current }), []);
  const canDraw = useMemo(() => webglAvailable(), []);

  // --- data: read now, then again while the tab is visible; an unchanged answer changes nothing ---
  const lastSignature = useRef("");
  const lastHub = useRef<HubInstalled | null>(null);
  const load = useCallback(async (withHub: boolean) => {
    const [agents, hub] = await Promise.all([
      api.ecosystemBindings(),
      withHub
        ? api.hubInstalled().then((h) => { setHubProblem(null); return h; }).catch((error: unknown) => { setHubProblem(error); return lastHub.current; })
        : Promise.resolve(lastHub.current),
    ]);
    lastHub.current = hub;
    const next = signature(agents, hub);
    if (next !== lastSignature.current) {
      lastSignature.current = next;
      setData({ agents, hub });
    }
    setUpdatedAt(new Date());
    setProblem(null);
  }, [api]);

  const refresh = useCallback(() => {
    setBusy(true);
    load(true).catch(setProblem).finally(() => setBusy(false));
  }, [load]);
  useEffect(() => { refresh(); }, [refresh]);

  useEffect(() => {
    if (!live) return;
    let reads = 0;
    const timer = window.setInterval(() => {
      if (document.visibilityState !== "visible") return;
      reads++;
      load(reads % HUB_EVERY === 0).catch(setProblem);
    }, REFRESH_MS);
    const onVisible = () => { if (document.visibilityState === "visible") load(false).catch(setProblem); };
    document.addEventListener("visibilitychange", onVisible);
    return () => { window.clearInterval(timer); document.removeEventListener("visibilitychange", onVisible); };
  }, [live, load]);

  // --- the graph, what is shown of it, and where ---
  const graph = useMemo(() => (data ? buildGraph(data.agents, data.hub) : null), [data]);
  const visible = useMemo(() => (graph ? visibleGraph(graph, view) : null), [graph, view]);
  const positions = useMemo(() => (visible ? layoutGraph(visible, { mode: view.mode, focus: view.focus }) : new Map()),
    [visible, view.mode, view.focus]);
  const counts = useMemo(() => (graph ? countByCategory(graph.nodes) : null), [graph]);
  const shownCounts = useMemo(() => (visible ? countByCategory(visible.nodes) : null), [visible]);

  // What the data no longer has, the view lets go of.
  useEffect(() => {
    if (!graph) return;
    if (selected && !graph.byId.has(selected)) setSelected(null);
    const present = new Set(graph.agentIds);
    setNav((current) => {
      let next = current;
      if (next.focus !== null && !present.has(next.focus)) next = { ...next, mode: "ecosystem", focus: null };
      if (next.agents && next.agents.some((a) => !present.has(a))) {
        const kept = next.agents.filter((a) => present.has(a));
        next = { ...next, agents: kept.length ? kept : null };
      }
      return next;
    });
  }, [graph]); // eslint-disable-line react-hooks/exhaustive-deps

  // Frame everything when the map first appears and when the mode or the focused agent changes.
  const framed = useRef(false);
  useEffect(() => {
    if (!visible || visible.nodes.length === 0) return;
    if (!framed.current) { framed.current = true; ask({ kind: "fit", instant: true }); }
  }, [visible, ask]);
  useEffect(() => { if (framed.current) ask({ kind: "fit" }); }, [view.mode, view.focus, ask]);

  const focusId = hovered ?? selected;
  const focusSet = useMemo(() => {
    if (!graph || !visible || !focusId || !visible.ids.has(focusId)) return null;
    return new Set([...neighbours(graph, focusId)].filter((id) => visible.ids.has(id)));
  }, [graph, visible, focusId]);
  const hits = useMemo(() => (graph ? searchNodes(graph, query, 40) : []), [graph, query]);
  const matches = useMemo(() => (query.trim().length >= 2 ? new Set(hits.map((h) => h.node.id)) : null), [hits, query]);

  // --- gestures ---
  const goTo = useCallback((id: string) => {
    if (!graph) return;
    apply(reveal(graph, view, id));
    setSelected(id);
    setSearchOpen(false);
    ask({ kind: "focus", id });
  }, [graph, view, apply, ask]);

  const toggleGroup = useCallback((id: string) => setNav((v) => ({
    ...v, expanded: v.expanded.includes(id) ? v.expanded.filter((g) => g !== id) : [...v.expanded, id],
  })), []);
  const toggleAgent = useCallback((agent: number) => setNav((v) => ({
    ...v, collapsed: v.collapsed.includes(agent) ? v.collapsed.filter((a) => a !== agent) : [...v.collapsed, agent],
  })), []);
  const single = useCallback((agent: number) => {
    setNav((v) => ({ ...v, mode: "agent", focus: agent }));
    setSelected(agentId(agent));
  }, []);
  const ecosystem = useCallback(() => setNav((v) => ({ ...v, mode: "ecosystem" })), []);

  const activate = useCallback((id: string) => {
    const node = graph?.byId.get(id);
    if (!node) return;
    if (node.isGroup) { toggleGroup(id); return; }
    const agent = agentOf(id);
    if (agent !== null) {
      if (view.mode === "agent" && view.focus === agent) ecosystem();
      else single(agent);
      return;
    }
    ask({ kind: "focus", id });
  }, [graph, view.mode, view.focus, toggleGroup, single, ecosystem, ask]);

  const toggleCategory = (c: Category) => setHidden((h) => ({
    ...h, [nav.mode]: h[nav.mode].includes(c) ? h[nav.mode].filter((x) => x !== c) : [...h[nav.mode], c],
  }));
  const toggleAgentFilter = (id: number) => setNav((v) => {
    const all = graph?.agentIds ?? [];
    const current = v.agents ?? all;
    const next = current.includes(id) ? current.filter((a) => a !== id) : [...current, id];
    return { ...v, agents: next.length === all.length ? null : next };
  });
  const groupsShown = visible ? visible.nodes.filter((n) => n.isGroup).map((n) => n.id) : [];
  const allExpanded = groupsShown.length > 0 && groupsShown.every((g) => nav.expanded.includes(g));
  const agentsShown = visible ? visible.nodes.filter((n) => n.category === "agent").map((n) => agentOf(n.id)!) : [];
  const allCollapsed = agentsShown.length > 0 && agentsShown.every((a) => nav.collapsed.includes(a));

  const onSearchKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter" && hits[0]) goTo(hits[0].node.id);
    if (event.key === "Escape") { setQuery(""); setSearchOpen(false); }
  };

  const selectedNode = selected && graph ? graph.byId.get(selected) ?? null : null;
  const agents = useMemo(() => (data?.agents ?? []).slice().sort((a, b) => a.name.localeCompare(b.name)), [data]);
  const focusAgent = view.focus !== null ? agents.find((a) => a.id === view.focus) ?? null : null;

  return (
    <div className="stack eco-page">
      <div className="page-head">
        <div>
          <h1>Ecosistema 3D</h1>
          <p>Gli agenti e tutto ciò che li definisce — modello, skill, knowledge, MCP, plugin, prompt, direttive, ruoli — come una mappa
            navigabile, letta dalla configurazione reale.</p>
        </div>
        <div className="row">
          <label className="bp-check" title="Rilegge agenti e risorse ogni 15 secondi mentre la scheda è visibile">
            <input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} /> Aggiornamento automatico
          </label>
          {updatedAt && <span className="muted" style={{ fontSize: 12 }}>
            {live && <span className="eco-live" aria-hidden="true" />}aggiornato alle {updatedAt.toLocaleTimeString("it-IT")}</span>}
          <button className="btn" onClick={refresh} disabled={busy}><Icon name="refresh" size={14} /> Aggiorna</button>
        </div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {hubProblem !== null && (
        <div className="notice notice-warn row" role="status">
          <Icon name="warning" size={13} /> Le skill, i plugin e i connettori delle app non sono leggibili ora: la mappa mostra il resto.
          <span className="spacer" /><button className="btn btn-small" onClick={() => setHubProblem(null)}>Chiudi</button>
        </div>
      )}

      <div className="card card-body eco-toolbar">
        <div className="row">
          <div className="tabs" role="group" aria-label="Modalità">
            <button className="tab" aria-selected={view.mode === "ecosystem"} onClick={ecosystem}>Intero ecosistema</button>
            <button className="tab" aria-selected={view.mode === "agent"}
                    onClick={() => { const id = view.focus ?? agentOf(selected ?? "") ?? agents[0]?.id; if (id !== undefined) single(id); }}
                    disabled={agents.length === 0}>Agente singolo</button>
          </div>
          {view.mode === "agent" && (
            <select aria-label="Agente da mostrare" value={view.focus ?? ""} style={{ width: "auto", minWidth: 200 }}
                    onChange={(e) => single(Number(e.target.value))}>
              {agents.map((a) => <option key={a.id} value={a.id}>{a.name} — {a.role}</option>)}
            </select>
          )}
          <div className="search eco-search">
            <Icon name="search" size={15} />
            <input aria-label="Cerca nella mappa" placeholder="Cerca agenti, skill, MCP, prompt, direttive…" value={query}
                   onChange={(e) => { setQuery(e.target.value); setSearchOpen(true); }} onFocus={() => setSearchOpen(true)}
                   onKeyDown={onSearchKey} />
            {query && <button className="btn btn-small" aria-label="Svuota la ricerca" onClick={() => { setQuery(""); setSearchOpen(false); }}>
              <Icon name="close" size={12} /></button>}
            {searchOpen && query.trim().length >= 2 && (
              <div className="search-results" role="listbox" aria-label="Risultati">
                {hits.length === 0 && <div className="muted" style={{ padding: "8px 12px" }}>Nessun elemento trovato.</div>}
                {hits.slice(0, 14).map((h) => (
                  <button key={h.node.id} role="option" aria-selected={false} onClick={() => goTo(h.node.id)}>
                    <span className="eco-dot" style={{ background: CATEGORY[h.node.category].color }} />
                    <span className="eco-link-label">{h.node.label}</span>
                    <small className="muted">{h.node.subtitle}{h.where === "text" ? " · nel testo" : ""}
                      {visible && !visible.ids.has(h.node.id) ? " · nascosto" : ""}</small>
                  </button>
                ))}
                {hits.length > 14 && <div className="muted" style={{ padding: "6px 12px", fontSize: 12 }}>
                  Altri {hits.length - 14} risultati: restringi la ricerca.</div>}
              </div>
            )}
          </div>
          <span className="spacer" />
          <button className="btn btn-small" disabled={groupsShown.length === 0}
                  onClick={() => setNav((v) => ({ ...v, expanded: allExpanded ? v.expanded.filter((g) => !groupsShown.includes(g))
                    : [...new Set([...v.expanded, ...groupsShown])] }))}>
            {allExpanded ? "Comprimi i cluster" : "Espandi i cluster"}</button>
          {view.mode === "ecosystem" && (
            <button className="btn btn-small" disabled={agentsShown.length === 0}
                    onClick={() => setNav((v) => ({ ...v, collapsed: allCollapsed ? [] : agentsShown }))}>
              {allCollapsed ? "Espandi gli agenti" : "Comprimi gli agenti"}</button>
          )}
          <button className="btn btn-small" aria-pressed={labels === "all"} onClick={() => setLabels(labels === "all" ? "auto" : "all")}>
            Etichette: {labels === "all" ? "tutte" : "essenziali"}</button>
          <button className="btn btn-small" aria-pressed={animate} onClick={() => setAnimate(!animate)}>
            Animazioni {animate ? "attive" : "ferme"}</button>
          <button className="btn btn-small" aria-pressed={autoRotate} onClick={() => setAutoRotate(!autoRotate)}>Rotazione</button>
          <button className="btn btn-small" onClick={() => ask({ kind: "fit" })}><Icon name="dashboard" size={13} /> Centra</button>
        </div>

        <div className="eco-legend" role="group" aria-label="Categorie">
          {CATEGORIES.map((c) => {
            const off = c.key !== "agent" && view.hidden.includes(c.key);
            const total = counts?.[c.key] ?? 0;
            return (
              <button key={c.key} className={off ? "bp-fchip" : "bp-fchip is-on"} aria-pressed={!off} disabled={c.key === "agent"}
                      title={c.hint} onClick={() => toggleCategory(c.key)} style={{ opacity: total === 0 ? 0.55 : 1 }}>
                <span className={`eco-glyph eco-glyph-${c.shape}`} style={{ color: c.color }} aria-hidden="true" />
                {c.label}
                <span className="bp-fcount">{off ? total : `${shownCounts?.[c.key] ?? 0}/${total}`}</span>
              </button>
            );
          })}
        </div>

        {view.mode === "ecosystem" && agents.length > 0 && (
          <div className="eco-agents">
            <button className="btn btn-small" aria-expanded={showAgents} onClick={() => setShowAgents(!showAgents)}>
              <Icon name="agents" size={13} /> Filtra per agente
              <span className="bp-fcount">{view.agents ? `${view.agents.length}/${agents.length}` : "tutti"}</span>
            </button>
            {view.agents && <button className="btn btn-small" onClick={() => setNav((v) => ({ ...v, agents: null }))}>Mostra tutti</button>}
            {showAgents && (
              <div className="eco-agent-chips" role="group" aria-label="Agenti mostrati">
                {agents.map((a) => {
                  const on = !view.agents || view.agents.includes(a.id);
                  return (
                    <button key={a.id} className={on ? "bp-fchip is-on" : "bp-fchip"} aria-pressed={on} onClick={() => toggleAgentFilter(a.id)}>
                      <span className="eco-dot" style={{ background: a.status === "ACTIVE" ? CATEGORY.agent.color : "#64748b" }} />
                      {a.name}{a.parent ? <span className="bp-fcount">sotto {a.parent.name}</span> : null}
                    </button>
                  );
                })}
                <button className="btn btn-small" onClick={() => setNav((v) => ({ ...v, agents: [] }))}>Nessuno</button>
              </div>
            )}
          </div>
        )}
      </div>

      <div className={selectedNode ? "bp-stage has-inspector" : "bp-stage"}>
        <div className="card eco-stage" role="img"
             aria-label={visible ? `Mappa 3D: ${visible.nodes.length} elementi e ${visible.edges.length} connessioni` : "Mappa 3D"}>
          {!canDraw && (
            <div className="bp-empty">
              <Icon name="warning" size={28} />
              <strong>Questo browser non può disegnare la mappa 3D (WebGL non disponibile).</strong>
              <span className="muted">Gli agenti restano consultabili e modificabili nella scheda Agenti; la ricerca qui sopra funziona comunque.</span>
              <button className="btn" onClick={() => navigate("agents")}>Apri la scheda Agenti</button>
            </div>
          )}
          {canDraw && data && data.agents.length === 0 && (
            <div className="bp-empty">
              <Icon name="agents" size={28} />
              <strong>Nessun agente da mostrare.</strong>
              <span className="muted">Crea un agente o installalo da un template nella scheda Agenti: comparirà qui da solo.</span>
              <button className="btn btn-primary" onClick={() => navigate("agents")}>Vai agli agenti</button>
            </div>
          )}
          {canDraw && !data && !problem && <div className="bp-empty"><span className="muted">Carico l'ecosistema…</span></div>}
          {canDraw && visible && visible.nodes.length > 0 && (
            <EcosystemScene visible={visible} positions={positions} focusId={focusId} focusSet={focusSet} matches={matches}
                            selected={selected} labels={labels} animate={animate} autoRotate={autoRotate} camera={camera}
                            onHover={setHovered} onSelect={setSelected} onActivate={activate} />
          )}
          {canDraw && data && data.agents.length > 0 && visible?.nodes.length === 0 && (
            <div className="bp-empty"><strong>Nessun agente scelto.</strong>
              <button className="btn" onClick={() => setNav((v) => ({ ...v, agents: null }))}>Mostra tutti gli agenti</button></div>
          )}
          {visible && visible.nodes.length > 0 && (
            <>
              <div className="eco-overlay eco-overlay-top">
                {view.mode === "agent" && focusAgent
                  ? <span><strong>{focusAgent.name}</strong> · vista agente singolo</span>
                  : <span>{agentsShown.length} agenti · {visible.nodes.length} elementi · {visible.edges.length} connessioni</span>}
              </div>
              <div className="eco-overlay eco-overlay-bottom muted">
                Trascina per ruotare · tasto destro per spostare · rotella per lo zoom · clic per le proprietà · doppio clic per espandere o aprire l'agente
              </div>
            </>
          )}
        </div>
        {selectedNode && graph && visible && (
          <NodeInspector key={selectedNode.id} graph={graph} visible={visible} node={selectedNode}
                         expanded={nav.expanded.includes(selectedNode.id)} collapsed={nav.collapsed.includes(agentOf(selectedNode.id) ?? -1)}
                         mode={view.mode} focus={view.focus} navigate={navigate} onSelect={goTo} onClose={() => setSelected(null)}
                         onToggleGroup={toggleGroup} onToggleAgent={toggleAgent} onSingle={single} onEcosystem={ecosystem} />
        )}
      </div>
    </div>
  );
}
