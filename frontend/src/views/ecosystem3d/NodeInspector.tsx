import type { ReactNode } from "react";
import { BindingChain, BILLING_LABEL, DELIVERY_LABEL } from "../../components/BindingChain";
import { Icon } from "../../components/icons";
import { ORIGIN_LABEL } from "../Agents";
import type { Navigate } from "../../App";
import {
  CATEGORY, EDGE_LABEL, LOCALITY_LABEL, agentId, type Category, type EcoGraph, type EcoNode, type EdgeKind, type VisibleGraph,
} from "./ecosystemModel";

/** An edge read from the owned end. */
const INCOMING_LABEL: Record<EdgeKind, string> = {
  subagent: "agente padre", "uses-model": "usato da", "served-by": "serve il modello", "runs-on": "lavora qui", equipped: "usato da",
  "uses-software": "usato da", prompt: "di", directive: "di", role: "di", capability: "di", offers: "offerto in", member: "nel cluster",
};

const AVAILABILITY_LABEL: Record<string, string> = {
  INSTALLED: "installato", RUNNING: "attivo", NOT_INSTALLED: "non installato", STOPPED: "fermo", UNKNOWN: "sconosciuto",
};

/**
 * What a selected node is, read-only, from the same data the rest of the console
 * edits: the agent's binding with the chain of the Agents tab, a resource with the
 * agents that use it, a prompt's text. Changes are made where they are made today --
 * the inspector links there instead of editing here.
 */
