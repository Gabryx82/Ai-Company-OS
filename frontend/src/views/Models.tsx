import { useEffect, useState, type FormEvent } from "react";
import { useApi, useIsAdmin } from "../context";
import type { CatalogModel, ModelCatalog, Provider } from "../api/types";
import { Field, Modal, ProblemNote } from "../components/ui";

const ROLE_LABEL: Record<string, string> = {
  FAST: "Veloce", GENERAL: "Generale", CODER: "Codice", PLANNER: "Pianificazione", VISION: "Visione",
  REASONING: "Ragionamento", PREMIUM: "Premium", EMBEDDING: "Embedding", TEST: "Test",
};
const LIFECYCLE_TONE: Record<string, string> = {
  ACTIVE: "badge badge-ok", CANDIDATE: "badge badge-accent", DEPRECATED: "badge badge-warn", RETIRED: "badge",
};
const STATUS_TONE: Record<string, string> = {
  ENABLED: "badge badge-ok", DISABLED: "badge badge-warn", INCOMPATIBLE_HARDWARE: "badge badge-danger",
};

/**
 * Providers and models as separate catalogs (ADR-018): who serves, what is
 * served, what each model is for, and whether the engine can run it now.
 */
export function Models() {
  const api = useApi();
  const [providers, setProviders] = useState<Provider[] | null>(null);
  const [catalog, setCatalog] = useState<ModelCatalog | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [pricing, setPricing] = useState<CatalogModel | null>(null);
  const isAdmin = useIsAdmin();

  const load = () => {
    api.providers().then(setProviders).catch(setProblem);
    api.modelCatalog().then(setCatalog).catch(setProblem);
  };
  useEffect(load, [api]); // eslint-disable-line react-hooks/exhaustive-deps
  // Only what the engine actually serves: a provider switched off (an API without a key) is not an offer.
  const served = (catalog?.uncatalogued ?? []).filter((u) => u.available);
  const paid = new Set((providers ?? []).filter((p) => p.billing === "PAY_PER_TOKEN").map((p) => p.key));

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Modelli LLM</h1>
          <p>Provider e modelli sono cataloghi distinti. Il ruolo guida l'orchestratore; la disponibilità viene dall'AI Engine.</p>
        </div>
        {catalog && (
          <span className={catalog.engineReachable ? "badge badge-ok" : "badge badge-danger"}>
            AI Engine {catalog.engineReachable ? `raggiungibile · default ${catalog.engineDefault}` : "non raggiungibile"}
          </span>
        )}
      </div>
      <ProblemNote problem={problem} />

      <div className="card">
        <div className="card-head"><h2>Modelli</h2></div>
        <table>
          <thead><tr><th>Modello</th><th>Ruolo</th><th>Ciclo di vita</th><th>Capability</th><th>Dimensione</th><th>Prezzo /M token</th><th>Ora</th></tr></thead>
          <tbody>
            {catalog?.models.map((m) => (
              <tr key={m.key}>
                <td><strong>{m.displayName}</strong><div className="mono muted">{m.key}</div>
                  {m.notes && <div className="muted" style={{ fontSize: 12, maxWidth: 420 }}>{m.notes}</div>}
                  {m.replacedBy && <div style={{ fontSize: 12 }}>→ sostituto: <code>{m.replacedBy}</code></div>}</td>
                <td>{ROLE_LABEL[m.role] ?? m.role}</td>
                <td><span className={LIFECYCLE_TONE[m.lifecycle]}>{m.lifecycle}</span></td>
                <td>{m.capabilities.map((c) => <span key={c} className="chip">{c}</span>)}</td>
                <td className="muted">{m.sizeGb ? `${m.sizeGb} GB` : "—"}{m.parameters ? ` · ${m.parameters}` : ""}
                  {m.contextWindow ? <div>{Math.round(m.contextWindow / 1024)}K ctx</div> : null}</td>
                <td>{paid.has(m.providerKey) ? (
                  <div style={{ fontSize: 12.5 }}>
                    {m.inputPricePerMtok != null ? <>${m.inputPricePerMtok} in · ${m.outputPricePerMtok} out</> : <span className="badge badge-warn">da impostare</span>}
                    {isAdmin && <div><button className="btn btn-small" onClick={() => setPricing(m)}>Prezzo</button></div>}
                  </div>) : <span className="muted" style={{ fontSize: 12 }}>nessun costo per esecuzione</span>}</td>
                <td>{m.engineAvailable === null ? <span className="badge">?</span>
                  : m.engineAvailable ? <span className="badge badge-ok">pronto</span>
                  : <span className="badge" title={m.engineDetail ?? ""}>non servito</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {pricing && <PriceModal model={pricing} onClose={() => setPricing(null)} onSaved={() => { setPricing(null); load(); }} />}
      {served.length > 0 && (
        <div className="card">
          <div className="card-head"><h2>Serviti dall'engine ma non catalogati</h2></div>
          <div className="card-body">
            {served.map((u) => (
              <span key={u.key} className="chip">{u.key}{u.available ? "" : " (non disponibile)"}{u.billed ? " · a consumo" : ""}</span>
            ))}
          </div>
        </div>
      )}

      <div className="card">
        <div className="card-head"><h2>Provider</h2></div>
        {providers?.map((p) => (
          <div key={p.key} className="list-item" style={{ alignItems: "flex-start" }}>
            <div style={{ flex: 1 }}>
              <div className="row"><strong>{p.name}</strong><span className="chip">{p.kind}</span><span className="chip">{p.billing}</span></div>
              {p.notes && <div className="muted" style={{ fontSize: 12.5 }}>{p.notes}</div>}
            </div>
            <span className={STATUS_TONE[p.status]}>{p.status}</span>
            {p.docsUrl && <a className="btn btn-small" href={p.docsUrl} target="_blank" rel="noreferrer">Docs</a>}
          </div>
        ))}
      </div>
    </div>
  );
}

/** ADR-031: the price per million tokens of a pay-per-token model; without it the model does not run. */
function PriceModal({ model, onClose, onSaved }: { model: CatalogModel; onClose: () => void; onSaved: () => void }) {
  const api = useApi();
  const [input, setInput] = useState(model.inputPricePerMtok != null ? String(model.inputPricePerMtok) : "");
  const [output, setOutput] = useState(model.outputPricePerMtok != null ? String(model.outputPricePerMtok) : "");
  const [problem, setProblem] = useState<unknown>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      await api.setModelPrice(model.key, model.version, input === "" ? null : Number(input), output === "" ? null : Number(output));
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }
  return (
    <Modal title={`Prezzo di ${model.displayName}`} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <div className="muted" style={{ fontSize: 12.5 }}>USD per milione di token, dal listino del provider. Serve a contare la spesa contro il budget.</div>
        <Field label="Token in ingresso"><input type="number" step="0.0001" min="0" value={input} onChange={(e) => setInput(e.target.value)} /></Field>
        <Field label="Token in uscita"><input type="number" step="0.0001" min="0" value={output} onChange={(e) => setOutput(e.target.value)} /></Field>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit">Salva</button></div>
      </form>
    </Modal>
  );
}
