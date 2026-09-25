import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi, useIsAdmin } from "../context";
import type { EcosystemServiceInfo } from "../api/types";
import { Field, Modal, ProblemNote, when } from "./ui";
import { Icon } from "./icons";

const STATUS: Record<string, { label: string; tone: string }> = {
  NEVER: { label: "mai avviato", tone: "badge" },
  ALREADY_RUNNING: { label: "già attivo", tone: "badge badge-ok" },
  STARTING: { label: "in avvio…", tone: "badge badge-accent" },
  RUNNING: { label: "attivo", tone: "badge badge-ok" },
  FAILED: { label: "non partito", tone: "badge badge-danger" },
};
const AVAILABILITY: Record<string, string> = {
  RUNNING: "risponde", STOPPED: "fermo", NOT_INSTALLED: "non installato", INCOMPATIBLE_HARDWARE: "incompatibile",
  UNKNOWN: "sconosciuto", NOT_IN_CATALOG: "non nel catalogo",
};

/**
 * The ecosystem's services and their start (ADR-028): what answers now, what
 * happened at the last start, and a button to start one -- or all -- without
 * ever starting twice. With `manage`, an admin also chooses what starts with
 * AI Company OS, in which order, how long to wait, and with which command.
 */
export function EcosystemPanel({ manage }: { manage?: boolean }) {
  const api = useApi();
  const isAdmin = useIsAdmin();
  const [services, setServices] = useState<EcosystemServiceInfo[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [editing, setEditing] = useState<EcosystemServiceInfo | null>(null);

  const load = useCallback(() => api.ecosystemServices().then(setServices).catch(setProblem), [api]);
  useEffect(() => { load(); }, [load]);

  // While something is starting, follow it.
  const starting = (services ?? []).some((s) => s.lastStatus === "STARTING");
  useEffect(() => {
    if (!starting) return;
    const timer = window.setInterval(load, 2000);
    return () => window.clearInterval(timer);
  }, [starting, load]);

  async function act(action: () => Promise<unknown>) {
    setProblem(null);
    try { await action(); await load(); } catch (error) { setProblem(error); }
  }

  return (
    <div className="card card-body stack">
      <div className="row">
        <div className="section-title" style={{ margin: 0 }}><Icon name="integrations" size={12} /> Ecosistema all'avvio</div>
        <span className="spacer" />
        <button className="btn btn-small" onClick={() => act(() => api.startEcosystem())}>Avvia quelli automatici</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {(services ?? []).map((s) => (
        <div key={s.key} className="row" style={{ borderBottom: "1px solid var(--border)", paddingBottom: 6, alignItems: "flex-start" }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="row" style={{ gap: 6 }}>
              <strong>{s.name}</strong>
              <span className={STATUS[s.lastStatus]?.tone ?? "badge"}>{STATUS[s.lastStatus]?.label ?? s.lastStatus}</span>
              <span className="muted" style={{ fontSize: 12 }}>ora: {AVAILABILITY[s.availability] ?? s.availability}</span>
              {s.autostart ? <span className="chip">parte con AI Company OS</span> : <span className="chip">manuale</span>}
            </div>
            {s.lastMessage && <div className="muted" style={{ fontSize: 12 }}>{s.lastMessage}{s.lastAttemptAt ? ` · ${when(s.lastAttemptAt)}` : ""}</div>}
            {manage && s.command && <div className="mono muted" style={{ fontSize: 11, overflowWrap: "anywhere" }}>{s.command}</div>}
          </div>
          <button className="btn btn-small" disabled={s.availability === "RUNNING" || s.lastStatus === "STARTING"}
                  onClick={() => act(() => api.startEcosystemService(s.key))}>Avvia</button>
          {manage && isAdmin && <button className="btn btn-small" onClick={() => setEditing(s)}>Configura</button>}
        </div>
      ))}
      {services?.length === 0 && <div className="muted">Nessun servizio configurato.</div>}
      {editing && <ConfigureService service={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />}
    </div>
  );
}

function ConfigureService({ service, onClose, onSaved }: {
  service: EcosystemServiceInfo; onClose: () => void; onSaved: () => void;
}) {
  const api = useApi();
  const [autostart, setAutostart] = useState(service.autostart);
  const [position, setPosition] = useState(service.position);
  const [timeout, setTimeoutSeconds] = useState(service.timeoutSeconds);
  const [executable, setExecutable] = useState<string | null>(null);
  const [args, setArgs] = useState("");
  const [healthUrl, setHealthUrl] = useState("");
  const [problem, setProblem] = useState<unknown>(null);

  useEffect(() => {
    api.softwareEntry(service.key).then((entry) => {
      const s = entry.body as unknown as { executable: string | null; executableArgs: string[]; healthUrl: string | null };
      setExecutable(s.executable ?? "");
      setArgs((s.executableArgs ?? []).join("\n"));
      setHealthUrl(s.healthUrl ?? "");
    }).catch(setProblem);
  }, [api, service.key]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setProblem(null);
    try {
      await api.configureEcosystemService(service.key, service.version, { autostart, position, timeoutSeconds: timeout });
      const entry = await api.softwareEntry(service.key);
      const current = entry.body as unknown as Record<string, unknown>;
      await api.updateSoftware(service.key, entry.etag, {
        ...current, executable: executable || null,
        executableArgs: args.split("\n").map((a) => a.trim()).filter((a) => a.length > 0),
        healthUrl: healthUrl || null,
      });
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <Modal title={`Avvio di ${service.name}`} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <label className="row" style={{ gap: 6 }}><input type="checkbox" style={{ width: "auto" }} checked={autostart}
               onChange={(e) => setAutostart(e.target.checked)} /> Avvia insieme ad AI Company OS</label>
        <div className="grid grid-2">
          <Field label="Ordine" hint="Prima i servizi da cui dipendono gli altri (es. Ollama)."><input type="number" value={position} onChange={(e) => setPosition(Number(e.target.value))} /></Field>
          <Field label="Attesa massima (s)" hint="Da 5 a 600."><input type="number" value={timeout} min={5} max={600} onChange={(e) => setTimeoutSeconds(Number(e.target.value))} /></Field>
        </div>
        <Field label="Eseguibile" hint="Accetta variabili d'ambiente, es. %USERPROFILE%\\3D Omniverse\\start.ps1 tramite powershell.exe.">
          <input value={executable ?? ""} onChange={(e) => setExecutable(e.target.value)} disabled={executable === null} /></Field>
        <Field label="Argomenti" hint="Uno per riga. Nessuna shell: ogni riga è un argomento.">
          <textarea className="mono" value={args} onChange={(e) => setArgs(e.target.value)} style={{ minHeight: 90, fontSize: 12 }} /></Field>
        <Field label="URL di salute" hint="Risponde quando il servizio è pronto: serve a non avviarlo due volte e a sapere quando è partito.">
          <input value={healthUrl} onChange={(e) => setHealthUrl(e.target.value)} placeholder="http://localhost:8800" /></Field>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit">Salva</button></div>
      </form>
    </Modal>
  );
}
