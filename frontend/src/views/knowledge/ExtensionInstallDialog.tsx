import { Fragment, useEffect, useState } from "react";
import { useApi } from "../../context";
import type { HubExtensionPreview, HubInstalled, HubInstallOutcome, HubInstallPlan, HubResult } from "../../api/types";
import { Field, Modal, ProblemNote } from "../../components/ui";
import { Icon } from "../../components/icons";

const NAME = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;

const STATUS: Record<HubInstallOutcome["status"], { label: string; tone: string }> = {
  INSTALLED: { label: "installato", tone: "badge badge-ok" },
  NEEDS_CONFIRMATION: { label: "da confermare", tone: "badge badge-warn" },
  MANUAL: { label: "da completare nell'app", tone: "badge badge-accent" },
  FAILED: { label: "non installato", tone: "badge badge-danger" },
  REMOVED: { label: "tolto", tone: "badge" },
};

/**
 * A plugin or connector of the Marketplace (PHASE 31, ADR-036 §6b): what it is,
 * what it asks, how each application gets it -- the application's own CLI,
 * Claude Desktop's configuration file, or a step to do in the app -- and then
 * what happened, application by application. A command a plugin's marketplace
 * declares runs only once the operator has read it and approved it. The values
 * typed for a connector (API keys) go to the applications and are forgotten here.
 */
/** What a value is for, as the dialog says it (PHASE 32: URL and ARG, TD-89; CONFIG, TD-88). */
const INPUT_KIND: Record<string, string> = {
  HEADER: "intestazione HTTP", ENV: "variabile d'ambiente", URL: "parte dell'indirizzo",
  ARG: "argomento del comando", CONFIG: "opzione del plugin",
};

