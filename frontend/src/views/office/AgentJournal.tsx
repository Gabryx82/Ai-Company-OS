// PHASE 37 (ADR-047): what each agent did, step by step -- the moments the control plane
// records (a task handed to a tool, a run, a review) and the steps the agent writes in its
// diary while it works. Read every few seconds while the office is in view.
import { useEffect, useRef, useState } from "react";
import type { Agent, JournalEntry, JournalKind } from "../../api/types";
import type { ControlPlane } from "../../api/client";
import { ago } from "../dashboard/Widgets";

/** How often the office reads the journal again. */
export const JOURNAL_MS = 4000;
/** A step older than this is no longer shown on the agent's tag. */
const FRESH_MS = 30 * 60 * 1000;

const TONE: Record<JournalKind, "start" | "step" | "run" | "done" | "warn" | "fail"> = {
  DELIVERED: "start", STARTED: "start", STEP: "step", OUTCOME: "done",
  RUN_QUEUED: "run", RUN_STARTED: "run", RUN_SUCCEEDED: "run", RUN_FAILED: "fail",
  ACCEPTED: "done", CHANGES_REQUESTED: "warn", COMPLETED: "done", STOPPED: "warn", REOPENED: "warn",
};
const WHO: Partial<Record<JournalKind, string>> = { STEP: "dal diario dell'agente", OUTCOME: "dall'agente" };

/** The office's journal, read now and every few seconds; an unchanged answer changes nothing. */
export function useJournal(api: ControlPlane): { entries: JournalEntry[] | null; problem: unknown } {
  const [entries, setEntries] = useState<JournalEntry[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const signature = useRef("");
  useEffect(() => {
    let alive = true;
    const read = () => api.journal(undefined, 300).then((list) => {
      if (!alive) return;
      setProblem(null);
      const next = list.map((e) => `${e.key}@${e.at}`).join("|");
      if (next === signature.current) return;
      signature.current = next;
      setEntries(list);
    }).catch((e) => { if (alive) { setProblem(e); setEntries((now) => now ?? []); } });
    read();
    const timer = window.setInterval(() => { if (!document.hidden) read(); }, JOURNAL_MS);
    return () => { alive = false; window.clearInterval(timer); };
  }, [api]);
  return { entries, problem };
}

/** Each agent's latest moment, when recent: what its tag in the office says under its name. */
export function latestSteps(entries: JournalEntry[] | null, now = Date.now()): Map<number, string> {
  const latest = new Map<number, string>();
  for (const e of entries ?? []) {
    if (e.agentId === null || latest.has(e.agentId) || !e.at) continue;
    if (now - new Date(e.at).getTime() > FRESH_MS) continue;
    latest.set(e.agentId, e.text);
  }
  return latest;
}

function clock(at: string): string {
  return new Date(at).toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

/** A journal, the most recent first. With `agents`, each moment says whose it is. */
export function JournalList({ entries, agents, onAgent, limit }: {
  entries: JournalEntry[]; agents?: Agent[]; onAgent?: (id: number) => void; limit?: number;
}) {
  const shown = limit ? entries.slice(0, limit) : entries;
  const name = (id: number | null) => (id === null ? null : agents?.find((a) => a.id === id)?.name ?? null);
  return (
    <ol className="journal" aria-label="Diario">
      {shown.map((e) => (
        <li key={e.key} className={`journal-entry is-${TONE[e.kind]}`}>
          <time className="journal-when" dateTime={e.at ?? undefined} title={e.at ? `${ago(e.at)}${e.approximate
            ? " · ora stimata: l'agente non l'ha scritta" : ""}` : "ora non nota"}>
            {e.at ? `${e.approximate ? "≈ " : ""}${clock(e.at)}` : "—"}
          </time>
          <span className="journal-mark" aria-hidden="true" />
          <span className="journal-what">
            <span className="journal-text">{e.text}</span>
            <small>
              {agents && name(e.agentId) && (onAgent && e.agentId !== null
                ? <button type="button" className="link-button" onClick={() => onAgent(e.agentId!)}>{name(e.agentId)}</button>
                : <span>{name(e.agentId)}</span>)}
              <span className="mono">{e.task}</span>
              {WHO[e.kind] && <span>{WHO[e.kind]}</span>}
              {e.actor && <span>{e.actor}</span>}
            </small>
          </span>
        </li>
      ))}
    </ol>
  );
}

/** The office's diary: every agent's moments together, the most recent first. */
export function OfficeJournal({ entries, agents, problem, onAgent }: {
  entries: JournalEntry[] | null; agents: Agent[]; problem: unknown; onAgent: (id: number) => void;
}) {
  return (
    <section className="card widget" aria-label="Diario dell'ufficio">
      <div className="widget-head">
        <div><h2>Diario dell'ufficio</h2><small>Ogni passo degli agenti, man mano che arriva</small></div>
      </div>
      {entries === null && <p className="widget-note">Leggo il diario…</p>}
      {entries !== null && entries.length === 0 && (
        <p className="widget-note">{problem ? "Il diario non si può leggere adesso: riavvia AI Company OS se il server è di una versione precedente."
          : "Ancora nessun passo: consegna una task a un agente e lo vedrai cominciare qui."}</p>
      )}
      {entries !== null && entries.length > 0 && <JournalList entries={entries} agents={agents} onAgent={onAgent} limit={12} />}
    </section>
  );
}
