import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ReactFlowProvider, useNodesInitialized, useReactFlow } from "@xyflow/react";
import { useApi } from "../../context";
import { ApiProblem } from "../../api/client";
import type { BlueprintBlock, BlueprintFamily, BlueprintGraph, BlueprintLayer, BlueprintVocabulary } from "../../api/types";
import { ProblemNote, when } from "../../components/ui";
import { Icon } from "../../components/icons";
import { ConfirmDeletion } from "../../components/Deletion";
import { useIsAdmin } from "../../context";
import { BlueprintCanvas } from "./BlueprintCanvas";
import { BlockInspector, RelationInspector, type InspectorActions } from "./Inspector";
import { AddBlockDialog, FileViewer, ProposalsDialog, RelationDialog } from "./BlueprintDialogs";
import { BlockActionsContext, EmphasisContext, EmphasisStore } from "./emphasis";
import {
  ALL_ORIGINS, defaultFilters, freeSpot, neighbours, search, toggle, visibleBlocks, visibleRelations, type Filters,
} from "./blueprintModel";
import { FAMILY_LABEL, KIND_LOOK, LAYER_LABEL, ORIGIN_LABEL } from "./blueprintStyle";

const LAYERS: BlueprintLayer[] = ["BACKEND", "FRONTEND", "DATABASE", "TESTS", "DOCS"];
const FAMILIES: BlueprintFamily[] = ["FILES", "CLASSES", "METHODS", "APIS", "DATA", "TESTS", "CUSTOM"];

type Dialog = null | { kind: "add" } | { kind: "relation"; source: number; target: number | null } | { kind: "proposals" }
  | { kind: "file"; block: BlueprintBlock };

/**
 * PHASE 28 (ADR-033): the Project Blueprint -- the technical structure of a
 * project as blocks and relations, read from the code and editable, in the spirit
 * of Unreal's Blueprints and much simpler. Reading mode documents the code;
 * editing mode changes the Blueprint, and anything that would change a real file
 * becomes a Proposed Code Change, never an edit.
 */
export function BlueprintView({ projectId, projectName, onOpenTask }: {
  projectId: number; projectName?: string; onOpenTask: (taskId: number) => void;
}) {
  return (
    <ReactFlowProvider>
      <Workspace projectId={projectId} projectName={projectName} onOpenTask={onOpenTask} />
    </ReactFlowProvider>
  );
}

