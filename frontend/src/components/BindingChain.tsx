import type { Binding, Delivery } from "../api/types";
import { Icon } from "./icons";

export const DELIVERY_LABEL: Record<Delivery, string> = {
  ENGINE_RUN: "esecuzione nell'AI Engine",
  CLI_PROMPT: "CLI nel terminale, con il prompt",
  IDE_FOLDER: "IDE sulla cartella del progetto",
  APP_PASTE: "app desktop, prompt negli appunti",
  WEB_PASTE: "web, prompt negli appunti",
  MANUAL: "lavoro manuale",
};

const BILLING_LABEL: Record<string, string> = { FREE: "gratuito", PAY_PER_TOKEN: "a consumo (API)", SUBSCRIPTION: "abbonamento, senza API" };

/**
 * Agent → Model → Provider → Execution Target (ADR-025), four boxes in a row:
 * the one picture that says who works, with what, served by whom, and where.
 */
export function BindingChain({ agentName, agentRole, binding, compact }: {
  agentName?: string; agentRole?: string; binding: Binding; compact?: boolean;
}) {
  const model = binding.model;
  const provider = binding.provider;
  const target = binding.target;
  return (
    <div className={compact ? "chain chain-compact" : "chain"} aria-label="Catena agente, modello, provider, execution target">
      {agentName && (
        <>
          <div className="chain-step">
            <span className="chain-label">Agente</span>
            <strong>{agentName}</strong>
            {agentRole && <small>{agentRole}</small>}
          </div>
          <span className="chain-arrow" aria-hidden="true">→</span>
        </>
      )}
      <div className="chain-step">
        <span className="chain-label">Modello</span>
        <strong>{model ? model.displayName : target.delivery === "ENGINE_RUN" ? "Default dell'engine" : "Scelto nell'app"}</strong>
        {model && <small className="mono">{model.key}</small>}
        {model?.lifecycle === "DEPRECATED" && <span className="badge badge-warn">superato</span>}
      </div>
      <span className="chain-arrow" aria-hidden="true">→</span>
      <div className="chain-step">
        <span className="chain-label">Provider</span>
        <strong>{provider ? provider.name : "—"}</strong>
        {provider?.billing && <small>{BILLING_LABEL[provider.billing] ?? provider.billing}</small>}
      </div>
      <span className="chain-arrow" aria-hidden="true">→</span>
      <div className="chain-step">
        <span className="chain-label">Execution target</span>
        <strong>{target.name}</strong>
        {target.delivery && <small>{DELIVERY_LABEL[target.delivery]}</small>}
        {target.availability && target.availability !== "INSTALLED" && target.availability !== "RUNNING" && (
          <span className="badge badge-warn">{target.availability === "NOT_INSTALLED" ? "non installato" : target.availability.toLowerCase()}</span>
        )}
      </div>
      {!compact && (binding.problems.length > 0 || binding.warnings.length > 0) && (
        <div className="chain-notes">
          {binding.problems.map((p) => <div key={p} className="notice notice-danger"><Icon name="warning" size={13} /> {p}</div>)}
          {binding.warnings.map((w) => <div key={w} className="muted" style={{ fontSize: 12.5 }}><Icon name="warning" size={12} /> {w}</div>)}
        </div>
      )}
    </div>
  );
}
