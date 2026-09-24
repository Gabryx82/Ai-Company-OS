import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { ModelCatalog, Provider } from "../api/types";
import { ProblemNote } from "../components/ui";

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

  useEffect(() => {
    api.providers().then(setProviders).catch(setProblem);
    api.modelCatalog().then(setCatalog).catch(setProblem);
  }, [api]);

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
          <thead><tr><th>Modello</th><th>Ruolo</th><th>Ciclo di vita</th><th>Capability</th><th>Dimensione</th><th>Ora</th></tr></thead>
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
                <td>{m.engineAvailable === null ? <span className="badge">?</span>
                  : m.engineAvailable ? <span className="badge badge-ok">pronto</span>
                  : <span className="badge" title={m.engineDetail ?? ""}>non servito</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {catalog && catalog.uncatalogued.length > 0 && (
        <div className="card">
          <div className="card-head"><h2>Serviti dall'engine ma non catalogati</h2></div>
          <div className="card-body">
            {catalog.uncatalogued.map((u) => (
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