function Workspace({ projectId, projectName, onOpenTask }: {
  projectId: number; projectName?: string; onOpenTask: (taskId: number) => void;
}) {
  const api = useApi();
  const isAdmin = useIsAdmin();
  const [deleting, setDeleting] = useState(false);
  const loadDeletion = useCallback(() => api.blueprintDeletionPreview(projectId), [api, projectId]);
  const flow = useReactFlow();
  const [vocabulary, setVocabulary] = useState<BlueprintVocabulary | null>(null);
  const [graph, setGraph] = useState<BlueprintGraph | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [filters, setFilters] = useState<Filters>(defaultFilters);
  const [editMode, setEditMode] = useState(false);
  const [selectedNode, setSelectedNode] = useState<number | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<number | null>(null);
  const [query, setQuery] = useState("");
  const [dialog, setDialog] = useState<Dialog>(null);
  const [scanning, setScanning] = useState(false);
  const emphasis = useMemo(() => new EmphasisStore(), []);
  const layoutVersion = useRef(0);
  const saving = useRef<Promise<void>>(Promise.resolve());

  const reload = useCallback(() => api.blueprint(projectId).then((g) => {
    setGraph(g);
    layoutVersion.current = g.layout.version;
  }).catch(setProblem), [api, projectId]);

  useEffect(() => {
    api.blueprintVocabulary().then(setVocabulary).catch(setProblem);
    reload();
  }, [api, reload]);

  const blocks = useMemo(() => (graph ? visibleBlocks(graph, filters) : []), [graph, filters]);
  const visibleIds = useMemo(() => new Set(blocks.map((b) => b.id)), [blocks]);
  const relations = useMemo(() => (graph ? visibleRelations(graph, visibleIds, filters) : []), [graph, visibleIds, filters]);
  const found = useMemo(() => search(blocks, query), [blocks, query]);
  const editable = editMode && !!graph && !graph.archived;

  // Fit the whole Blueprint once, when its blocks have first been measured.
  const initialized = useNodesInitialized();
  const fitted = useRef(false);
  useEffect(() => {
    if (initialized && !fitted.current && blocks.length > 0) {
      fitted.current = true;
      flow.fitView({ padding: 0.12, duration: 0 });
    }
  }, [initialized, blocks.length, flow]);

  // What the selection points at: the block and its relations, the rest dimmed.
  useEffect(() => {
    if (!graph) return;
    if (selectedNode !== null) {
      const touching = graph.edges.filter((e) => e.source === selectedNode || e.target === selectedNode);
      emphasis.focus([selectedNode, ...touching.map((e) => (e.source === selectedNode ? e.target : e.source))], touching.map((e) => e.id));
    } else if (selectedEdge !== null) {
      const e = graph.edges.find((x) => x.id === selectedEdge);
      emphasis.focus(e ? [e.source, e.target] : [], [selectedEdge]);
    } else {
      emphasis.clear();
    }
  }, [selectedNode, selectedEdge, graph, emphasis]);

  const visibleRef = useRef(visibleIds);
  visibleRef.current = visibleIds;
  const centerOn = useCallback(function center(id: number, zoom?: number, attempt = 0) {
    const n = flow.getNode(String(id));
    if (!n) {
      if (visibleRef.current.has(id) || attempt > 0) {
        // on the canvas in a moment (just created, or filters just reset): try again
        if (attempt < 10) window.setTimeout(() => center(id, zoom, attempt + 1), 100);
        return;
      }
      setNotice("Il blocco era nascosto dai filtri: li ho azzerati.");
      setFilters(defaultFilters());
      window.setTimeout(() => center(id, zoom, 1), 100);
      return;
    }
    flow.setCenter(n.position.x + (n.measured?.width ?? 250) / 2, n.position.y + (n.measured?.height ?? 90) / 2,
      { zoom: zoom ?? Math.max(flow.getZoom(), 1), duration: 450 });
  }, [flow]);

  const select = useCallback((id: number) => {
    setSelectedEdge(null);
    setSelectedNode(id);
    flow.setNodes((nodes) => nodes.map((n) => ({ ...n, selected: n.id === String(id) })));
  }, [flow]);

  // [USA → TaskService]: go there, select it, and flash the relation.
  const followChip = useCallback((targetId: number, edgeId: number) => {
    select(targetId);
    centerOn(targetId);
    window.setTimeout(() => emphasis.focus([targetId], [edgeId]), 30);
  }, [select, centerOn, emphasis]);

  const onSelect = useCallback(({ nodes, edge }: { nodes: number[]; edge: number | null }) => {
    if (nodes.length === 1) { setSelectedNode(nodes[0]); setSelectedEdge(null); }
    else if (edge !== null) { setSelectedEdge(edge); setSelectedNode(null); }
    else if (nodes.length === 0) { setSelectedNode(null); setSelectedEdge(null); }
    else { setSelectedNode(null); setSelectedEdge(null); }
  }, []);

  // Moves are saved one after the other, each with the layout's latest tag.
  const onMoved = useCallback((positions: Record<string, { x: number; y: number }>) => {
    saving.current = saving.current.then(async () => {
      try {
        const layout = await api.saveBlueprintLayout(projectId, layoutVersion.current, positions);
        layoutVersion.current = layout.version;
        setGraph((g) => (g ? { ...g, layout } : g));
      } catch (error) {
        setProblem(error);
        if (error instanceof ApiProblem && error.isStale) reload();
      }
    });
  }, [api, projectId, reload]);

  async function scan() {
    setScanning(true); setProblem(null); setNotice(null);
    try {
      const s = await api.scanBlueprint(projectId);
      setNotice(`Analisi completata: ${s.files} file, ${s.nodesDetected} componenti (${s.nodesAdded} nuovi, ${s.nodesMissing} non più presenti), `
        + `${s.edgesDetected} relazioni${s.unresolved ? `, ${s.unresolved} riferimenti non risolti` : ""} — ${s.languages}.`);
      fitted.current = false;
      await reload();
    } catch (error) {
      setProblem(error);
    } finally {
      setScanning(false);
    }
  }

  async function autoLayout() {
    if (!window.confirm("Riorganizzare tutti i blocchi con il layout automatico? Le posizioni scelte a mano verranno sostituite.")) return;
    try {
      const layout = await api.autoBlueprintLayout(projectId, layoutVersion.current);
      layoutVersion.current = layout.version;
      setGraph((g) => (g ? { ...g, layout } : g));
      window.setTimeout(() => flow.fitView({ padding: 0.12, duration: 400 }), 60);
    } catch (error) {
      setProblem(error);
      if (error instanceof ApiProblem && error.isStale) reload();
    }
  }

  const actions: InspectorActions = useMemo(() => ({
    focus: (nodes, edges = []) => { emphasis.focus(nodes, edges); if (nodes[0] !== undefined) centerOn(nodes[0]); },
    centerNeighbours: (id, direction) => {
      if (!graph) return;
      const ids = [id, ...neighbours(graph, id, direction)];
      const edges = graph.edges.filter((e) => (direction === "out" ? e.source === id : e.target === id)).map((e) => e.id);
      emphasis.focus(ids, edges);
      flow.fitView({ nodes: ids.map((i) => ({ id: String(i) })), duration: 450, padding: 0.35, maxZoom: 1.2 });
    },
    addRelationFrom: (id) => setDialog({ kind: "relation", source: id, target: null }),
    openFile: (id) => { const b = graph?.nodes.find((n) => n.id === id); if (b) setDialog({ kind: "file", block: b }); },
    openTask: onOpenTask,
    changed: () => { reload(); },
    proposalsChanged: () => { reload(); },
  }), [graph, emphasis, centerOn, flow, onOpenTask, reload]);

  if (!graph || !vocabulary) {
    return <div className="stack"><ProblemNote problem={problem} /><div className="muted">Carico il Blueprint…</div></div>;
  }
  const m = graph.metrics;
  const selectedRelation = selectedEdge !== null ? graph.edges.find((e) => e.id === selectedEdge) ?? null : null;
  const inspecting = selectedNode !== null || selectedRelation !== null;

  return (
    <div className="bp-root">
      <div className="bp-toolbar card">
        <div className="bp-modes" role="group" aria-label="Modalità">
          <button className={editMode ? "" : "is-on"} aria-pressed={!editMode} onClick={() => setEditMode(false)}>Lettura</button>
          <button className={editMode ? "is-on" : ""} aria-pressed={editMode} disabled={graph.archived}
                  title={graph.archived ? "Progetto archiviato: sola lettura" : "Modifica il Blueprint (mai il codice)"}
                  onClick={() => setEditMode(true)}>Modifica</button>
        </div>
        <div className="bp-search">
          <Icon name="search" size={14} />
          <input aria-label="Cerca un blocco" placeholder="Cerca blocco, classe, percorso…" value={query}
                 onChange={(e) => setQuery(e.target.value)}
                 onKeyDown={(e) => { if (e.key === "Enter" && found[0]) { followChip(found[0].id, -1); setQuery(""); } if (e.key === "Escape") setQuery(""); }} />
          {query && (
            <div className="bp-search-results" role="listbox">
              {found.length === 0 && <div className="muted" style={{ padding: 8 }}>Nessun blocco.</div>}
              {found.map((b) => (
                <button key={b.id} role="option" aria-selected={false} onClick={() => { followChip(b.id, -1); setQuery(""); }}>
                  <span className="bp-mono-sm" style={{ background: KIND_LOOK[b.kind].color }}>{KIND_LOOK[b.kind].mono}</span>
                  <span>{b.name}</span><span className="mono muted bp-ellipsis">{b.filePath}</span>
                </button>
              ))}
            </div>
          )}
        </div>
        <span className="spacer" />
        {isAdmin && projectName && graph.nodes.length > 0 && (
          <button className="btn btn-small btn-danger" onClick={() => setDeleting(true)} disabled={graph.archived}
                  title="Elimina blocchi, relazioni, disposizione e scansioni: mai il codice">
            <Icon name="trash" size={13} /> Elimina Blueprint</button>
        )}
        <button className="btn btn-small" onClick={() => flow.fitView({ padding: 0.12, duration: 400 })}>Adatta</button>
        <button className="btn btn-small" onClick={autoLayout} disabled={graph.archived || graph.nodes.length === 0}>Auto-layout</button>
        <button className="btn btn-small" onClick={() => setDialog({ kind: "proposals" })}>
          Proposte{graph.pendingProposals > 0 && <span className="badge badge-warn" style={{ marginLeft: 6 }}>{graph.pendingProposals}</span>}
        </button>
        <button className="btn btn-primary btn-small" onClick={scan} disabled={scanning || graph.archived || !graph.workspaceReady}
                title={graph.workspaceReady ? "Legge il codice del progetto: nulla viene eseguito o modificato" : "Prepara prima la cartella del progetto"}>
          <Icon name="refresh" size={13} /> {scanning ? "Analizzo…" : graph.lastScan ? "Rianalizza il codice" : "Analizza il codice"}
        </button>
      </div>

      <div className="bp-metrics" aria-label="Metriche del Blueprint">
        <Metric label="Blocchi" value={m.totalNodes} />
        <Metric label="File" value={m.files} />
        <Metric label="Classi" value={m.classes} />
        <Metric label="Endpoint" value={m.endpoints} />
        <Metric label="Entità DB" value={m.databaseEntities} />
        <Metric label="Test" value={m.tests} />
        <Metric label="Relazioni rilevate" value={m.detectedRelations} />
        <Metric label="Manuali" value={m.manualRelations} />
        <Metric label="Proposte" value={m.proposedRelations} />
        <Metric label="Non risolte" value={m.unresolvedRelations} warn={m.unresolvedRelations > 0} />
        {m.missingNodes > 0 && <Metric label="Non più nel codice" value={m.missingNodes} warn />}
      </div>

      <div className="bp-filters">
        <div className="bp-filter-row" role="group" aria-label="Filtra per livello">
          <FilterChip label="Tutti" on={filters.layers.length === 0} onClick={() => setFilters({ ...filters, layers: [] })} />
          {LAYERS.map((l) => <FilterChip key={l} label={LAYER_LABEL[l]} count={m.byLayer[l]} on={filters.layers.includes(l)}
                                          onClick={() => setFilters({ ...filters, layers: toggle(filters.layers, l) })} />)}
        </div>
        <div className="bp-filter-row" role="group" aria-label="Filtra per tipo">
          {FAMILIES.map((f) => <FilterChip key={f} label={FAMILY_LABEL[f]} count={m.byFamily[f]} on={filters.families.includes(f)}
                                           onClick={() => setFilters({ ...filters, families: toggle(filters.families, f) })} />)}
          <span className="bp-filter-sep" />
          {ALL_ORIGINS.map((o) => <FilterChip key={o} label={o === "DETECTED" ? "Rilevate" : o === "MANUAL" ? "Manuali" : "Proposte"}
                                              on={filters.origins.includes(o)} title={ORIGIN_LABEL[o]} line={o}
                                              onClick={() => setFilters({ ...filters, origins: toggle(filters.origins, o) })} />)}
          <label className="bp-check"><input type="checkbox" aria-label="Mostra i metodi espansi" checked={filters.showMethods}
                                             onChange={(e) => setFilters({ ...filters, showMethods: e.target.checked })} /> metodi</label>
          <label className="bp-check"><input type="checkbox" aria-label="Mostra i blocchi non più nel codice" checked={filters.showMissing}
                                             onChange={(e) => setFilters({ ...filters, showMissing: e.target.checked })} /> non più nel codice</label>
        </div>
      </div>

      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {notice && <div className="notice" role="status">{notice} <button className="bp-link" onClick={() => setNotice(null)}>ok</button></div>}
      {graph.archived && <div className="notice notice-warn">Progetto archiviato: il Blueprint si consulta ma non si modifica.</div>}

      <div className={inspecting ? "bp-stage has-inspector" : "bp-stage"}>
        <div className="bp-canvas card">
          {graph.nodes.length === 0 ? (
            <div className="bp-empty">
              <Icon name="graph" size={28} />
              <h2>Il Blueprint è vuoto</h2>
              <p className="muted">
                {graph.workspaceReady
                  ? "Analizza il codice: controller, service, repository, entità, endpoint, componenti React e test diventano blocchi collegati. Nulla viene eseguito."
                  : "La cartella del progetto non esiste ancora: preparala dalla Panoramica, oppure disegna il Blueprint a mano."}
              </p>
              <div className="row" style={{ justifyContent: "center" }}>
                {graph.workspaceReady && <button className="btn btn-primary" onClick={scan} disabled={scanning || graph.archived}>
                  {scanning ? "Analizzo…" : "Analizza il codice"}</button>}
                <button className="btn" disabled={graph.archived} onClick={() => { setEditMode(true); setDialog({ kind: "add" }); }}>+ Aggiungi blocco</button>
              </div>
            </div>
          ) : (
            <EmphasisContext.Provider value={emphasis}>
              <BlockActionsContext.Provider value={{ followChip }}>
                <BlueprintCanvas graph={graph} blocks={blocks} relations={relations} vocabulary={vocabulary} editMode={editable}
                                 emphasis={emphasis} onSelect={onSelect} onMoved={onMoved}
                                 onConnectRequest={(source, target) => setDialog({ kind: "relation", source, target })}
                                 onPaneClick={() => { setSelectedNode(null); setSelectedEdge(null); }} />
              </BlockActionsContext.Provider>
            </EmphasisContext.Provider>
          )}
          {editable && graph.nodes.length > 0 && (
            <button className="btn btn-primary bp-add" onClick={() => setDialog({ kind: "add" })}>+ Aggiungi blocco</button>
          )}
          {graph.lastScan && (
            <div className="bp-scaninfo muted">Ultima analisi {when(graph.lastScan.finishedAt)} · {graph.lastScan.languages}
              {editMode ? " · trascina da una porta destra a un blocco per creare una relazione" : ""}</div>
          )}
        </div>
        {selectedNode !== null && (
          <BlockInspector key={selectedNode} projectId={projectId} nodeId={selectedNode} vocabulary={vocabulary} editable={editable}
                          actions={actions} onClose={() => { setSelectedNode(null); flow.setNodes((n) => n.map((x) => ({ ...x, selected: false }))); }} />
        )}
        {selectedRelation && (
          <RelationInspector projectId={projectId} relation={selectedRelation} graph={graph} vocabulary={vocabulary}
                             editable={editable} actions={actions} onClose={() => setSelectedEdge(null)} />
        )}
      </div>

      {dialog?.kind === "add" && (
        <AddBlockDialog projectId={projectId} vocabulary={vocabulary} onClose={() => setDialog(null)}
                        at={centerOfView(flow) ?? freeSpot(graph.layout.positions)}
                        onCreated={(id) => { setDialog(null); reload().then(() => window.setTimeout(() => { select(id); centerOn(id); }, 80)); }} />
      )}
      {dialog?.kind === "relation" && (
        <RelationDialog projectId={projectId} vocabulary={vocabulary} blocks={graph.nodes} source={dialog.source} target={dialog.target}
                        onClose={() => setDialog(null)} onCreated={() => { setDialog(null); reload(); }} />
      )}
      {dialog?.kind === "proposals" && (
        <ProposalsDialog projectId={projectId} onClose={() => setDialog(null)} onChanged={reload}
                         onOpenTask={(id) => { setDialog(null); onOpenTask(id); }} />
      )}
      {dialog?.kind === "file" && <FileViewer projectId={projectId} block={dialog.block} onClose={() => setDialog(null)} />}
      {deleting && projectName && (
        <ConfirmDeletion title="Elimina Blueprint" subject={`il Blueprint di ${projectName}`} typedName={projectName}
                         loadPreview={loadDeletion} confirmLabel="Elimina definitivamente" onClose={() => setDeleting(false)}
                         intro={<>Stai per eliminare l'intero Blueprint di <strong>«{projectName}»</strong>. Il codice non viene toccato; i blocchi disegnati a mano e la disposizione non tornano.</>}
                         onConfirm={async () => {
                           await api.deleteBlueprint(projectId, layoutVersion.current, projectName);
                           setDeleting(false);
                           setSelectedNode(null);
                           setSelectedEdge(null);
                           setNotice("Blueprint eliminato. «Analizza il codice» lo ricostruisce da ciò che il codice dice.");
                           await reload();
                         }} />
      )}
    </div>
  );
}

function centerOfView(flow: ReturnType<typeof useReactFlow>): { x: number; y: number } | null {
  const pane = document.querySelector(".bp-canvas .react-flow");
  if (!pane) return null;
  const r = pane.getBoundingClientRect();
  const p = flow.screenToFlowPosition({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
  return { x: Math.round(p.x - 125), y: Math.round(p.y - 40) };
}

function Metric({ label, value, warn }: { label: string; value: number; warn?: boolean }) {
  return <div className={warn ? "bp-metric is-warn" : "bp-metric"}><strong>{value}</strong><span>{label}</span></div>;
}

function FilterChip({ label, on, onClick, count, title, line }: {
  label: string; on: boolean; onClick: () => void; count?: number; title?: string; line?: string;
}) {
  return (
    <button type="button" className={on ? "bp-fchip is-on" : "bp-fchip"} aria-pressed={on} onClick={onClick} title={title}>
      {line && <span className={`bp-fline is-${line.toLowerCase()}`} aria-hidden />}
      {label}{count !== undefined && <span className="bp-fcount">{count}</span>}
    </button>
  );
}
