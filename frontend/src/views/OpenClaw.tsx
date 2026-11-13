import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useApi, useIsAdmin } from "../context";
import type { OpenClawAgent, OpenClawMessage, OpenClawOverview, OpenClawSession } from "../api/types";
import { ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

const AVAILABILITY: Record<string, { label: string; dot: string }> = {
  AVAILABLE: { label: "Gateway acceso", dot: "dot-ok" },
  UNREACHABLE: { label: "Gateway spento", dot: "dot-danger" },
  NOT_CONFIGURED: { label: "Da configurare", dot: "dot-warn" },
  NOT_INSTALLED: { label: "Non installato", dot: "dot-off" },
};
/** How often the page asks again while OpenClaw is being read or is answering. */
const POLL_MS = 3000;

function when(at: string | null | undefined): string {
  if (!at) return "—";
  const date = new Date(at);
  const minutes = Math.round((Date.now() - date.getTime()) / 60000);
  if (minutes < 1) return "adesso";
  if (minutes < 60) return `${minutes} min fa`;
  if (minutes < 60 * 24) return `${Math.round(minutes / 60)} h fa`;
  return date.toLocaleDateString("it-IT", { day: "numeric", month: "short" });
}

function sessionName(session: OpenClawSession): string {
  if (session.console) return "Conversazione dalla console";
  if (session.taskId != null) return `Task #${session.taskId}`;
  if (session.label) return session.label;
  return session.key.split(":").slice(2).join(":") || session.key;
}

/**
 * ADR-045: OpenClaw inside the console. Its Control UI refuses to be framed (it sends
 * `X-Frame-Options: DENY`, by design), so this page brings here what the operator works
 * with -- the gateway, the agents, a conversation, the sessions -- and opens the full
 * Control UI in the browser, paired by OpenClaw itself. `openclaw` commands take up to a
 * minute: the page shows the last reading and asks again while one is under way.
 */
export function OpenClaw({ navigate }: { navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const isAdmin = useIsAdmin();
  const [overview, setOverview] = useState<OpenClawOverview | null>(null);
  const [messages, setMessages] = useState<OpenClawMessage[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const thread = useRef<HTMLDivElement | null>(null);

  const load = useCallback((refresh = false) => api.openClaw(refresh).then(setOverview), [api]);
  useEffect(() => {
    load().catch(setProblem);
    api.openClawMessages().then(setMessages).catch(() => setMessages([]));
  }, [api, load]);

  // A reading under way, or a Control UI being opened: ask again until it is done.
  const waiting = overview !== null && (overview.refreshing || overview.controlUi.state === "OPENING");
  useEffect(() => {
    if (!waiting) return;
    const timer = window.setTimeout(() => { load().catch(setProblem); }, POLL_MS);
    return () => window.clearTimeout(timer);
  }, [waiting, overview, load]);

  // An answer still coming: ask for it until it comes.
  // Keyed by the ids, not the array: another render (a reading, a keystroke) must not restart the wait.
  // The tick re-arms the wait after each ask, for an answer that is still coming.
  const running = messages.filter((m) => m.state === "RUNNING").map((m) => m.id).join(",");
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!running) return;
    const timer = window.setTimeout(() => {
      Promise.all(running.split(",").map((id) => api.openClawMessage(id)))
        .then((updated) => setMessages((all) => all.map((m) => updated.find((u) => u.id === m.id) ?? m)))
        .catch(setProblem)
        .finally(() => setTick((t) => t + 1));
    }, POLL_MS);
    return () => window.clearTimeout(timer);
  }, [api, running, tick]);

  const agents = overview?.agents ?? [];
  const agent: OpenClawAgent | null = agents.find((a) => a.id === selected) ?? agents.find((a) => a.isDefault) ?? agents[0] ?? null;
  const conversation = useMemo(() => messages.filter((m) => agent && m.agentId === agent.id), [messages, agent]);
  const busy = conversation.some((m) => m.state === "RUNNING");

  useEffect(() => {
    thread.current?.scrollTo?.({ top: thread.current.scrollHeight });
  }, [conversation.length, busy]);

  const send = () => {
    if (!agent || !draft.trim()) return;
    setProblem(null);
    api.sendOpenClawMessage(agent.id, draft.trim())
      .then((sent) => { setMessages((all) => [...all, sent]); setDraft(""); })
      .catch(setProblem);
  };
  const openTerminal = (sessionKey: string) => {
    setProblem(null);
    api.openOpenClawTerminal(sessionKey)
      .then(() => setNotice("Sessione aperta in Windows Terminal con «openclaw tui»."))
      .catch(setProblem);
  };
  const openControlUi = () => {
    setProblem(null);
    api.openOpenClawControlUi().then((controlUi) => setOverview((o) => o && { ...o, controlUi })).catch(setProblem);
  };
  const startGateway = () => {
    setStarting(true);
    setProblem(null);
    api.launch("openclaw-gateway")
      .then(() => setNotice("Avvio il gateway: la pagina lo vede appena risponde."))
      .catch(setProblem)
      .finally(() => { setStarting(false); window.setTimeout(() => { load().catch(setProblem); }, POLL_MS); });
  };

  if (!overview) {
    return (
      <div className="stack">
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {!problem && <p className="muted" role="status">Chiedo a OpenClaw come sta…</p>}
      </div>
    );
  }

  const state = AVAILABILITY[overview.availability] ?? AVAILABILITY.NOT_INSTALLED;
  const available = overview.availability === "AVAILABLE";
  const controlUi = overview.controlUi;

  return (
    <div className="stack oc">
      <div className="page-head">
        <div>
          <h1>OpenClaw</h1>
          <p>I tuoi agenti di OpenClaw dentro AI Company OS: parlaci, riprendi le loro sessioni, apri la Control UI completa.</p>
        </div>
        <div className="row oc-actions">
          <span className="row oc-state"><span className={`dot ${state.dot}`} /> {state.label}
            <span className="muted mono">{overview.gatewayUrl.replace(/^https?:\/\//, "")}</span></span>
          {available && (
            <button className="btn btn-small" disabled={overview.refreshing} onClick={() => load(true).catch(setProblem)}>
              <Icon name="refresh" size={13} /> {overview.refreshing ? "Leggo…" : "Aggiorna"}</button>
          )}
          {overview.availability === "UNREACHABLE" && (
            <button className="btn" disabled={!isAdmin || starting} onClick={startGateway}>
              <Icon name="play" size={13} /> {starting ? "Avvio…" : "Avvia il gateway"}</button>
          )}
          <button className="btn btn-primary" disabled={!isAdmin || !available || controlUi.state === "OPENING"} onClick={openControlUi}
                  title={isAdmin ? "OpenClaw la apre nel browser, già collegata" : "Aprirla è una decisione dell'amministratore"}>
            <Icon name="external" size={13} /> {controlUi.state === "OPENING" ? "Apro…" : "Apri la Control UI"}</button>
        </div>
      </div>

      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {notice && <div className="notice row" role="status"><Icon name="check" size={13} /> {notice}
        <span className="spacer" /><button className="btn btn-small" onClick={() => setNotice(null)}>Chiudi</button></div>}
      {controlUi.state === "OPENED" && <div className="notice row" role="status"><Icon name="check" size={13} /> {controlUi.detail}</div>}
      {controlUi.state === "FAILED" && <div className="notice notice-danger" role="status">{controlUi.detail}</div>}

      {!available && (
        <section className="card card-body">
          <h2>{state.label}</h2>
          <p>{overview.detail}</p>
          {overview.hint && <p className="oc-hint"><Icon name="chevron" size={12} /> {overview.hint}</p>}
        </section>
      )}

      {available && (
        <>
          <p className="muted oc-why">
            La Control UI di OpenClaw non si lascia mettere dentro un'altra pagina: lo impedisce di proposito, per sicurezza.
            Qui hai agenti, conversazione e sessioni; per impostazioni, automazioni e canali apri la Control UI.
            {overview.refreshedAt && <> Letto {when(overview.refreshedAt)}.</>}
            {overview.refreshing && <> <span role="status">Sto leggendo OpenClaw: i suoi comandi impiegano fino a un minuto.</span></>}
          </p>
          {overview.problem && (
            <div className="notice notice-warn" role="status">L'ultima lettura non è riuscita: {overview.problem}</div>
          )}

          <div className="oc-main">
            <section className="card oc-agents" aria-label="Agenti di OpenClaw">
              <div className="oc-section-head"><h2>Agenti</h2><span className="muted">{agents.length}</span></div>
              {agents.length === 0 && <p className="muted oc-empty">{overview.refreshing ? "Li sto leggendo…" : "Nessun agente."}</p>}
              <ul>
                {agents.map((a) => (
                  <li key={a.id}>
                    <button className="oc-agent" aria-current={agent?.id === a.id ? "true" : undefined} onClick={() => setSelected(a.id)}
                            title={`${a.name} (${a.id})`}>
                      <span className="oc-avatar" aria-hidden="true">{a.name.slice(0, 1).toUpperCase()}</span>
                      <span className="oc-agent-text">
                        <strong>{a.name}</strong>
                        <small className="muted mono">{a.id}</small>
                      </span>
                      {a.isDefault && <span className="badge">predefinito</span>}
                      {a.madeHere && <span className="badge badge-accent" title="Pubblicato da AI Company OS">da qui</span>}
                    </button>
                  </li>
                ))}
              </ul>
            </section>

            <section className="card oc-chat" aria-label={agent ? `Conversazione con ${agent.name}` : "Conversazione"}>
              {agent ? (
                <>
                  <div className="oc-section-head">
                    <div>
                      <h2>{agent.name}</h2>
                      {agent.aicosAgentId != null && (
                        <button className="link-button" onClick={() => navigate("agents", String(agent.aicosAgentId))}>
                          In AI Company OS: {agent.aicosAgentName ?? `agente ${agent.aicosAgentId}`}</button>
                      )}
                    </div>
                    <span className="spacer" />
                    <button className="btn btn-small" disabled={!isAdmin} onClick={() => openTerminal(`agent:${agent.id}:aicos-console`)}
                            title="La stessa conversazione, in «openclaw tui»">
                      <Icon name="terminal" size={13} /> Continua nel terminale</button>
                  </div>
                  <div className="oc-thread" ref={thread}>
                    {conversation.length === 0 && (
                      <p className="muted oc-empty">Scrivi un messaggio a {agent.name}. Lavora con i suoi strumenti di OpenClaw; la
                        conversazione resta in OpenClaw, nella sessione <code>aicos-console</code>.</p>
                    )}
                    {conversation.map((m) => (
                      <div key={m.id} className="oc-exchange">
                        <div className="oc-bubble oc-bubble-you"><p>{m.message}</p></div>
                        {m.state === "RUNNING" && (
                          <div className="oc-bubble oc-bubble-agent oc-thinking" role="status">{agent.name} sta lavorando…</div>
                        )}
                        {m.reply && <div className="oc-bubble oc-bubble-agent"><p>{m.reply}</p>
                          {(m.model || m.tokens != null) && <small className="muted">{[m.model, m.tokens != null ? `${m.tokens} token` : null]
                            .filter(Boolean).join(" · ")}</small>}</div>}
                        {m.state !== "RUNNING" && m.detail && (
                          <div className={m.state === "FAILED" ? "notice notice-danger" : "notice"}>{m.detail}</div>
                        )}
                      </div>
                    ))}
                  </div>
                  <form className="oc-composer" onSubmit={(e) => { e.preventDefault(); send(); }}>
                    <label className="sr-only" htmlFor="oc-message">Messaggio per {agent.name}</label>
                    <textarea id="oc-message" rows={3} value={draft} disabled={!isAdmin || busy} maxLength={20000}
                              placeholder={isAdmin ? `Scrivi a ${agent.name}… (Ctrl+Invio per inviare)` : "Scrivere agli agenti è dell'amministratore"}
                              onChange={(e) => setDraft(e.target.value)}
                              onKeyDown={(e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); } }} />
                    <button className="btn btn-primary" type="submit" disabled={!isAdmin || busy || !draft.trim()}>
                      {busy ? "In attesa…" : "Invia"}</button>
                  </form>
                </>
              ) : <p className="muted oc-empty">{overview.refreshing ? "Leggo gli agenti di OpenClaw…" : "OpenClaw non ha agenti."}</p>}
            </section>
          </div>

          <section className="card" aria-label="Sessioni di OpenClaw">
            <div className="oc-section-head"><h2>Sessioni recenti</h2><span className="muted">{overview.sessions.length}</span></div>
            <div className="table-scroll">
              <table className="oc-sessions">
                <thead><tr><th scope="col">Sessione</th><th scope="col">Agente</th><th scope="col">Modello</th>
                  <th scope="col">Token</th><th scope="col">Ultima attività</th><th /></tr></thead>
                <tbody>
                  {overview.sessions.map((s) => (
                    <tr key={s.key}>
                      <th scope="row"><span>{sessionName(s)}</span><small className="muted mono">{s.key}</small></th>
                      <td>{agents.find((a) => a.id === s.agentId)?.name ?? s.agentId}</td>
                      <td>{s.model ?? "—"}</td>
                      <td>{s.totalTokens != null ? s.totalTokens.toLocaleString("it-IT") : "—"}</td>
                      <td>{when(s.updatedAt)}</td>
                      <td><button className="btn btn-small" disabled={!isAdmin} onClick={() => openTerminal(s.key)}
                                  aria-label={`Riprendi ${sessionName(s)} nel terminale`}>
                        <Icon name="terminal" size={13} /> Riprendi</button></td>
                    </tr>
                  ))}
                  {overview.sessions.length === 0 && (
                    <tr><td colSpan={6} className="muted">{overview.refreshing ? "Le sto leggendo…" : "Nessuna sessione."}</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </div>
  );
}
