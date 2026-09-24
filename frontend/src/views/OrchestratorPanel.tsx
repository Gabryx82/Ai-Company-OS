import { useCallback, useEffect, useState } from "react";
import { useApi } from "../context";
import type { Binding, Handoff, HandoffOutcome, Orchestration, Review, Task } from "../api/types";
import { Modal, ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";
import { BindingChain } from "../components/BindingChain";

const AVAILABILITY_TONE: Record<string, string> = {
  INSTALLED: "badge badge-ok", RUNNING: "badge badge-ok", WEB: "badge badge-ok", STOPPED: "badge badge-warn",
  NOT_INSTALLED: "badge", INCOMPATIBLE_HARDWARE: "badge badge-danger", UNKNOWN: "badge",
};
const DELIVERY_HINT: Record<string, string> = {
  CLI_PROMPT: "si apre nel terminale, nella cartella, con il prompt",
  IDE_FOLDER: "si apre sulla cartella; prompt negli appunti",
  APP_PASTE: "si apre l'app; prompt negli appunti",
  WEB_PASTE: "si apre nel browser; prompt negli appunti",
  MANUAL: "nessuno strumento: pacchetto e prompt pronti",
};

type Target = Orchestration["targets"][number];

/**
 * The Master Orchestrator's view of one task (ADR-021 §3–5, ADR-025 §4): the
 * chain Agent → Model → Provider → Execution Target, what it would use and why,
 * where the task can be handed off -- with the agent's own target first -- and
 * the operator's review. Every action takes the task's tag through `act`.
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
  const [confirming, setConfirming] = useState<Target | null>(null);
  const [outcome, setOutcome] = useState<(HandoffOutcome & { copied: boolean }) | null>(null);
  const [document, setDocument] = useState<string | null>(null);

  const load = useCallback(() => {
    api.orchestration(task.id).then(setDecision).catch(setProblem);
    api.handoffs(task.id).then(setHandoffs).catch(() => setHandoffs([]));
    api.reviews(task.id).then(setReviews).catch(() => setReviews([]));
  }, [api, task.id]);

  // Reload whenever the task changed (its tag moves with every write).
  useEffect(load, [load, task.status, task.agentId]);

  async function handoff(target: Target) {
    setConfirming(null);
    await act(async (etag) => {
      const result = await api.handoff(task.id, etag, target.key);
      let copied = false;
      if (result.promptToClipboard) {
        const text = target.delivery === "APP_PASTE" || target.delivery === "WEB_PASTE" ? result.fullPrompt || result.prompt : result.prompt;
        try { await navigator.clipboard.writeText(text); copied = true; } catch { copied = false; }
      }
      if (result.openUrl) window.open(result.openUrl, `aicos-${target.key}`, "noopener");
      setOutcome({ ...result, copied });
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

  async function copy(text: string) {
    try { await navigator.clipboard.writeText(text); } catch { /* the text is on screen anyway */ }
  }

  if (!decision) {
    return problem ? <ProblemNote problem={problem} /> : null;
  }

  const handoffTargets = decision.targets.filter((t) => t.kind !== "ENGINE")
    .sort((a, b) => Number(b.recommended) - Number(a.recommended));
  const binding = decision.binding as Binding | null;

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
          {task.documentPath && <button className="btn btn-small" onClick={showDocument}><Icon name="file" size={13} /> {task.documentPath}</button>}
        </div>
      )}
      {document && <pre className="doc">{document}</pre>}
      {decision.blockers.length > 0 && (
        <div className="notice notice-warn">{decision.blockers.map((b) => <div key={b}>• {b}</div>)}</div>
      )}

      {binding && <BindingChain agentName={decision.agent.name ?? undefined} agentRole={decision.agent.role ?? undefined} binding={binding} compact />}

      <dl className="kv">
        <dt>Agente</dt>
        <dd>{decision.agent.name ?? "—"} {decision.agent.role && <span className="muted">({decision.agent.role})</span>}
          <div className="muted" style={{ fontSize: 12 }}>{decision.agent.reason}</div></dd>
        <dt>Modello</dt>
        <dd className="mono">{decision.model.model ?? "scelto dallo strumento o default dell'engine"}
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

      <div>
        <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>
          Consegna a uno strumento — senza API: AI Company OS prepara cartella, contesto e prompt, poi apre lo strumento</div>
        <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(170px, 1fr))", gap: 6 }}>
          {handoffTargets.map((t) => (
            <button key={t.key} className="agent-card" disabled={busy || !t.available} title={t.detail}
                    onClick={() => setConfirming(t)} style={{ opacity: t.available ? 1 : 0.55 }}>
              <span className="row" style={{ gap: 6 }}>
                <Icon name={t.kind === "CLI" ? "terminal" : t.kind === "MANUAL" ? "file" : "external"} size={13} />
                <strong style={{ fontSize: 13 }}>{t.name}</strong>
              </span>
              {t.recommended && <span className="badge badge-accent">consigliato</span>}
              <small className="muted">{DELIVERY_HINT[t.delivery] ?? ""}</small>
            </button>
          ))}
        </div>
        {outcome && (
          <div className="notice" style={{ marginTop: 8, display: "grid", gap: 6 }}>
            <div><Icon name="check" size={13} /> Pronto in <code>{outcome.folder}</code>
              {(outcome.written ?? []).length > 0 && <> — scritti: {(outcome.written ?? []).map((w) => <code key={w} style={{ marginRight: 6 }}>{w}</code>)}</>}</div>
            {outcome.promptToClipboard
              ? <div>{outcome.copied ? "Il prompt è già negli appunti: incollalo nello strumento." : "Copia il prompt e incollalo nello strumento."}</div>
              : <div>Aperto nel terminale, nella cartella, con il prompt.</div>}
            <div className="row">
              <button className="btn btn-small" onClick={() => copy(outcome.prompt)}>Copia prompt compatto</button>
              {outcome.fullPrompt && <button className="btn btn-small" onClick={() => copy(outcome.fullPrompt)}>Copia prompt completo</button>}
              {outcome.openUrl && <a className="btn btn-small" href={outcome.openUrl} target="_blank" rel="noreferrer">Apri di nuovo</a>}
            </div>
          </div>
        )}
      </div>

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

      {confirming && (
        <Modal title="Conferma la consegna" onClose={() => setConfirming(null)}>
          <div className="stack">
            <div className="what-box">
              <span className="chain-label">Cosa</span>
              <strong>{decision.code ? `${decision.code} — ` : ""}{decision.title}</strong>
              {task.description && <span className="muted" style={{ fontSize: 12.5 }}>{task.description.slice(0, 220)}{task.description.length > 220 ? "…" : ""}</span>}
              <span className="muted" style={{ fontSize: 12 }}>
                {decision.projectName ? `Progetto ${decision.projectName}` : "Nessun progetto: cartella dedicata alla task"}
                {decision.phaseNumber != null ? ` · Fase ${decision.phaseNumber} — ${decision.phaseTitle}` : ""}</span>
            </div>
            {binding && <BindingChain agentName={decision.agent.name ?? undefined} agentRole={decision.agent.role ?? undefined}
                                      binding={{ ...binding, target: { ...binding.target, key: confirming.key, name: confirming.name,
                                        delivery: confirming.delivery as Binding["target"]["delivery"] } }} compact />}
            <div className="notice">
              <strong>Attraverso {confirming.name}</strong>: {DELIVERY_HINT[confirming.delivery] ?? ""}.
              <div className="muted" style={{ fontSize: 12.5 }}>{confirming.detail}</div>
            </div>
            <div className="row"><span className="spacer" />
              <button className="btn" onClick={() => setConfirming(null)}>Annulla</button>
              <button className="btn btn-primary" onClick={() => handoff(confirming)}>Prepara e apri</button></div>
          </div>
        </Modal>
      )}
    </div>
  );
}
