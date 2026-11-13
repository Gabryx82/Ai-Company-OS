import { useCallback, useEffect, useState } from "react";
import { useApi } from "../context";
import type {
  Binding, Handoff, HandoffOutcome, LearningMode, Orchestration, Readiness, ReadinessItem, Review, Task,
} from "../api/types";
import { Modal, ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";
import { BindingChain } from "../components/BindingChain";
import { OpenCodeFollow } from "./OpenCodeFollow";

const AVAILABILITY_TONE: Record<string, string> = {
  INSTALLED: "badge badge-ok", RUNNING: "badge badge-ok", WEB: "badge badge-ok", STOPPED: "badge badge-warn",
  NOT_INSTALLED: "badge", INCOMPATIBLE_HARDWARE: "badge badge-danger", UNKNOWN: "badge",
};
const DELIVERY_HINT: Record<string, string> = {
  CLI_PROMPT: "si apre nel terminale, nella cartella, con il prompt",
  IDE_FOLDER: "si apre sulla cartella; prompt negli appunti",
  APP_PASTE: "si apre l'app; prompt negli appunti",
  APP_LINK: "si apre l'app sulla cartella, con il prompt già scritto",
  WEB_PASTE: "si apre nel browser; prompt negli appunti",
  MANUAL: "nessuno strumento: pacchetto e prompt pronti",
};

type Target = Orchestration["targets"][number];

/**
 * PHASE 37 (ADR-044): what a handoff asks of the agent as far as learning goes. The code is
 * the agent's in every case; what changes is who puts it in the files.
 */
const LEARNING: { mode: LearningMode; title: string; detail: string }[] = [
  { mode: "TEACH", title: "Imparo",
    detail: "L'agente scrive il codice e lo spiega riga per riga in una lezione; nei file lo porti tu, un passo alla volta." },
  { mode: "RECAP", title: "Non posso seguire ora",
    detail: "L'agente lavora da solo e ti lascia la lezione su quello che ha costruito, da ripercorrere quando torni." },
  { mode: "NONE", title: "Solo il lavoro", detail: "Nessuna lezione: implementa, prova e prepara la review." },
];

/** What the server would choose with nothing said: the project's level decides. */
function defaultLearning(level: string | null | undefined): LearningMode {
  return level === "GUIDED" || !level ? "TEACH" : level === "SUPERVISED" ? "RECAP" : "NONE";
}

const AREA_LABEL: Record<string, string> = {
  TASK: "Task", BINDING: "Strumento", MODEL: "Modello", CONTEXT: "Contesto", SKILL: "Skill", KNOWLEDGE: "Knowledge",
  MCP: "MCP", TOOL: "Tool", SOFTWARE: "Software",
};
const STATUS_BADGE: Record<string, { tone: string; label: string }> = {
  OK: { tone: "badge badge-ok", label: "ok" }, WARNING: { tone: "badge badge-warn", label: "avviso" },
  BLOCKING: { tone: "badge badge-danger", label: "bloccante" },
};

/**
 * PHASE 36 (ADR-042): what the agent would find on this target -- each item ok, a warning
 * or blocking, with the copy of a skill into the tool when that is what is missing.
 */
export function ReadinessList({ readiness, onCopy }: {
  readiness: Readiness; onCopy?: (item: ReadinessItem) => void;
}) {
  const blocking = readiness.items.filter((i) => i.status === "BLOCKING");
  const warnings = readiness.items.filter((i) => i.status === "WARNING");
  return (
    <div className="stack" style={{ gap: 6 }} aria-label={`Verifica per ${readiness.targetName}`}>
      <div className={blocking.length ? "notice notice-danger" : warnings.length ? "notice notice-warn" : "notice"}>
        {blocking.length
          ? <><strong>Ferma:</strong> {blocking.map((b) => b.detail).join(" ")}</>
          : <><strong>Pronta per {readiness.targetName}</strong>
            {warnings.length ? ` — ${warnings.length} avvis${warnings.length === 1 ? "o" : "i"}` : ""}</>}
      </div>
      <ul style={{ margin: 0, paddingLeft: 0, listStyle: "none", display: "grid", gap: 4 }}>
        {readiness.items.map((i) => (
          <li key={`${i.area}:${i.key}`} style={{ display: "flex", alignItems: "flex-start", gap: 6, fontSize: 12.5 }}>
            <span className={STATUS_BADGE[i.status].tone} style={{ flex: "none", minWidth: 64, textAlign: "center" }}>{STATUS_BADGE[i.status].label}</span>
            <span style={{ flex: "1 1 0", minWidth: 0, overflowWrap: "anywhere" }}>
              <span className="muted">{AREA_LABEL[i.area] ?? i.area}</span> <strong>{i.name}</strong> — {i.detail}
              {i.action?.type === "HUB_COPY" && onCopy && (
                <button className="btn btn-small" style={{ marginLeft: 6 }} onClick={() => onCopy(i)}>Copia nello strumento</button>
              )}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

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
  const [checkTarget, setCheckTarget] = useState<string | null>(null);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [confirmReadiness, setConfirmReadiness] = useState<Readiness | null>(null);
  const [learning, setLearning] = useState<LearningMode | null>(null);

  const load = useCallback(() => {
    api.orchestration(task.id).then(setDecision).catch(setProblem);
    api.handoffs(task.id).then(setHandoffs).catch(() => setHandoffs([]));
    api.reviews(task.id).then(setReviews).catch(() => setReviews([]));
  }, [api, task.id]);

  // Reload whenever the task changed (its tag moves with every write).
  useEffect(load, [load, task.status, task.agentId]);

  // PHASE 36: the check for the target in view -- the agent's own unless the operator picks another.
  const viewed = checkTarget ?? decision?.binding?.target.key ?? null;
  const assigned = decision?.agent.assigned ?? false;
  const check = useCallback(() => {
    if (!viewed || !assigned) { setReadiness(null); return; }
    api.readiness(task.id, viewed).then(setReadiness).catch(() => setReadiness(null));
  }, [api, task.id, viewed, assigned]);
  useEffect(check, [check, task.status]);

  useEffect(() => {
    setConfirmReadiness(null);
    setLearning(null);
    if (confirming) api.readiness(task.id, confirming.key).then(setConfirmReadiness).catch(setProblem);
  }, [api, task.id, confirming]);

  async function copyToTool(item: ReadinessItem) {
    if (!item.action) return;
    try {
      await api.hubDeploy(item.action.key, item.action.app);
    } catch (error) {
      setProblem(error);
    }
    check();
    if (confirming) api.readiness(task.id, confirming.key).then(setConfirmReadiness).catch(setProblem);
  }

  async function handoff(target: Target) {
    setConfirming(null);
    await act(async (etag) => {
      const result = await api.handoff(task.id, etag, target.key, learning ?? undefined);
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

      {decision.agent.assigned && (
        <details open={readiness !== null && !readiness.ready}>
          <summary>Verifica prima della consegna{readiness ? ` — ${readiness.ready ? "pronta" : "ferma"} per ${readiness.targetName}` : ""}</summary>
          <div className="stack" style={{ gap: 6, marginTop: 6 }}>
            <label className="row" style={{ gap: 6, fontSize: 12.5 }}>Strumento
              <select aria-label="Strumento da verificare" value={viewed ?? ""} onChange={(e) => setCheckTarget(e.target.value)}>
                {decision.targets.map((t) => <option key={t.key} value={t.key}>{t.kind === "ENGINE" ? `${t.name} (run)` : t.name}</option>)}
              </select>
            </label>
            {readiness && <ReadinessList readiness={readiness} onCopy={copyToTool} />}
          </div>
        </details>
      )}

      {decision.prompt && (
        <details>
          <summary>Prompt da incollare a mano</summary>
          <div className="notice" style={{ marginTop: 6 }}>
            <code>{decision.prompt}</code>
            <div className="muted" style={{ fontSize: 12 }}>
              Porta l'agente al pacchetto della consegna; se la task non è ancora stata consegnata, l'agente chiede di
              consegnarla. La strada che dà all'agente ruolo, skill e knowledge è «Prepara e apri» qui sotto.</div>
            <button className="btn btn-small" onClick={() => copy(decision.prompt)}>Copia</button>
          </div>
        </details>
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
            {outcome.notOpened
              ? <div className="notice notice-warn">Lo strumento non è stato aperto: {outcome.notOpened}. Aprilo tu nella
                cartella; {outcome.copied ? "il prompt è già negli appunti." : "copia il prompt qui sotto."}</div>
              : outcome.promptToClipboard && outcome.delivery !== "APP_LINK"
              ? <div>{outcome.copied ? "Il prompt è già negli appunti: incollalo nello strumento." : "Copia il prompt e incollalo nello strumento."}</div>
              : outcome.delivery === "APP_LINK"
                ? <div>Aperto nell'app, con il prompt già scritto: conferma la cartella e invia.</div>
                : <div>Aperto nel terminale, nella cartella, con il prompt.</div>}
            {!outcome.notOpened && outcome.delivery === "APP_LINK" && outcome.promptToClipboard && (
              <div className="muted" style={{ fontSize: 12 }}>{outcome.copied
                ? "Il prompt è anche negli appunti, se l'app non lo mostra." : "Se l'app non lo mostra, copialo qui sotto."}</div>
            )}
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

      {handoffs[0]?.followable && <OpenCodeFollow handoffId={handoffs[0].id} />}

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
            <fieldset className="learn-mode">
              <legend>Come vuoi lavorarci?</legend>
              {LEARNING.map((option) => (
                <label key={option.mode}>
                  <input type="radio" name="learning-mode" checked={(learning ?? defaultLearning(decision.autonomyLevel)) === option.mode}
                         onChange={() => setLearning(option.mode)} />
                  <span><strong>{option.title}</strong>
                    {option.mode === defaultLearning(decision.autonomyLevel) && <span className="badge" style={{ marginLeft: 6 }}>il solito per questo progetto</span>}
                    <small>{option.detail}</small></span>
                </label>
              ))}
            </fieldset>
            {confirmReadiness ? <ReadinessList readiness={confirmReadiness} onCopy={copyToTool} />
              : <div className="muted" style={{ fontSize: 12.5 }}>Verifico che cosa troverà l'agente…</div>}
            <div className="row modal-actions"><span className="spacer" />
              <button className="btn" onClick={() => setConfirming(null)}>Annulla</button>
              <button className="btn btn-primary" disabled={!confirmReadiness || !confirmReadiness.ready}
                      onClick={() => handoff(confirming)}>Prepara e apri</button></div>
          </div>
        </Modal>
      )}
    </div>
  );
}
