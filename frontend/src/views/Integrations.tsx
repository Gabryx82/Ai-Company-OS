import { useEffect, useState, type ReactNode } from "react";
import { useApi } from "../context";
import type { Software } from "../api/types";
import { ProblemNote } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon } from "../components/icons";
import { AVAILABILITY, openWeb, useLauncher } from "./SoftwareHub";
import { EcosystemPanel } from "../components/EcosystemPanel";

type Navigate = (page: string, detail?: string) => void;

const embeddable = (s: Software) => s.embeddable && s.launchKind === "LOCAL_SERVICE";

/**
 * The applications that live inside the console, and the ones that cannot.
 *
 * Local services the operator runs (Open WebUI, 3D Omniverse) are embedded in
 * an iframe: same site as the console, so their own login works. Third-party
 * web apps refuse to be framed -- measured, ADR-019 §6 -- and open in a named
 * window the console reuses, one click away. An embedded application itself is
 * shown by {@link KeptEmbeds}, which keeps it loaded (ADR-046).
 */
export function Integrations({ navigate }: { navigate: Navigate }) {
  const api = useApi();
  const [all, setAll] = useState<Software[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);

  useEffect(() => { api.software().then(setAll).catch(setProblem); }, [api]);

  const embedded = (all ?? []).filter(embeddable);
  const external = (all ?? []).filter((s) => s.launchKind === "WEB"
    && ["PRODUCTIVITY", "VCS", "HOSTING", "AI_CLOUD"].includes(s.category));
  // ADR-045: OpenClaw's Control UI refuses framing; its page in the console is the native one.
  const openClaw = (all ?? []).find((s) => s.key === "openclaw-gateway") ?? null;

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Integrazioni</h1>
          <p>Le applicazioni collegate, aperte direttamente nella console quando è possibile, altrimenti in una finestra dedicata.</p>
        </div>
      </div>
      <ProblemNote problem={problem} />
      <EcosystemPanel />

      <div className="section-title">Dentro la console</div>
      <div className="tile-grid">
        {embedded.map((s) => (
          <button key={s.key} className="tile" onClick={() => navigate("integrations", s.key)}>
            <span className={`dot ${AVAILABILITY[s.availability].dot}`} />
            <AppIcon software={s} size={46} /><h3>{s.name}</h3><span className="role">{s.role}</span>
            <span className="chip">{AVAILABILITY[s.availability].label}</span>
          </button>
        ))}
        {openClaw && (
          <button className="tile" onClick={() => navigate("openclaw")}>
            <span className={`dot ${AVAILABILITY[openClaw.availability].dot}`} />
            <AppIcon software={openClaw} size={46} /><h3>OpenClaw</h3>
            <span className="role">Agenti, conversazione e sessioni qui; la Control UI completa si apre nel browser</span>
            <span className="chip">{AVAILABILITY[openClaw.availability].label}</span>
          </button>
        )}
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

/**
 * ADR-046: the applications embedded in the console stay loaded once opened. Open WebUI takes seconds to start,
 * and it started again at every visit; now it is hidden while the operator works elsewhere and shown again as it
 * was -- the conversation open, the page scrolled where it was. `keys` are the ones opened since the console
 * was loaded, `current` the one shown now (null: none, all hidden).
 */
export function KeptEmbeds({ keys, current, navigate }: { keys: string[]; current: string | null; navigate: Navigate }) {
  const api = useApi();
  const [all, setAll] = useState<Software[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const { launch, outcome } = useLauncher();

  // Read again each time one is shown: whether it runs is what the header says.
  useEffect(() => {
    if (current) api.software().then(setAll).catch(setProblem);
  }, [api, current]);

  if (all === null) {
    if (!current) return null;
    return problem ? <ProblemNote problem={problem} /> : <p className="muted">Caricamento…</p>;
  }
  return (
    <>
      {keys.map((key) => {
        const software = all.find((s) => s.key === key && embeddable(s));
        if (!software) {
          return key === current ? (
            <div key={key} className="stack">
              <div className="row"><button className="btn btn-small" onClick={() => navigate("integrations")}>← Integrazioni</button></div>
              <p className="muted">«{key}» non è un'applicazione che si apre dentro la console.</p>
            </div>
          ) : null;
        }
        return (
          <div key={key} hidden={key !== current}>
            <EmbeddedApp software={software} navigate={navigate} launch={() => launch(software)}
                         outcome={key === current ? outcome : null} />
          </div>
        );
      })}
    </>
  );
}

function EmbeddedApp({ software, navigate, launch, outcome }: {
  software: Software; navigate: Navigate; launch: () => void; outcome: ReactNode;
}) {
  // Kept loaded, it is reloaded only when asked -- after starting it, say.
  const [reloads, setReloads] = useState(0);
  const url = software.url!;
  return (
    <div className="stack">
      <div className="row">
        <button className="btn btn-small" onClick={() => navigate("integrations")}>← Integrazioni</button>
        <AppIcon software={software} size={28} /><h1 style={{ fontSize: 17 }}>{software.name}</h1>
        <span className={`dot ${AVAILABILITY[software.availability].dot}`} />
        <span className="muted">{AVAILABILITY[software.availability].label} · {url}</span>
        <span className="spacer" />
        {software.availability === "STOPPED" && software.launchable && (
          <button className="btn" onClick={launch}><Icon name="play" size={14} /> Avvia</button>
        )}
        <button className="btn btn-small" onClick={() => setReloads((n) => n + 1)} title="Ricarica l'applicazione">
          <Icon name="refresh" size={13} /> Ricarica</button>
        <a className="btn btn-small" href={url} target={`aicos-${software.key}`} rel="noreferrer">
          <Icon name="external" size={13} /> Finestra</a>
      </div>
      {outcome}
      {software.availability === "STOPPED" && (
        <div className="notice notice-warn">
          {software.name} non risponde su <code>{url}</code>. Avvialo, poi premi «Ricarica».
        </div>
      )}
      <iframe key={reloads} className="embed-frame" src={url} title={software.name}
              allow="clipboard-read; clipboard-write; microphone" />
    </div>
  );
}
