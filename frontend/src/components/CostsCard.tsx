import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi, useIsAdmin } from "../context";
import type { CostSummary, Provider } from "../api/types";
import { Field, Modal, ProblemNote } from "./ui";

const usd = (n: number | null | undefined) => n === null || n === undefined ? "—" : `$${Number(n).toFixed(n < 1 ? 4 : 2)}`;

/**
 * What the runs cost this month and what paid providers may spend (ADR-031).
 * A pay-per-token provider runs only within a budget, at a known price; local
 * models and subscriptions cost nothing per run.
 */
export function CostsCard() {
  const api = useApi();
  const isAdmin = useIsAdmin();
  const [summary, setSummary] = useState<CostSummary | null>(null);
  const [providers, setProviders] = useState<Provider[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [editing, setEditing] = useState<string | null>(null);

  const load = useCallback(() => api.costs().then(setSummary).catch(setProblem), [api]);
  useEffect(() => {
    load();
    api.providers().then(setProviders).catch(() => setProviders([]));
  }, [api, load]);

  const paid = providers.filter((p) => p.billing === "PAY_PER_TOKEN");

  return (
    <div className="card card-body stack">
      <div className="row">
        <div className="section-title" style={{ margin: 0 }}>Costi delle esecuzioni — questo mese</div>
        <span className="spacer" />
        <strong>{usd(summary?.totalUsd ?? 0)}</strong>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="muted" style={{ fontSize: 12.5 }}>
        Un provider a consumo esegue solo con un budget mensile e con il prezzo del modello impostati; a budget esaurito le sue
        esecuzioni si fermano. I modelli locali e gli abbonamenti (Claude Code, Codex…) non costano per esecuzione.
      </div>
      <table>
        <thead><tr><th>Provider</th><th>Esecuzioni</th><th>Token</th><th>Costo</th><th>Budget</th><th /></tr></thead>
        <tbody>
          {paid.map((p) => {
            const spend = summary?.providers.find((s) => s.providerKey === p.key);
            const budget = summary?.budgets.find((b) => b.providerKey === p.key);
            return (
              <tr key={p.key}>
                <td><strong>{p.name}</strong> {p.status !== "ENABLED" && <span className="chip">spento</span>}</td>
                <td>{spend?.runs ?? 0}</td>
                <td className="muted">{((spend?.inputTokens ?? 0) + (spend?.outputTokens ?? 0)).toLocaleString("it-IT")}</td>
                <td>{usd(spend?.costUsd ?? 0)}{spend && spend.runsWithoutPrice > 0 && <span className="badge badge-warn">{spend.runsWithoutPrice} senza prezzo</span>}</td>
                <td>{budget ? (
                  <div style={{ minWidth: 160 }}>
                    <div className={budget.exceeded ? "progress warn" : budget.alert ? "progress warn" : "progress"}>
                      <span style={{ width: `${Math.min(100, budget.percent)}%` }} /></div>
                    <span className="muted" style={{ fontSize: 12 }}>{usd(budget.spentThisMonthUsd)} di {usd(budget.monthlyLimitUsd)}
                      {budget.exceeded ? " · esaurito" : budget.alert ? " · soglia superata" : ""}</span>
                  </div>
                ) : <span className="badge">nessun budget: bloccato</span>}</td>
                <td>{isAdmin && <button className="btn btn-small" onClick={() => setEditing(p.key)}>Budget</button>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {summary && summary.models.length > 0 && (
        <details>
          <summary>Per modello e per agente</summary>
          <table>
            <thead><tr><th>Modello</th><th>Esecuzioni</th><th>Token in / out</th><th>Prezzo /M</th><th>Costo</th></tr></thead>
            <tbody>{summary.models.map((m) => (
              <tr key={m.model}><td className="mono">{m.model}</td><td>{m.runs}</td>
                <td className="muted">{m.inputTokens} / {m.outputTokens}</td>
                <td className="muted">{m.inputPricePerMtok !== null ? `${m.inputPricePerMtok} / ${m.outputPricePerMtok}` : "—"}</td>
                <td>{usd(m.costUsd)}</td></tr>
            ))}</tbody>
          </table>
          <table>
            <thead><tr><th>Agente</th><th>Esecuzioni</th><th>Token</th><th>Costo</th></tr></thead>
            <tbody>{summary.agents.map((a) => (
              <tr key={a.agentId}><td>{a.agentName}</td><td>{a.runs}</td><td className="muted">{a.tokens}</td><td>{usd(a.costUsd)}</td></tr>
            ))}</tbody>
          </table>
        </details>
      )}
      {editing && <BudgetModal provider={editing} current={summary?.budgets.find((b) => b.providerKey === editing) ?? null}
                               onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />}
    </div>
  );
}

function BudgetModal({ provider, current, onClose, onSaved }: {
  provider: string; current: CostSummary["budgets"][number] | null; onClose: () => void; onSaved: () => void;
}) {
  const api = useApi();
  const [limit, setLimit] = useState(String(current?.monthlyLimitUsd ?? 10));
  const [alert, setAlert] = useState(String(current?.alertPercent ?? 80));
  const [problem, setProblem] = useState<unknown>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      await api.setBudget(provider, Number(limit), Number(alert), current ? `"${current.version}"` : undefined);
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }
  return (
    <Modal title={`Budget mensile — ${provider}`} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Field label="Limite mensile (USD)" hint="Raggiunto il limite, le esecuzioni di questo provider si fermano fino al mese successivo.">
          <input type="number" step="0.01" min="0" value={limit} onChange={(e) => setLimit(e.target.value)} /></Field>
        <Field label="Soglia di avviso (%)"><input type="number" min="1" max="100" value={alert} onChange={(e) => setAlert(e.target.value)} /></Field>
        <div className="muted" style={{ fontSize: 12.5 }}>Il prezzo per milione di token di ogni modello si imposta nella pagina Modelli.</div>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit">Salva</button></div>
      </form>
    </Modal>
  );
}
