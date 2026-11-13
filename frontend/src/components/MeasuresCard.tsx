import { useCallback, useEffect, useState } from "react";
import { useApi } from "../context";
import type { GroupMeasure, HandoffMeasure, MeasuresReport, RunMeasure } from "../api/types";
import { ProblemNote } from "./ui";

type View = "models" | "agents" | "runs" | "handoffs";

const VIEWS: { key: View; label: string }[] = [
  { key: "models", label: "Modelli" },
  { key: "agents", label: "Agenti" },
  { key: "runs", label: "Esecuzioni" },
  { key: "handoffs", label: "Handoff" },
];

const KIND: Record<string, string> = {
  GOVERNANCE: "governance", TASK: "task", PHASE: "fase", CONTEXT_MAP: "mappa del contesto", SKILL: "skill",
  KNOWLEDGE: "knowledge",
};

const DASH = "—";

/** A count as the operator reads it: 1200, 16.384. */
const count = (n: number | null | undefined) => n === null || n === undefined ? DASH : n.toLocaleString("it-IT");

/** A duration the provider measured: 850 ms, 9,9 s. */
function duration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return DASH;
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toLocaleString("it-IT", { minimumFractionDigits: 1, maximumFractionDigits: 1 })} s`;
}

const windows = (sizes: number[]) => sizes.length === 0 ? DASH : sizes.map(count).join(", ");

/** A calendar day as the server sent it (2026-11-01 → 1/11/2026): no time zone can move it to the day before. */
const day = (iso: string) => iso.split("-").reverse().map((part) => String(Number(part))).join("/");

/**
 * PHASE 35 (ADR-041, phase 1): what the agents consumed, as measured -- per model, per
 * agent, per run, per handoff. A number appears only where something measured it:
 * tokens and durations as the provider reported them, characters counted on what was
 * sent or written. Nothing here is an estimate.
 */
export function MeasuresCard() {
  const api = useApi();
  const [report, setReport] = useState<MeasuresReport | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [view, setView] = useState<View>("models");

  const load = useCallback(() => api.measurements().then(setReport).catch(setProblem), [api]);
  useEffect(() => { load(); }, [load]);

  return (
    <div className="card card-body stack">
      <div className="row">
        <div className="section-title" style={{ margin: 0 }}>Misure degli agenti — questo mese</div>
        <span className="spacer" />
        {report && <span className="muted" style={{ fontSize: 12.5 }}>
          {day(report.from)} – {day(report.to)}</span>}
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="muted" style={{ fontSize: 12.5 }}>
        Solo valori misurati, mai stimati: token e durate arrivano dal provider di ogni esecuzione (per Ollama{" "}
        <code>prompt_eval_count</code>, <code>eval_count</code> e i tempi); se il provider non li riporta, vedi «{DASH}».
        Il contesto è la finestra impostata per Ollama (<code>num_ctx</code>). Prompt e pacchetti di handoff sono misurati
        in caratteri, così come sono stati inviati, e non sono convertiti in token.
      </div>
      <div className="tabs" role="tablist" aria-label="Vista delle misure">
        {VIEWS.map((v) => (
          <button key={v.key} className="tab" role="tab" aria-selected={view === v.key} onClick={() => setView(v.key)}>
            {v.label}</button>
        ))}
      </div>
      {report && view === "models" && <Groups label="Misure per modello" head="Modello" groups={report.byModel} />}
      {report && view === "agents" && <Groups label="Misure per agente" head="Agente" groups={report.byAgent} />}
      {report && view === "runs" && <Runs runs={report.runs} />}
      {report && view === "handoffs" && <Handoffs handoffs={report.handoffs} />}
    </div>
  );
}

function Groups({ label, head, groups }: { label: string; head: string; groups: GroupMeasure[] }) {
  if (groups.length === 0) return <p className="muted" style={{ margin: 0 }}>Nessuna esecuzione dell'AI Engine in questo periodo.</p>;
  return (
    <div className="table-scroll">
      <table aria-label={label}>
        <thead><tr>
          <th>{head}</th><th title="Esecuzioni con le durate del provider, sul totale">Con durate</th>
          <th>Token in, media</th><th>Token out, media</th><th>Durata media</th><th>Caricamento medio</th>
          <th title="Token generati sul tempo di generazione, delle esecuzioni che misurano entrambi">Token/s</th>
          <th>Contesto</th><th title="Caratteri medi del prompt di sistema e del messaggio">Prompt, car.</th>
        </tr></thead>
        <tbody>{groups.map((g) => (
          <tr key={`${g.agentId ?? ""}-${g.key}`}>
            <td className={head === "Modello" ? "mono" : undefined}>{g.key}</td>
            <td>{g.timedRuns} di {g.runs}</td>
            <td>{count(g.avgInputTokens)}</td>
            <td>{count(g.avgOutputTokens)}</td>
            <td>{duration(g.avgProviderTotalMs)}</td>
            <td>{duration(g.avgLoadMs)}</td>
            <td>{g.outputTokensPerSecond === null || g.outputTokensPerSecond === undefined ? DASH
              : g.outputTokensPerSecond.toLocaleString("it-IT", { maximumFractionDigits: 1 })}</td>
            <td>{windows(g.contextWindows)}</td>
            <td className="muted">{count(g.avgSystemChars)} / {count(g.avgUserChars)}</td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}

function Runs({ runs }: { runs: RunMeasure[] }) {
  if (runs.length === 0) return <p className="muted" style={{ margin: 0 }}>Nessuna esecuzione dell'AI Engine in questo periodo.</p>;
  return (
    <div className="table-scroll">
      <table aria-label="Misure per esecuzione">
        <thead><tr>
          <th>Task</th><th>Agente</th><th>Modello</th><th>Token in / out</th><th>Caricamento</th>
          <th>Lettura prompt</th><th>Generazione</th><th title="La durata che il provider riporta per la chiamata">Totale</th>
          <th>Contesto</th><th title="Caratteri del prompt di sistema e del messaggio inviati">Prompt, car.</th><th>Esito</th>
        </tr></thead>
        <tbody>{runs.map((r) => (
          <tr key={r.runId}>
            <td>{r.taskTitle}</td>
            <td>{r.agentName ?? DASH}</td>
            <td className="mono">{r.model}</td>
            <td>{count(r.inputTokens)} / {count(r.outputTokens)}</td>
            <td>{duration(r.loadMs)}</td>
            <td>{duration(r.promptEvalMs)}</td>
            <td>{duration(r.evalMs)}</td>
            <td>{duration(r.providerTotalMs)}</td>
            <td>{count(r.contextWindow)}</td>
            <td className="muted">{count(r.systemChars)} / {count(r.userChars)}</td>
            <td>{r.status === "SUCCEEDED" ? "riuscita" : "fallita"}</td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}

function Handoffs({ handoffs }: { handoffs: HandoffMeasure[] }) {
  if (handoffs.length === 0) return <p className="muted" style={{ margin: 0 }}>Nessun handoff in questo periodo.</p>;
  return (
    <>
      <div className="table-scroll">
        <table aria-label="Misure per handoff">
          <thead><tr>
            <th>Task</th><th>Strumento</th><th title="Caratteri del pacchetto .aicos/handoffs/…">Pacchetto, car.</th>
            <th title="Caratteri dei file che il pacchetto chiede di leggere">Da leggere, car.</th>
            <th title="Caratteri dei file da leggere solo se serve">Se serve, car.</th><th>File</th>
          </tr></thead>
          <tbody>{handoffs.map((h) => (
            <tr key={h.handoffId}>
              <td>{h.taskTitle}</td>
              <td className="mono">{h.target}</td>
              <td>{h.packageChars === null ? <span className="muted">non misurato</span> : count(h.packageChars)}</td>
              <td>{count(h.requiredChars)}</td>
              <td>{count(h.optionalChars)}</td>
              <td>{h.packageChars === null ? DASH
                : `${h.reads.length} file${h.missingFiles > 0 ? `, ${h.missingFiles} mancant${h.missingFiles === 1 ? "e" : "i"}` : ""}`}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      {handoffs.filter((h) => h.reads.length > 0).map((h) => (
        <details key={h.handoffId}>
          <summary>{h.taskTitle} — file chiesti</summary>
          <ul className="measure-reads">
            {h.reads.map((read, i) => (
              <li key={i}>
                <code>{read.path}</code>
                <span className="muted"> · {KIND[read.kind] ?? read.kind}{read.required ? "" : ", se serve"} · </span>
                {read.chars === null ? <span className="badge badge-warn">mancante</span> : <>{count(read.chars)} car.</>}
              </li>
            ))}
          </ul>
        </details>
      ))}
    </>
  );
}