export function NodeInspector({ graph, visible, node, expanded, collapsed, mode, focus, navigate, onSelect, onClose, onToggleGroup,
                                onToggleAgent, onSingle, onEcosystem }: {
  graph: EcoGraph; visible: VisibleGraph; node: EcoNode; expanded: boolean; collapsed: boolean; mode: "ecosystem" | "agent";
  focus: number | null; navigate: Navigate; onSelect: (id: string) => void; onClose: () => void; onToggleGroup: (id: string) => void;
  onToggleAgent: (agent: number) => void; onSingle: (agent: number) => void; onEcosystem: () => void;
}) {
  const info = CATEGORY[node.category];
  const d = node.detail;
  const shown = visible.ids.has(node.id);

  return (
    <div className="card side-panel eco-inspector" aria-label={`Proprietà di ${node.label}`}>
      <div className="card-head">
        <span className="eco-dot eco-dot-lg" style={{ background: info.color }} aria-hidden="true" />
        <h2 style={{ minWidth: 0, overflowWrap: "anywhere" }}>{node.label}</h2>
        <span className="spacer" />
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi"><Icon name="close" size={14} /></button>
      </div>
      <div className="card-body stack">
        <div className="row">
          <span className="chip" style={{ color: info.color }}>{node.isGroup ? `Cluster · ${info.label}` : node.subtitle || info.label}</span>
          {node.status === "inactive" && <span className="badge">{d.type === "hub" ? "disattivato" : "inattivo"}</span>}
          {node.status === "warning" && <span className="badge badge-warn">da controllare</span>}
          {!shown && <span className="badge">non visibile ora</span>}
        </div>

        {d.type === "agent" && (() => {
          const a = d.agent;
          const counts = new Map<Category, number>();
          for (const e of graph.out.get(node.id) ?? []) {
            if (e.kind === "subagent") continue;
            const c = graph.byId.get(e.target)!.category;
            counts.set(c, (counts.get(c) ?? 0) + 1);
          }
          return (
            <>
              <div className="row">
                <span className="chip origin-badge">{ORIGIN_LABEL[a.origin]}</span>
                {a.status === "ACTIVE" ? <span className="badge badge-ok">attivo</span> : <span className="badge">inattivo</span>}
                {a.modified.length > 0 && <span className="badge badge-warn">{a.modified.length} modific{a.modified.length === 1 ? "a" : "he"}</span>}
              </div>
              <Line label="Ruolo">{a.role}</Line>
              <Line label="Specializzazione">{a.specialization}</Line>
              {a.description && <Line label="Descrizione">{a.description}</Line>}
              <BindingChain binding={a.binding} />
              <div>
                <div className="section-title">Cosa lo definisce</div>
                <div className="eco-counts">
                  {[...counts.entries()].sort((x, y) => y[1] - x[1]).map(([c, n]) => (
                    <span key={c} className="eco-count"><span className="eco-dot" style={{ background: CATEGORY[c].color }} />{CATEGORY[c].label} <b>{n}</b></span>
                  ))}
                  {counts.size === 0 && <span className="muted">Nessuna proprietà configurata.</span>}
                </div>
              </div>
              {(a.parent || a.children.length > 0) && (
                <div>
                  <div className="section-title">Gerarchia</div>
                  {a.parent && <div className="row">Sottoagente di <LinkTo id={agentId(a.parent.id)} graph={graph} onSelect={onSelect} /></div>}
                  {a.children.length > 0 && <div className="row">Sottoagenti: {a.children.map((c) => <LinkTo key={c.id} id={agentId(c.id)} graph={graph} onSelect={onSelect} />)}</div>}
                </div>
              )}
              <div className="row">
                {mode === "agent" && focus === a.id
                  ? <button className="btn btn-small" onClick={onEcosystem}><Icon name="graph" size={13} /> Torna all'ecosistema</button>
                  : <button className="btn btn-small" onClick={() => onSingle(a.id)}><Icon name="agents" size={13} /> Vista agente singolo</button>}
                {mode === "ecosystem" && (
                  <button className="btn btn-small" onClick={() => onToggleAgent(a.id)}>{collapsed ? "Espandi proprietà" : "Comprimi proprietà"}</button>
                )}
                <button className="btn btn-small btn-primary" onClick={() => navigate("agents", String(a.id))}>
                  <Icon name="external" size={13} /> Apri nella scheda Agenti</button>
              </div>
            </>
          );
        })()}

        {d.type === "model" && (
          <>
            <Line label="Chiave"><span className="mono">{d.key}</span></Line>
            <Line label="Provider">{d.providerName ?? "—"}{d.locality && <> · <span className="badge badge-accent">{LOCALITY_LABEL[d.locality]}</span></>}</Line>
            {d.role && <Line label="Ruolo del modello">{d.role.toLowerCase()}</Line>}
            <Line label="Ciclo di vita">{d.lifecycle === "DEPRECATED"
              ? <span className="badge badge-warn">superato{d.replacedBy ? ` da ${d.replacedBy}` : ""}</span> : (d.lifecycle ?? "—").toLowerCase()}</Line>
            {!d.catalogued && <div className="notice notice-warn">Il modello non è nel catalogo.</div>}
            <Line label="Eseguibile dall'AI Engine">{d.engineRunnable ? "sì" : "no"}</Line>
            <button className="btn btn-small" onClick={() => navigate("models")}><Icon name="models" size={13} /> Apri Modelli LLM</button>
          </>
        )}

        {d.type === "provider" && (
          <>
            {d.locality && <Line label="Dove gira"><span className="badge badge-accent">{LOCALITY_LABEL[d.locality]}</span></Line>}
            {d.billing && <Line label="Costo">{BILLING_LABEL[d.billing] ?? d.billing}</Line>}
            {d.status && <Line label="Stato">{d.status.toLowerCase()}</Line>}
          </>
        )}

        {d.type === "target" && (
          <>
            {d.delivery && <Line label="Come riceve il lavoro">{DELIVERY_LABEL[d.delivery]}</Line>}
            {d.howItWorks && <div className="muted" style={{ fontSize: 12.5 }}>{d.howItWorks}</div>}
            {d.contextFile && <Line label="File di contesto"><span className="mono">{d.contextFile}</span></Line>}
            {d.availability && <Line label="Disponibilità">{AVAILABILITY_LABEL[d.availability] ?? d.availability.toLowerCase()}</Line>}
            {d.software && <button className="btn btn-small" onClick={() => navigate("software", d.software!)}><Icon name="software" size={13} /> Apri nel Software Hub</button>}
          </>
        )}

        {d.type === "resource" && (
          <>
            <Line label="Chiave"><span className="mono">{d.key}</span></Line>
            {d.description && <div style={{ fontSize: 13 }}>{d.description}</div>}
            {d.configured && (
              <div className="notice"><Icon name="warning" size={13} /> Ha una configurazione: non è mostrata qui, perché può contenere credenziali.</div>
            )}
            {(node.category === "skill" || node.category === "knowledge") && (
              <button className="btn btn-small" onClick={() => navigate("knowledge")}><Icon name="knowledge" size={13} /> Apri nel Knowledge Hub</button>
            )}
          </>
        )}

        {d.type === "software" && (
          <>
            <Line label="Chiave"><span className="mono">{d.key}</span></Line>
            <button className="btn btn-small" onClick={() => navigate("software", d.key)}><Icon name="software" size={13} /> Apri nel Software Hub</button>
          </>
        )}

        {d.type === "text" && (
          <>
            <Line label="Agente"><LinkTo id={agentId(d.agentId)} graph={graph} onSelect={onSelect} /></Line>
            <pre className="eco-text">{d.text}</pre>
            <div className="muted" style={{ fontSize: 12 }}>Si modifica nella scheda Agenti, sezione «Prompt e direttive».</div>
          </>
        )}

        {d.type === "value" && <div style={{ fontSize: 13 }}>{d.value}</div>}

        {d.type === "hub" && (
          <>
            <Line label="Applicazione">{d.appName}</Line>
            {d.skill.description && <div style={{ fontSize: 13 }}>{d.skill.description}</div>}
            <Line label="Categoria">{d.skill.categoryLabel}</Line>
            {d.skill.plugin && <Line label="Plugin">{d.skill.plugin}{d.skill.pluginVersion ? ` ${d.skill.pluginVersion}` : ""}</Line>}
            {d.skill.marketplace && <Line label="Marketplace">{d.skill.marketplace}</Line>}
            {d.skill.version && <Line label="Versione">{d.skill.version}</Line>}
            {d.skill.updateAvailable && <span className="badge badge-warn">aggiornamento disponibile</span>}
            {d.skill.details && Object.keys(d.skill.details).length > 0 && (
              <dl className="eco-details">
                {Object.entries(d.skill.details).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
              </dl>
            )}
            <button className="btn btn-small" onClick={() => navigate("knowledge")}><Icon name="knowledge" size={13} /> Apri nel Knowledge Hub</button>
          </>
        )}

        {d.type === "group" && (
          <>
            <Line label="Applicazione">{d.appName}</Line>
            <div style={{ fontSize: 13 }}>{d.count} element{d.count === 1 ? "o" : "i"} che gli agenti ricevono lavorando in {d.appName}.</div>
            <button className="btn btn-small btn-primary" onClick={() => onToggleGroup(node.id)}>
              {expanded ? "Comprimi il cluster" : "Espandi il cluster"}</button>
          </>
        )}

        <Connections graph={graph} visible={visible} node={node} onSelect={onSelect} />
      </div>
    </div>
  );
}

function Line({ label, children }: { label: string; children: ReactNode }) {
  return <div className="eco-line"><span className="muted">{label}</span><span>{children}</span></div>;
}

function LinkTo({ id, graph, onSelect }: { id: string; graph: EcoGraph; onSelect: (id: string) => void }) {
  const node = graph.byId.get(id);
  if (!node) return null;
  return (
    <button className="eco-link" onClick={() => onSelect(id)}>
      <span className="eco-dot" style={{ background: CATEGORY[node.category].color }} />{node.label}
    </button>
  );
}

/** Every edge of the node, by what it means, both ways; a click selects the other end. */
function Connections({ graph, visible, node, onSelect }: { graph: EcoGraph; visible: VisibleGraph; node: EcoNode; onSelect: (id: string) => void }) {
  const incoming = (graph.into.get(node.id) ?? []).map((e) => ({ id: e.source, label: INCOMING_LABEL[e.kind] }));
  const outgoing = (graph.out.get(node.id) ?? []).map((e) => ({ id: e.target, label: EDGE_LABEL[e.kind] }));
  const all = [...incoming, ...outgoing];
  if (all.length === 0) return null;
  const limit = 60;
  return (
    <div>
      <div className="section-title">Connessioni <span className="muted">({all.length})</span></div>
      <ul className="eco-connections">
        {all.slice(0, limit).map((c) => {
          const other = graph.byId.get(c.id)!;
          return (
            <li key={`${c.label}:${c.id}`}>
              <button className="eco-link" onClick={() => onSelect(c.id)} title={other.subtitle}>
                <span className="eco-dot" style={{ background: CATEGORY[other.category].color }} />
                <span className="eco-link-label">{other.label}</span>
                <small className="muted">{c.label}{visible.ids.has(c.id) ? "" : " · nascosto"}</small>
              </button>
            </li>
          );
        })}
      </ul>
      {all.length > limit && <div className="muted" style={{ fontSize: 12 }}>…e altre {all.length - limit}.</div>}
    </div>
  );
}
