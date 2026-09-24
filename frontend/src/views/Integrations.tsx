import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { Software } from "../api/types";
import { ProblemNote } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon } from "../components/icons";
import { AVAILABILITY, openWeb, useLauncher } from "./SoftwareHub";

/**
 * The applications that live inside the console, and the ones that cannot.
 *
 * Local services the operator runs (Open WebUI, 3D Omniverse) are embedded in
 * an iframe: same site as the console, so their own login works. Third-party
 * web apps refuse to be framed -- measured, ADR-019 §6 -- and open in a named
 * window the console reuses, one click away.
 */
export function Integrations({ selected, navigate }: { selected: string | null; navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const [all, setAll] = useState<Software[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const { launch, outcome } = useLauncher();

  useEffect(() => { api.software().then(setAll).catch(setProblem); }, [api]);

  const embedded = (all ?? []).filter((s) => s.embeddable && s.launchKind === "LOCAL_SERVICE");
  const external = (all ?? []).filter((s) => s.launchKind === "WEB"
    && ["PRODUCTIVITY", "VCS", "HOSTING", "AI_CLOUD"].includes(s.category));
  const current = embedded.find((s) => s.key === selected) ?? null;

  if (current) {
    const url = current.url!;
    return (
      <div className="stack">
        <div className="row">
          <button className="btn btn-small" onClick={() => navigate("integrations")}>← Integrazioni</button>
          <AppIcon software={current} size={28} /><h1 style={{ fontSize: 17 }}>{current.name}</h1>
          <span className={`dot ${AVAILABILITY[current.availability].dot}`} />
          <span className="muted">{AVAILABILITY[current.availability].label} · {url}</span>
          <span className="spacer" />
          {current.availability === "STOPPED" && current.launchable && (
            <button className="btn" onClick={() => launch(current)}><Icon name="play" size={14} /> Avvia</button>
          )}
          <a className="btn btn-small" href={url} target={`aicos-${current.key}`} rel="noreferrer">
            <Icon name="external" size={13} /> Finestra</a>
        </div>
        {outcome}
        {current.availability === "STOPPED" && (
          <div className="notice notice-warn">
            {current.name} non risponde su <code>{url}</code>. Avvialo, poi ricarica questa scheda.
          </div>
        )}
        <iframe className="embed-frame" src={url} title={current.name}
                allow="clipboard-read; clipboard-write; microphone" />
      </div>
    );
  }

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Integrazioni</h1>
          <p>Le applicazioni dell'ecosistema dentro la console quando è possibile, in una finestra dedicata quando non lo è.</p>
        </div>
      </div>
      <ProblemNote problem={problem} />

      <div className="section-title">Dentro la console</div>
      <div className="tile-grid">
        {embedded.map((s) => (
          <button key={s.key} className="tile" onClick={() => navigate("integrations", s.key)}>
            <span className={`dot ${AVAILABILITY[s.availability].dot}`} />
            <AppIcon software={s} size={46} /><h3>{s.name}</h3><span className="role">{s.role}</span>
            <span className="chip">{AVAILABILITY[s.availability].label}</span>
          </button>
        ))}
      </div>

      <div className="section-title">Finestra dedicata (il servizio rifiuta l'embedding)</div>
      <div className="tile-grid">
        {external.map((s) => (
          <button key={s.key} className="tile" onClick={() => openWeb(s)} title={s.embedNote ?? ""}>
            <AppIcon software={s} size={40} /><h3>{s.name}</h3><span className="role">{s.role}</span>
            <span className="chip"><Icon name="external" size={11} /> apre</span>
          </button>
        ))}
      </div>

      <div className="card card-body stack">
        <h2>Integrazione dei dati</h2>
        <p className="muted" style={{ margin: 0 }}>
          Mostrare posta, file o task di ClickUp <em>dentro</em> AI Company OS richiede le API dei servizi con le
          credenziali dell'operatore (OAuth per Gmail e Drive, token personale per ClickUp). È una decisione umana,
          registrata per PHASE 18, insieme alla shell desktop con WebView che renderebbe incorporabili anche i siti.
        </p>
      </div>
    </div>
  );
}
