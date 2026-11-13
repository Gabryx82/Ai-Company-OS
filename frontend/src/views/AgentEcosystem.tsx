import { useCallback, useEffect, useState } from "react";
import { useApi } from "../context";
import type { AgentSyncCell, AgentSyncOverview, AgentSyncReport, AgentSyncState, AgentSyncTool } from "../api/types";
import { ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

const STATE: Record<AgentSyncState, { label: string; tone: string }> = {
  IN_SYNC: { label: "allineato", tone: "badge badge-ok" },
  BEHIND: { label: "da aggiornare", tone: "badge badge-warn" },
  NOT_PUBLISHED: { label: "da pubblicare", tone: "badge badge-accent" },
  NOT_APPLICABLE: { label: "non adatto", tone: "badge" },
  MISSING: { label: "non c'è più", tone: "badge badge-danger" },
  ADOPTED: { label: "è suo", tone: "badge" },
  UNAVAILABLE: { label: "—", tone: "badge" },
  READING: { label: "lettura…", tone: "badge eco-reading" },
};
/** ADR-046: while a tool is being read, the page asks again this often. */
const POLL_MS = 3000;

function readAt(at: string | null): string {
  if (!at) return "";
  return new Date(at).toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" });
}
const AVAILABILITY: Record<string, { label: string; tone: string }> = {
  AVAILABLE: { label: "pronto", tone: "badge badge-ok" },
  NOT_INSTALLED: { label: "non installato", tone: "badge" },
  NOT_CONFIGURED: { label: "da configurare", tone: "badge badge-warn" },
  UNREACHABLE: { label: "non risponde", tone: "badge badge-danger" },
};
const OUTCOME: Record<string, { label: string; tone: string }> = {
  PUBLISHED: { label: "pubblicato", tone: "badge badge-ok" }, UPDATED: { label: "aggiornato", tone: "badge badge-ok" },
  UNCHANGED: { label: "già allineato", tone: "badge" }, SKIPPED: { label: "saltato", tone: "badge" },
  FAILED: { label: "non riuscito", tone: "badge badge-danger" },
};
const WRITABLE: AgentSyncState[] = ["NOT_PUBLISHED", "BEHIND", "MISSING"];

/**
 * PHASE 37 (ADR-043): one ecosystem.
 * ADR-046: the matrix comes at once -- OpenClaw answers with its last reading (its command takes up to a minute)
 * and a tool being read shows "lettura…" until the page, asking again every few seconds, has it. The agents are made here; this panel says how each
 * stands in OpenClaw, Open WebUI and LiteLLM, and brings the tools up to
 * date when an administrator asks. Nothing is written until a button is pressed.
 */
export function AgentEcosystem({ isAdmin, onAgentsChanged, onOpenAgent }: {
  isAdmin: boolean; onAgentsChanged: () => void; onOpenAgent: (id: number) => void;
}) {
  const api = useApi();
  const [overview, setOverview] = useState<AgentSyncOverview | null>(null);
  const [report, setReport] = useState<AgentSyncReport | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [problem, setProblem] = useState<unknown>(null);

  const reload = useCallback(() => api.agentSync().then(setOverview), [api]);
  useEffect(() => { reload().catch(setProblem); }, [reload]);

  // A tool being read: ask again until it is read.
  const reading = overview?.tools.some((t) => t.refreshing) ?? false;
  useEffect(() => {
    if (!reading) return;
    const timer = window.setTimeout(() => { reload().catch(setProblem); }, POLL_MS);
    return () => window.clearTimeout(timer);
  }, [reading, overview, reload]);

  const run = (label: string, work: () => Promise<unknown>) => {
    setBusy(label);
    setDone(null);
    setProblem(null);
    work().then(() => reload()).catch(setProblem).finally(() => setBusy(null));
  };
  const publish = (label: string, selection: { agentIds?: number[]; tools?: string[] }) =>
    run(label, () => api.publishAgents(selection).then(setReport));
  const bringIn = (tool: string, externalIds?: string[]) => run("import", () => api.importAgents(tool, externalIds).then((imported) => {
    setDone(imported.length === 1 ? `«${imported[0].name}» ora è anche un agente di AI Company OS.`
      : `${imported.length} agenti portati in AI Company OS.`);
    onAgentsChanged();
  }));
  const takeOut = (tool: AgentSyncTool, agentId: number, name: string, cell: AgentSyncCell) => {
    const what = cell.state === "ADOPTED"
      ? `Scollegare «${name}» da ${tool.name}? In ${tool.name} resta tutto com'è.`
      : `Togliere «${name}» da ${tool.name}? Quello che è stato pubblicato lì viene rimosso.`;
    if (window.confirm(what)) run(`out-${tool.key}-${agentId}`, () => api.unpublishAgent(tool.key, agentId));
  };

  if (!overview) {
    return (
      <div className="stack">
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {!problem && <p className="muted" role="status">Chiedo agli strumenti come stanno i tuoi agenti…</p>}
      </div>
    );
  }

  const tools = overview.tools;
  const ready = tools.filter((t) => t.availability === "AVAILABLE");
  const writing = busy !== null;

  return (
    <div className="stack eco">
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {done && <div className="notice row" role="status"><Icon name="check" size={13} /> {done}</div>}

      <section className="card card-body eco-head">
        <div>
          <h2>Un solo ecosistema</h2>
          <p className="muted">Gli agenti nascono qui. Da qui arrivano agli strumenti che usi, ognuno nella forma che quello strumento ha per un agente.</p>
        </div>
        <button className="btn btn-primary" disabled={!isAdmin || writing || overview.pending === 0 || ready.length === 0}
                onClick={() => publish("all", {})}
                title={isAdmin ? undefined : "Scrivere negli altri strumenti è una decisione dell'amministratore"}>
          <Icon name="refresh" size={14} />{" "}
          {busy === "all" ? "Sincronizzo…" : overview.pending === 0 ? "Tutto allineato" : `Sincronizza tutto (${overview.pending})`}
        </button>
      </section>
      {!isAdmin && <p className="muted">Puoi vedere lo stato; per scrivere negli strumenti serve un amministratore.</p>}

      <ul className="eco-tools">
        {tools.map((tool) => (
          <li key={tool.key} className="card">
            <div className="row">
              <strong>{tool.name}</strong>
              <span className={AVAILABILITY[tool.availability]?.tone ?? "badge"}>{AVAILABILITY[tool.availability]?.label ?? tool.availability}</span>
            </div>
            <p className="eco-shape">Qui diventa {tool.shape}.</p>
            <p className="muted">{tool.detail}</p>
            {tool.hint && <p className="eco-hint"><Icon name="chevron" size={12} /> {tool.hint}</p>}
            {tool.availability === "AVAILABLE" && (tool.refreshing || tool.checkedAt) && (
              <p className="eco-read muted" role={tool.refreshing ? "status" : undefined}>
                {tool.refreshing
                  ? tool.checkedAt ? `Letto alle ${readAt(tool.checkedAt)}, sto rileggendo…` : "Sto leggendo i suoi agenti…"
                  : `Letto alle ${readAt(tool.checkedAt)}`}
              </p>
            )}
          </li>
        ))}
      </ul>

      {report && (
        <section className="card card-body" aria-label="Esito della sincronizzazione">
          <div className="row"><h3>Esito: {report.written} scritti{report.failed > 0 ? `, ${report.failed} non riusciti` : ""}</h3>
            <span className="spacer" /><button className="btn btn-small" onClick={() => setReport(null)}>Chiudi</button></div>
          <ul className="eco-report">
            {report.results.filter((r) => r.outcome !== "UNCHANGED").map((r) => (
              <li key={`${r.agentId}:${r.tool}`}>
                <span className={OUTCOME[r.outcome]?.tone ?? "badge"}>{OUTCOME[r.outcome]?.label ?? r.outcome}</span>
                <span><strong>{r.agent}</strong> → {tools.find((t) => t.key === r.tool)?.name ?? r.tool}: {r.detail}
                  {r.notes.length > 0 && <ul>{r.notes.map((n) => <li key={n} className="muted">{n}</li>)}</ul>}</span>
              </li>
            ))}
            {report.results.every((r) => r.outcome === "UNCHANGED") && <li className="muted">Niente da scrivere: era già tutto allineato.</li>}
          </ul>
        </section>
      )}

      <section className="card">
        <div className="table-scroll">
          <table className="eco-matrix">
            <thead>
              <tr><th scope="col">Agente</th>{tools.map((t) => <th key={t.key} scope="col">{t.name}</th>)}<th /></tr>
            </thead>
            <tbody>
              {overview.agents.map((agent) => {
                const pending = agent.cells.some((c) => WRITABLE.includes(c.state));
                return (
                  <tr key={agent.agentId}>
                    <th scope="row">
                      <button className="link-button" onClick={() => onOpenAgent(agent.agentId)}>{agent.name}</button>
                      <small className="muted">{agent.role}</small>
                    </th>
                    {tools.map((tool) => {
                      const cell = agent.cells.find((c) => c.tool === tool.key);
                      if (!cell) return <td key={tool.key} />;
                      const removable = isAdmin && cell.externalId !== null && cell.state !== "UNAVAILABLE";
                      return (
                        <td key={tool.key}>
                          <span className={STATE[cell.state].tone} title={cell.detail ?? undefined}>{STATE[cell.state].label}</span>
                          {removable && (
                            <button className="eco-out" disabled={writing} onClick={() => takeOut(tool, agent.agentId, agent.name, cell)}
                                    aria-label={`${cell.state === "ADOPTED" ? "Scollega" : "Togli"} ${agent.name} da ${tool.name}`}>
                              <Icon name="close" size={11} />
                            </button>
                          )}
                          {cell.detail && cell.state !== "IN_SYNC" && cell.state !== "UNAVAILABLE" && (
                            <small className="eco-detail muted">{cell.detail}</small>
                          )}
                        </td>
                      );
                    })}
                    <td className="eco-act">
                      {pending && (
                        <button className="btn btn-small" disabled={!isAdmin || writing}
                                onClick={() => publish(`agent-${agent.agentId}`, { agentIds: [agent.agentId] })}>
                          {busy === `agent-${agent.agentId}` ? "…" : "Sincronizza"}
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
              {overview.agents.length === 0 && (
                <tr><td colSpan={tools.length + 2} className="muted">Nessun agente attivo: creane uno nella scheda Agenti.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {overview.importable.length > 0 && (
        <section className="card card-body">
          <div className="row">
            <div><h3>Agenti che gli strumenti hanno già</h3>
              <p className="muted">Portali qui per assegnare loro le task: restano dello strumento, con i loro file e la loro memoria.</p></div>
            <span className="spacer" />
            {overview.importable.length > 1 && (
              <button className="btn" disabled={!isAdmin || writing}
                      onClick={() => bringIn(overview.importable[0].tool)}>Porta qui tutti</button>
            )}
          </div>
          <ul className="eco-import">
            {overview.importable.map((external) => (
              <li key={`${external.tool}:${external.externalId}`}>
                <span><strong>{external.name}</strong> <code>{external.externalId}</code>
                  <small className="muted"> · {tools.find((t) => t.key === external.tool)?.name ?? external.tool}
                    {external.description ? ` · ${external.description}` : ""}</small></span>
                <button className="btn btn-small" disabled={!isAdmin || writing}
                        onClick={() => bringIn(external.tool, [external.externalId])}>Porta qui</button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
