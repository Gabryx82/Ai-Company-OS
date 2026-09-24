import { useCallback, useEffect, useState } from "react";
import { useApi } from "../context";
import type { Handoff, Orchestration, Review, Task } from "../api/types";
import { ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";

const AVAILABILITY_TONE: Record<string, string> = {
  INSTALLED: "badge badge-ok", RUNNING: "badge badge-ok", WEB: "badge badge-ok", STOPPED: "badge badge-warn",
  NOT_INSTALLED: "badge", INCOMPATIBLE_HARDWARE: "badge badge-danger", UNKNOWN: "badge",
};

/**
 * The Master Orchestrator's view of one task (ADR-021 §3–5): what it would use
 * and why, the targets it can hand the task to, and the operator's review.
 *
 * Every action takes the task's tag through `act`, like the rest of the drawer.
 */
export function OrchestratorPanel({ task, busy, act }: {
  task: Task; busy: boolean; act: (mutation: (etag: string) => Promise<unknown>) => Promise<void>;
}) {
  const api = useApi();
  const [decision, setDecision] = useState<Orchestration | null>(null);
  const [handoffs, setHandoffs] = useState<Handoff[]>([]);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [note, setNote] = useState("");
  const [lastPrompt, setLastPrompt] = useState<{ text: string; copied: boolean } | null>(null);
  const [document, setDocument] = useState<string | null>(null);

  const load = useCallback(() => {
    api.orchestration(task.id).then(setDecision).catch(setProblem);
    api.handoffs(task.id).then(setHandoffs).catch(() => setHandoffs([]));
    api.reviews(task.id).then(setReviews).catch(() => setReviews([]));
  }, [api, task.id]);

  // Reload whenever the task changed (its tag moves with every write).
  useEffect(load, [load, task.status, task.agentId]);

  async function handoff(target: string) {
    await act(async (etag) => {
      const outcome = await api.handoff(task.id, etag, target);
      let copied = false;
      if (outcome.promptToClipboard) {
        try { await navigator.clipboard.writeText(outcome.prompt); copied = true; } catch { copied = false; }
      }
      setLastPrompt({ text: outcome.promptToClipboard ? outcome.prompt : "", copied });
    });
    load();
  }

  async function review(verdict: "ACCEPTED" | "CHANGES_REQUESTED") {
    await act((etag) => api.review(task.id, etag, verdict, note || undefined,
      handoffs[0] ? { handoffId: handoffs[0].id } : {}));
    setNote("");
    load();
  }

  async function showDocument() {
    if (!decision?.projectId || !task.documentPath) return;
    setDocument("…");
    api.readFile(decision.projectId, task.documentPath).then(setDocument).catch((e) => setDocument(String(e)));
  }

  if (!decision) {
    return problem ? <ProblemNote problem={problem} /> : null;
  }

  return (
    <div className="section">
      <div className="section-title"><Icon name="orchestrator" size={12} /> Master Orchestrator</div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {decision.code && (
        <div className="row">
          <span className="badge badge-accent">{decision.code}</span>
          {decision.phaseNumber != null && <span className="muted">Fase {decision.phaseNumber} — {decision.phaseTitle}</span>}
          <span className={decision.phaseApproved ? "badge badge-ok" : "badge badge-warn"}>
            {decision.phaseApproved ? "fase approvata" : "fase da approvare"}</span>
          <span className="spacer" />
          <button className="btn btn-small" onClick={showDocument}><Icon name="file" size={13} /> {task.documentPath}</button>
        </div>
      )}
      {document && <pre className="doc">{document}</pre>}
      {decision.blockers.length > 0 && (
        <div className="notice notice-warn">{decision.blockers.map((b) => <div key={b}>• {b}</div>)}</div>
      )}

      <dl className="kv">
        <dt>Agente</dt>
        <dd>{decision.agent.name ?? "—"} {decision.agent.role && <span className="muted">({decision.agent.role})</span>}
          <div className="muted" style={{ fontSize: 12 }}>{decision.agent.reason}</div></dd>
        <dt>Modello</dt>
        <dd className="mono">{decision.model.model ?? "default dell'engine"}
          <div className="muted" style={{ fontSize: 12, fontFamily: "inherit" }}>{decision.model.reason}</div></dd>
        <dt>Autonomia</dt><dd>{decision.autonomyLevel}</dd>
      </dl>

      {decision.software.length > 0 && (
        <div>
          <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>Software necessari</div>
          {decision.software.map((s) => (
            <span key={s.key} className={AVAILABILITY_TONE[s.availability] ?? "badge"} style={{ margin: 2 }} title={s.reason}>{s.name}</span>
          ))}
        </div>
      )}

      {decision.context.length > 0 && (
        <details>
          <summary>Contesto: {decision.context.filter((c) => c.exists).length}/{decision.context.length} file</summary>
          <ul style={{ margin: "6px 0", paddingLeft: 18 }}>
            {decision.context.map((c) => (
              <li key={c.path}><code>{c.path}</code> <span className="muted">— {c.why}{c.exists ? "" : " (non esiste ancora)"}</span></li>
            ))}
          </ul>
        </details>
      )}

      {decision.prompt && (
        <div className="notice">
          <div className="muted" style={{ fontSize: 12 }}>Prompt compatto</div>
          <code>{decision.prompt}</code>
        </div>
      )}

      {decision.code && (
        <div>
          <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>Consegna a un agente esterno</div>
          <div className="row">
            {decision.targets.filter((t) => t.kind === "CLI" || t.kind === "DESKTOP").map((t) => (
              <button key={t.key} className="btn" disabled={busy || !t.available} title={t.detail} onClick={() => handoff(t.key)}>
                <Icon name={t.kind === "CLI" ? "terminal" : "external"} size={13} /> {t.name}
              </button>
            ))}
          </div>
          {lastPrompt && (lastPrompt.text
            ? <div className="notice" style={{ marginTop: 6 }}>Incolla nell'app {lastPrompt.copied ? "(già negli appunti)" : ""}: <code>{lastPrompt.text}</code></div>
            : <div className="notice" style={{ marginTop: 6 }}>Aperto nel terminale, nella cartella del progetto, con il prompt.</div>)}
        </div>
      )}

      {(task.status === "IN_PROGRESS" || task.status === "DONE") && (
        <div className="stack" style={{ gap: 6 }}>
          <div className="muted" style={{ fontSize: 12 }}>Review dell'operatore</div>
          <textarea placeholder="Nota (facoltativa): cosa hai verificato, cosa manca…" value={note}
                    onChange={(e) => setNote(e.target.value)} style={{ minHeight: 56 }} />
          <div className="row">
            {task.status === "IN_PROGRESS" && (
              <button className="btn btn-primary" disabled={busy} onClick={() => review("ACCEPTED")}>
                <Icon name="check" size={13} /> Accetta e completa</button>
            )}
            <button className="btn" disabled={busy} onClick={() => review("CHANGES_REQUESTED")}>
              {task.status === "DONE" ? "Riapri con modifiche" : "Richiedi modifiche"}</button>
          </div>
        </div>
      )}

      {(handoffs.length > 0 || reviews.length > 0) && (
        <details>
          <summary>Storia: {handoffs.length} consegne, {reviews.length} review</summary>
          {handoffs.map((h) => (
            <div key={`h${h.id}`} className="muted" style={{ fontSize: 12 }}>
              {when(h.createdAt)} — consegnata a <strong>{h.target}</strong> · <code>{h.documentPath}</code></div>
          ))}
          {reviews.map((r) => (
            <div key={`r${r.id}`} style={{ fontSize: 12 }}>
              {when(r.createdAt)} — <strong>{r.verdict === "ACCEPTED" ? "accettata" : "modifiche richieste"}</strong>
              {r.note ? `: ${r.note}` : ""} <span className="muted">({r.reviewer})</span></div>
          ))}
        </details>
      )}
    </div>
  );
}