export function ExtensionInstallDialog({ result, installed, providerName, onClose, onInstalled }: {
  result: HubResult; installed: HubInstalled | null; providerName: string; onClose: () => void;
  onInstalled: (message: string) => void;
}) {
  const api = useApi();
  const [preview, setPreview] = useState<HubExtensionPreview | null>(null);
  const [targets, setTargets] = useState<string[]>([]);
  const [name, setName] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const [replace, setReplace] = useState(false);
  const [outcomes, setOutcomes] = useState<HubInstallOutcome[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const connector = result.kind === "CONNECTOR";
  const appName = (id: string) => installed?.apps.find((a) => a.id === id)?.name ?? id;

  useEffect(() => {
    api.hubItem(result.provider, result.ref).then((p) => {
      setPreview(p);
      setName(p.installName);
      setTargets(p.targets.filter((t) => t.method !== "MANUAL" && t.available).map((t) => t.app));
    }).catch(setProblem);
  }, [api, result.provider, result.ref]);

  const installable = (preview?.targets ?? []).filter((t) => t.method !== "MANUAL");
  const manual = (preview?.targets ?? []).filter((t) => t.method === "MANUAL");
  // A plugin's options reach Claude Code only (TD-88): they are required when it is one of the targets.
  const asks = connector || targets.includes("claude-code");
  const missing = asks ? (preview?.inputs ?? []).filter((i) => i.required && !i.defaultValue && !(values[i.name] ?? "").trim()).map((i) => i.name) : [];
  const nameOk = !connector || NAME.test(name);

  /** A plugin's options, when any was given (TD-88). */
  function options(): Record<string, string> | undefined {
    const given = Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== ""));
    return Object.keys(given).length > 0 ? given : undefined;
  }

  function report(list: HubInstallOutcome[]) {
    const done = list.filter((o) => o.status === "INSTALLED").map((o) => appName(o.target));
    if (done.length > 0) onInstalled(`«${preview?.name ?? result.name}» installato in: ${done.join(", ")}`);
  }

  async function install() {
    if (!preview) return;
    setBusy(true);
    setProblem(null);
    try {
      if (connector) {
        const response = await api.hubInstallConnector({
          provider: result.provider, ref: result.ref, targets, name: name.trim(), values, replace,
        });
        setValues({});
        setOutcomes(response.results);
        report(response.results);
      } else {
        const list: HubInstallOutcome[] = [];
        for (const target of targets) {
          list.push(await api.hubInstallPlugin(result.provider, result.ref, target, undefined, options()));
          setOutcomes([...list]);
        }
        report(list);
      }
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  async function confirm(outcome: HubInstallOutcome) {
    if (!outcome.confirmation) return;
    setBusy(true);
    setProblem(null);
    try {
      const again = await api.hubInstallPlugin(result.provider, result.ref, outcome.target, outcome.confirmation, options());
      setOutcomes((list) => list.map((o) => o.target === outcome.target ? again : o));
      report([again]);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  async function openApp(app: string) {
    const software = installed?.apps.find((a) => a.id === app)?.software ?? app;
    try {
      await api.launch(software);
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <Modal title={`${connector ? "Connettore" : "Plugin"} «${preview?.name ?? result.name}»`} onClose={onClose} wide>
      <div className="stack">
        <div className="muted" style={{ fontSize: 12.5 }}>
          Da <strong>{providerName}</strong>. {connector
            ? "Un connettore (server MCP) collega gli agenti a un servizio o a uno strumento: lo aggiungo alle app che scegli."
            : "Un plugin porta skill, comandi, agenti, hook e connettori: lo installo con la CLI dell'app, come faresti tu."}
        </div>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {!preview && !problem && <div className="muted">Leggo la scheda…</div>}
        {preview && (
          <>
            {preview.warnings.length > 0 && (
              <div className="notice notice-warn" style={{ fontSize: 12.5 }}>
                {preview.warnings.map((w) => <div key={w}><Icon name="warning" size={12} /> {w}</div>)}
              </div>
            )}
            <div className="row" style={{ fontSize: 12.5 }}>
              {preview.version && <span className="chip">v{preview.version}</span>}
              <span className="chip mono">{preview.installName}</span>
              {preview.sourceUrl && <a href={preview.sourceUrl} target="_blank" rel="noreferrer"><Icon name="external" size={12} /> sorgente</a>}
            </div>
            {preview.description && <div style={{ whiteSpace: "pre-wrap" }}>{preview.description}</div>}
            {Object.keys(preview.details).length > 0 && (
              <dl className="hub-facts">
                {Object.entries(preview.details).map(([label, value]) => (
                  <Fragment key={label}><dt>{label}</dt><dd>{value}</dd></Fragment>
                ))}
              </dl>
            )}

            {outcomes.length === 0 && (
              <>
                {installable.length > 0 && (connector || preview.inputs.length > 0) && (
                  <>
                    {connector && (
                    <Field label="Nome del connettore nelle app" hint="Lettere, cifre, trattini. Così lo vedrai nelle app."
                           error={nameOk ? undefined : "Solo lettere, cifre, - e _ (fino a 64)"}>
                      <input value={name} onChange={(e) => setName(e.target.value)} />
                    </Field>
                    )}
                    {preview.inputs.map((input) => (
                      <Field key={input.name} label={`${input.name}${input.required ? " *" : ""}`}
                             hint={[input.description, INPUT_KIND[input.kind] ?? input.kind,
                               input.secret ? "segreta: va solo alle app, AI Company OS non la conserva" : null].filter(Boolean).join(" · ")}>
                        <input type={input.secret ? "password" : "text"} autoComplete="off" spellCheck={false}
                               placeholder={input.defaultValue ?? ""} value={values[input.name] ?? ""}
                               onChange={(e) => setValues((v) => ({ ...v, [input.name]: e.target.value }))} />
                      </Field>
                    ))}
                  </>
                )}
                <div className="stack" style={{ gap: 6 }}>
                  <div className="section-title">Installa in</div>
                  {installable.length === 0 && manual.length === 0 && (
                    <div className="muted" style={{ fontSize: 12.5 }}>Questo elemento non si installa da qui: apri la sorgente per configurarlo a mano.</div>
                  )}
                  {installable.map((plan) => (
                    <PlanRow key={plan.app} plan={plan} checked={targets.includes(plan.app)}
                             onToggle={(on) => setTargets((t) => on ? [...t, plan.app] : t.filter((x) => x !== plan.app))} />
                  ))}
                  {manual.map((plan) => (
                    <div key={plan.app} className="hub-plan">
                      <div className="row"><strong>{plan.appName}</strong><span className="badge badge-accent">dall'app</span><span className="spacer" />
                        {plan.available && <button className="btn btn-small" onClick={() => openApp(plan.app)}><Icon name="external" size={12} /> Apri {plan.appName}</button>}
                      </div>
                      {plan.note && <div className="muted" style={{ fontSize: 12.5 }}>{plan.note}</div>}
                    </div>
                  ))}
                  {connector && targets.includes("claude-desktop") && (
                    <label className="row" style={{ fontSize: 12.5, gap: 6 }}>
                      <input type="checkbox" style={{ width: "auto", margin: 0 }} checked={replace} onChange={(e) => setReplace(e.target.checked)} />
                      Sostituisci in Claude Desktop un connettore con lo stesso nome
                    </label>
                  )}
                </div>
              </>
            )}

            {outcomes.length > 0 && (
              <div className="stack" style={{ gap: 8 }} aria-label="Esito">
                <div className="section-title">Esito</div>
                {outcomes.map((o) => (
                  <div key={o.target} className="hub-plan">
                    <div className="row"><strong>{appName(o.target)}</strong><span className={STATUS[o.status].tone}>{STATUS[o.status].label}</span>
                      <span className="spacer" />
                      {o.software && o.status !== "FAILED" && <button className="btn btn-small" onClick={() => openApp(o.target)}>
                        <Icon name="external" size={12} /> Apri {appName(o.target)}</button>}
                    </div>
                    {o.message && <div style={{ fontSize: 12.5 }}>{o.message}</div>}
                    {o.status === "NEEDS_CONFIRMATION" && (
                      <div className="notice notice-warn stack" style={{ gap: 6, fontSize: 12.5 }}>
                        <div><Icon name="warning" size={12} /> Il marketplace chiede di eseguire questo comando sul tuo PC:</div>
                        <pre className="doc mono" style={{ margin: 0 }} aria-label="Comando dichiarato">{o.command}</pre>
                        <div className="row"><span className="spacer" />
                          <button className="btn btn-small btn-primary" disabled={busy} onClick={() => confirm(o)}>
                            Ho letto il comando: installa</button></div>
                      </div>
                    )}
                    {o.status !== "NEEDS_CONFIRMATION" && o.command && <code className="muted" style={{ fontSize: 12 }}>{o.command}</code>}
                    {o.output && (
                      <details><summary style={{ cursor: "pointer", fontSize: 12 }}>Output</summary>
                        <pre className="doc" style={{ maxHeight: 180, fontSize: 11.5 }}>{o.output}</pre></details>
                    )}
                  </div>
                ))}
              </div>
            )}
          </>
        )}
        <div className="row modal-actions">
          {missing.length > 0 && outcomes.length === 0 && installable.length > 0 && (
            <span className="muted" style={{ fontSize: 12 }}>Serve: {missing.join(", ")}</span>
          )}
          <span className="spacer" />
          <button className="btn" onClick={onClose}>{outcomes.length > 0 ? "Chiudi" : "Annulla"}</button>
          {outcomes.length === 0 && installable.length > 0 && (
            <button className="btn btn-primary" disabled={!preview || busy || targets.length === 0 || missing.length > 0 || !nameOk}
                    onClick={install}>{busy ? "Installo…" : "Installa"}</button>
          )}
        </div>
      </div>
    </Modal>
  );
}

function PlanRow({ plan, checked, onToggle }: { plan: HubInstallPlan; checked: boolean; onToggle: (on: boolean) => void }) {
  return (
    <label className="hub-plan" style={{ cursor: plan.available ? "pointer" : "default" }}>
      <div className="row">
        <input type="checkbox" checked={checked} disabled={!plan.available} onChange={(e) => onToggle(e.target.checked)} />
        <strong>{plan.appName}</strong>
        <span className="chip">{plan.method === "CLI" ? "con la sua CLI" : "file di configurazione"}</span>
        {!plan.available && <span className="badge">non trovata su questo PC</span>}
      </div>
      {plan.command && <pre className="doc mono" style={{ margin: 0, fontSize: 11.5 }}>{plan.command}</pre>}
      {plan.note && <div className="muted" style={{ fontSize: 12 }}>{plan.note}</div>}
    </label>
  );
}
