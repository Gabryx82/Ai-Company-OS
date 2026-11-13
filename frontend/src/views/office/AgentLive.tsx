import { useEffect, useRef, useState } from "react";
import { ApiProblem } from "../../api/client";
import type { Agent, Handoff, OpenCodeSession, Project, ProjectFileEntry, Run, Task } from "../../api/types";
import { useApi } from "../../context";
import { Icon } from "../../components/icons";
import { PriorityBadge } from "../../components/ui";
import { ago } from "../dashboard/Widgets";
import { changedLines, CodeView } from "./CodeView";
import type { DeskState } from "./OfficeScene";

const STATE_TEXT: Record<DeskState, string> = {
  working: "Al lavoro", waiting: "Ha task da iniziare", idle: "In pausa", off: "Disattivato",
};
const SESSION_TEXT: Record<OpenCodeSession["status"], string> = {
  NOT_FOLLOWED: "non seguita", UNREACHABLE: "OpenCode non risponde", WAITING: "in attesa di partire",
  BUSY: "sta lavorando", IDLE: "ferma, in attesa di istruzioni", RETRY: "riprova dopo un errore",
};

/** Calls `load` now and every `ms` while mounted and `enabled`; the latest answer wins. */
function usePoll<T>(load: (() => Promise<T>) | null, ms: number, deps: unknown[]): [T | null, unknown] {
  const [value, setValue] = useState<T | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  useEffect(() => {
    setValue(null);
    setProblem(null);
    if (!load) return;
    let alive = true;
    const tick = () => load().then((v) => { if (alive) { setValue(v); setProblem(null); } })
      .catch((e) => { if (alive) setProblem(e); });
    tick();
    const timer = window.setInterval(() => { if (!document.hidden) tick(); }, ms);
    return () => { alive = false; window.clearInterval(timer); };
  }, deps); // eslint-disable-line react-hooks/exhaustive-deps
  return [value, problem];
}

