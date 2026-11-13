import { useEffect, useState, type FormEvent } from "react";
import { useApi, useIsAdmin } from "../context";
import type { CatalogModel, ModelCatalog, Provider } from "../api/types";
import { Field, Modal, ProblemNote } from "../components/ui";
import { ConfirmDeletion, DeleteButton } from "../components/Deletion";

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
  const [retiring, setRetiring] = useState<CatalogModel | null>(null);
  const [tuning, setTuning] = useState<CatalogModel | null>(null);
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
          <h1>Modelli</h1>
          <p>I modelli a disposizione dei tuoi agenti e i provider che li servono. Il ruolo orienta le scelte dell'orchestratore; la disponibilità è verificata dal motore AI.</p>
        </div>
        {catalog && (
          <span className={catalog.engineReachable ? "badge badge-ok" : "badge badge-danger"}>
            Motore AI {catalog.engineReachable ? `attivo · predefinito ${catalog.engineDefault}` : "non raggiungibile"}
          </span>
        )}
      </div>
      <ProblemNote problem={problem} />

      <div className="card">
        <div className="card-head"><h2>Modelli</h2></div>
        <div className="table-scroll"><table>
          <thead><tr><th>Modello</th><th>Ruolo</th><th>Ciclo di vita</th><th>Capability</th><th>Dimensione</th><th title="Come Ollama esegue il modello: finestra di contesto, quanto resta in memoria, quantizzazione consigliata">Esecuzione</th><th>Prezzo /M token</th><th>Ora</th>{isAdmin && <th aria-label="Azioni" />}</tr></thead>
          <tbody>
            {catalog?.models.map((m) => (
              <tr key={m.key}>
                <td><strong>{m.displayName}</strong><div className="mono muted">{m.key}</div>
                  {m.notes && <div className="muted" style={{ fontSize: 12, maxWidth: 420 }}>{m.notes}</div>}
                  {m.replacedBy && <div style={{ fontSize: 12 }}>→ sostituto: <code>{m.replacedBy}</code></div>}</td>
                <td>{ROLE_LABEL[m.role] ?? m.role}
                  {(m.alsoServes ?? []).length > 0 && (
                    <div className="muted" style={{ fontSize: 12 }}>serve anche: {(m.alsoServes ?? []).map((r) => ROLE_LABEL[r] ?? r).join(", ")}</div>
                  )}</td>
                <td><span className={LIFECYCLE_TONE[m.lifecycle]}>{m.lifecycle}</span></td>
                <td>{m.capabilities.map((c) => <span key={c} className="chip">{c}</span>)}</td>
                <td className="muted">{m.sizeGb ? `${m.sizeGb} GB` : "—"}{m.parameters ? ` · ${m.parameters}` : ""}
                  {m.contextWindow ? <div>{Math.round(m.contextWindow / 1024)}K ctx</div> : null}</td>
                <td style={{ fontSize: 12.5 }}>{m.numCtx != null || m.keepAlive || m.quantization ? (
                  <div className="stack" style={{ gap: 4 }}>
                    <span className="muted">{runsWith(m)}</span>
                    <div><button className="btn btn-small" aria-label={`Esecuzione di ${m.displayName}`}
                                 onClick={() => setTuning(m)}>Modifica</button></div>
                  </div>) : <span className="muted">—</span>}</td>
                <td>{paid.has(m.providerKey) ? (
                  <div style={{ fontSize: 12.5 }}>
                    {m.inputPricePerMtok != null ? <>${m.inputPricePerMtok} in · ${m.outputPricePerMtok} out</> : <span className="badge badge-warn">da impostare</span>}
                    {isAdmin && <div><button className="btn btn-small" onClick={() => setPricing(m)}>Prezzo</button></div>}
                  </div>) : <span className="muted" style={{ fontSize: 12 }}>nessun costo per esecuzione</span>}</td>
                <td>{m.engineAvailable === null ? <span className="badge">?</span>
                  : m.engineAvailable ? <span className="badge badge-ok">pronto</span>
                  : <span className="badge" title={m.engineDetail ?? ""}>non servito</span>}</td>
                {isAdmin && <td><DeleteButton label={`Congeda ${m.displayName}`} onClick={() => setRetiring(m)} /></td>}
              </tr>
            ))}
          </tbody>
        </table></div>
      </div>

      {retiring && (
        <ConfirmDeletion title="Togli dal catalogo dei modelli" subject={retiring.displayName} confirmLabel="Congeda"
                         intro={<>Stai per togliere <strong>«{retiring.displayName}»</strong> dal catalogo dei modelli.</>}
                         loadPreview={() => api.modelDeletionPreview(retiring.key)}
                         onClose={() => setRetiring(null)}
                         onConfirm={async () => {
                           await api.deleteModel(retiring.key, retiring.version);
                           setRetiring(null);
                           load();
                         }} />
      )}
      {pricing && <PriceModal model={pricing} onClose={() => setPricing(null)} onSaved={() => { setPricing(null); load(); }} />}
      {tuning && <TuneModal model={tuning} onClose={() => setTuning(null)} onSaved={() => { setTuning(null); load(); }} />}
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
            <div style={{ flex: 1, minWidth: 0 }}>
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

/** PHASE 35: "finestra 16K · in memoria 5m · Q4_K_M" -- what is set, nothing invented. */
function runsWith(m: CatalogModel): string {
  return [m.numCtx != null ? `finestra ${Math.round(m.numCtx / 1024)}K` : null, m.keepAlive ? `in memoria ${m.keepAlive}` : null,
    m.quantization ?? null].filter(Boolean).join(" · ");
}

/**
 * PHASE 35 (ADR-041 §11): how Ollama runs a model. The window and the keep-alive go to Ollama with every
 * request; the quantization is the one recommended for this machine -- the model file decides it.
 */
function TuneModal({ model, onClose, onSaved }: { model: CatalogModel; onClose: () => void; onSaved: () => void }) {
  const api = useApi();
  const [numCtx, setNumCtx] = useState(model.numCtx != null ? String(model.numCtx) : "");
  const [keepAlive, setKeepAlive] = useState(model.keepAlive ?? "");
  const [quantization, setQuantization] = useState(model.quantization ?? "");
  const [problem, setProblem] = useState<unknown>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      await api.tuneModel(model, { numCtx: numCtx === "" ? null : Number(numCtx), keepAlive: keepAlive || null,
        quantization: quantization || null });
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }
  return (
    <Modal title={`Esecuzione — ${model.displayName}`} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <ProblemNote problem={problem} />
        <p className="muted" style={{ margin: 0, fontSize: 12.5 }}>
          Cambiare la finestra fa ricaricare il modello a Ollama alla richiesta successiva. «0» in memoria lo scarica subito
          dopo la risposta, «-1» lo tiene sempre.
        </p>
        <Field label="Finestra di contesto (num_ctx)">
          <input type="number" min={512} value={numCtx} onChange={(e) => setNumCtx(e.target.value)} />
        </Field>
        <Field label="Quanto resta in memoria (keep_alive)">
          <input value={keepAlive} placeholder="5m" onChange={(e) => setKeepAlive(e.target.value)} />
        </Field>
        <Field label="Quantizzazione consigliata">
          <input value={quantization} placeholder="Q4_K_M" onChange={(e) => setQuantization(e.target.value)} />
        </Field>
        <div className="row"><span className="spacer" /><button className="btn btn-primary" type="submit">Salva</button></div>
      </form>
    </Modal>
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
        <div className="row modal-actions"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit">Salva</button></div>
      </form>
    </Modal>
  );
}