export function AgentLive({ agent, state, task, project, next, onOpenTask }: {
  agent: Agent; state: DeskState; task: Task | null; project: Project | null; next: Task[];
  onOpenTask: (task: Task) => void;
}) {
  const api = useApi();
  const [tab, setTab] = useState<"code" | "about">("code");
  const [picked, setPicked] = useState<string | null>(null);
  const [file, setFile] = useState<{ path: string; text: string | null; note: string | null; changed: Set<number> } | null>(null);
  const previous = useRef<{ path: string; text: string; modifiedAt: string | null } | null>(null);

  const taskId = task?.id ?? null;
  const projectId = task?.projectId ?? project?.id ?? null;
  const [runs] = usePoll<Run[]>(taskId ? () => api.runs(taskId) : null, 3000, [api, taskId]);
  const [handoffs] = usePoll<Handoff[]>(taskId ? () => api.handoffs(taskId) : null, 8000, [api, taskId]);
  const run = runs && runs.length ? runs.reduce((a, b) => (b.id > a.id ? b : a)) : null;
  const handoff = handoffs && handoffs.length ? handoffs.reduce((a, b) => (b.id > a.id ? b : a)) : null;
  const [session] = usePoll<OpenCodeSession>(handoff?.followable ? () => api.handoffSession(handoff.id) : null, 4000,
    [api, handoff?.id, handoff?.followable]);
  const [files, filesProblem] = usePoll<ProjectFileEntry[]>(projectId && state !== "off" ? () => api.recentFiles(projectId, 6) : null,
    3000, [api, projectId, state]);
  const missingEndpoint = filesProblem instanceof ApiProblem && filesProblem.status === 404;
  const noFolder = filesProblem instanceof ApiProblem && (filesProblem.status === 409 || filesProblem.status === 503);

  useEffect(() => { setPicked(null); setFile(null); previous.current = null; }, [agent.id]);

  const shown = files?.find((f) => f.path === picked) ?? files?.[0] ?? null;
  const shownStamp = shown ? `${shown.path}@${shown.modifiedAt}` : null;
  useEffect(() => {
    if (!shown || !projectId) return;
    let alive = true;
    api.browseFile(projectId, shown.path).then((view) => {
      if (!alive) return;
      if (view.kind !== "TEXT" || view.content === null) {
        setFile({ path: shown.path, text: null, note: view.reason ?? "Questo file non è testo da mostrare.", changed: new Set() });
        return;
      }
      const before = previous.current && previous.current.path === shown.path ? previous.current.text : null;
      setFile({ path: shown.path, text: view.content, note: null, changed: changedLines(before, view.content) });
      previous.current = { path: shown.path, text: view.content, modifiedAt: shown.modifiedAt };
    }).catch(() => { if (alive) setFile({ path: shown.path, text: null, note: "Il file non si può leggere adesso.", changed: new Set() }); });
    return () => { alive = false; };
  }, [api, projectId, shownStamp]); // eslint-disable-line react-hooks/exhaustive-deps

  const running = run && (run.status === "RUNNING" || run.status === "QUEUED");
  const answered = run && run.status === "SUCCEEDED" && run.output ? run.output : null;

  return (
    <section className="card agent-live" aria-label={`Cosa sta facendo ${agent.name}`}>
      <header className="agent-live-head">
        <span className={`agent-live-avatar is-${state}`}><Icon name="agents" size={18} /></span>
        <div className="agent-live-title">
          <h2>{agent.name}</h2>
          <small>{agent.role}</small>
        </div>
        <span className={`badge ${state === "working" ? "badge-accent" : state === "waiting" ? "badge-warn" : ""}`}>
          <span className={`dot ${state === "working" ? "dot-ok" : state === "waiting" ? "dot-warn" : "dot-off"}`} />{STATE_TEXT[state]}
        </span>
        <span className="spacer" />
        <div className="tabs" role="tablist" aria-label="Vista">
          <button className="tab" role="tab" aria-selected={tab === "code"} onClick={() => setTab("code")}>Codice in tempo reale</button>
          <button className="tab" role="tab" aria-selected={tab === "about"} onClick={() => setTab("about")}>Cosa sta facendo</button>
        </div>
      </header>

      {task ? (
        <button className="agent-live-task" onClick={() => onOpenTask(task)}>
          <span className="mono">#{task.id}</span><strong>{task.title}</strong>
          <PriorityBadge priority={task.priority} />
          {project && <span className="muted">{project.name}</span>}
          <span className="spacer" /><span className="muted">Apri la task</span><Icon name="chevron" size={14} />
        </button>
      ) : (
        <p className="agent-live-empty">
          {state === "off" ? "Questo agente è disattivato: riattivalo dalla pagina Agenti per assegnargli lavoro."
            : "Nessuna task in corso per questo agente."}
          {next.length > 0 && ` Ne ha ${next.length} da iniziare.`}
        </p>
      )}

      {tab === "code" && task && (
        <div className="agent-live-body">
          {running && (
            <div className="engine-live">
              <span className="engine-pulse" aria-hidden="true" />
              <div>
                <strong>Il motore AI sta scrivendo la risposta</strong>
                <small>{run!.requestedModel ?? run!.servedModel ?? "modello predefinito"} · avviata {ago(run!.startedAt ?? run!.createdAt)}.
                  Il testo arriva intero alla fine dell'esecuzione.</small>
              </div>
            </div>
          )}
          {missingEndpoint && (
            <div className="notice notice-warn">Per vedere i file in tempo reale serve la versione aggiornata del server: riavvia AI Company OS.</div>
          )}
          {noFolder && <p className="widget-note">La cartella del progetto non è configurata o non esiste ancora: non c'è codice da seguire.</p>}
          {files && files.length > 0 && (
            <div className="live-files">
              <div className="live-file-list" role="listbox" aria-label="File modificati di recente">
                <div className="live-file-list-head">
                  <span>File modificati di recente</span>
                  {picked && <button className="link-button" onClick={() => setPicked(null)}>Segui l'ultimo</button>}
                </div>
                {files.map((f) => (
                  <button key={f.path} role="option" aria-selected={shown?.path === f.path}
                          className={`live-file${shown?.path === f.path ? " is-on" : ""}`} onClick={() => setPicked(f.path)}>
                    <Icon name="file" size={14} />
                    <span className="live-file-name" title={f.path}>{f.name}<small>{f.path}</small></span>
                    <span className="live-file-when">{f.modifiedAt ? ago(f.modifiedAt) : ""}</span>
                  </button>
                ))}
              </div>
              <div className="live-code">
                {file && file.text !== null && (
                  <>
                    <div className="live-code-head">
                      <span className="mono">{file.path}</span>
                      {file.changed.size > 0 && <span className="badge badge-accent">{file.changed.size} righe nuove</span>}
                      <span className="spacer" /><span className="legend-inline"><i className="is-comment" />commenti <i className="is-new" />appena scritte</span>
                    </div>
                    <CodeView text={file.text} changed={file.changed} label={`Contenuto di ${file.path}`} />
                  </>
                )}
                {file && file.text === null && <p className="widget-note" style={{ padding: 16 }}>{file.note}</p>}
                {!file && <p className="widget-note" style={{ padding: 16 }}>Apro il file…</p>}
              </div>
            </div>
          )}
          {files && files.length === 0 && !answered && (
            <p className="widget-note">Nessun file nella cartella del progetto, per ora.</p>
          )}
          {!projectId && !answered && !running && (
            <p className="widget-note">Questa task non appartiene a un progetto con una cartella: non c'è codice da seguire.</p>
          )}
          {answered && (!files || files.length === 0) && (
            <div className="live-code">
              <div className="live-code-head"><span>Ultima risposta del motore AI</span><span className="spacer" />
                <span className="muted">{run!.servedModel} · {ago(run!.finishedAt ?? run!.createdAt)}</span></div>
              <CodeView text={answered} label="Risposta del motore AI" />
            </div>
          )}
        </div>
      )}

      {tab === "about" && task && (
        <div className="agent-live-body about">
          <div>
            <h3>La task</h3>
            <p className="about-text">{task.description?.trim() || "La task non ha una descrizione: il titolo dice cosa fare."}</p>
          </div>
          <dl className="about-facts">
            {handoff && <><dt>Affidata a</dt><dd>{handoff.target} · {ago(handoff.createdAt)}</dd></>}
            {session && <><dt>Sessione</dt><dd>{SESSION_TEXT[session.status]}{session.files > 0 &&
              ` · ${session.files} file, +${session.additions} −${session.deletions}`}{session.updatedAt && ` · ${ago(session.updatedAt)}`}</dd></>}
            {run && <><dt>Motore AI</dt><dd>{run.status === "SUCCEEDED" ? "risposta completata" : run.status === "FAILED" ? "esecuzione non riuscita"
              : run.status === "RUNNING" ? "in esecuzione" : "in coda"} · {ago(run.createdAt)}</dd></>}
            {!handoff && !run && <><dt>Esecuzione</dt><dd>Non ancora avviata: aprila dalla task per affidarla all'agente.</dd></>}
          </dl>
          {session?.lastAnswer && (
            <div>
              <h3>Ultimo messaggio dell'agente</h3>
              <p className="about-text">{session.lastAnswer}</p>
            </div>
          )}
          {answered && (
            <div>
              <h3>Ultima risposta del motore AI</h3>
              <CodeView text={answered} label="Risposta del motore AI" />
            </div>
          )}
        </div>
      )}

      {!task && next.length > 0 && (
        <ul className="agent-live-next">
          {next.slice(0, 4).map((t) => (
            <li key={t.id}><button className="link-button" onClick={() => onOpenTask(t)}>#{t.id} {t.title}</button>
              <PriorityBadge priority={t.priority} /></li>
          ))}
        </ul>
      )}
    </section>
  );
}
