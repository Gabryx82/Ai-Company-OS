import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useApi } from "../../context";
import type { HubInstalled, HubInstallResult, HubMarketplace, HubOfferKind, HubPreview, HubResult } from "../../api/types";
import { Field, Modal, ProblemNote } from "../../components/ui";
import { Icon } from "../../components/icons";
import { family, formatCount, slug } from "./hubModel";
import { ExtensionInstallDialog } from "./ExtensionInstallDialog";

const KINDS: { kind: HubOfferKind; label: string; hint: string; placeholder: string; note: string }[] = [
  {
    kind: "SKILL", label: "Skill", hint: "Istruzioni e script che un agente usa quando servono",
    placeholder: "pdf, react, deploy, seo, unity, sicurezza…",
    note: "Le skill possono contenere istruzioni e script che gli agenti eseguono: prima di installare il Knowledge Hub ti mostra i file e il SKILL.md. Installa da fonti di cui ti fidi.",
  },
  {
    kind: "PLUGIN", label: "Plugin", hint: "Pacchetti di skill, comandi, agenti, hook e connettori",
    placeholder: "design, github, sales, figma, linear…",
    note: "Un plugin si installa con la CLI dell'app (Claude Code, Codex), come faresti tu; se il marketplace dichiara un comando da eseguire, te lo mostro e aspetto la tua conferma.",
  },
  {
    kind: "CONNECTOR", label: "Connettori", hint: "Server MCP: servizi e strumenti per gli agenti",
    placeholder: "github, notion, postgres, browser, slack…",
    note: "Un connettore locale è un programma che l'app avvia sul tuo PC; uno remoto riceve i dati che gli passi. Le chiavi che inserisci vanno solo alle app: AI Company OS non le conserva.",
  },
];

const kindOf = (provider: HubMarketplace): HubOfferKind => provider.kind ?? "SKILL";
const lastPart = (ref: string) => ref.split(/[/:]/).pop() ?? ref;

/**
 * The online catalogs (PHASE 31), organised like the Mockup Hub: one search,
 * a shelf of providers -- for skills, plugins and connectors. The ones with an
 * API are searched and installed from here; the others open in the browser,
 * with the search when they take one.
 */
export function MarketplacePanel({ installed, onInstalled }: {
  installed: HubInstalled | null; onInstalled: (message: string) => void;
}) {
  const api = useApi();
  const [providers, setProviders] = useState<HubMarketplace[]>([]);
  const [kind, setKind] = useState<HubOfferKind>("SKILL");
  const [query, setQuery] = useState("");
  const [only, setOnly] = useState<string[]>([]);
  const [results, setResults] = useState<HubResult[] | null>(null);
  const [title, setTitle] = useState("");
  const [errors, setErrors] = useState<{ provider: string; message: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [installing, setInstalling] = useState<HubResult | null>(null);

  useEffect(() => {
    api.hubMarketplaces().then(setProviders).catch(setProblem);
  }, [api]);

  const current = KINDS.find((k) => k.kind === kind) ?? KINDS[0];
  const ofKind = providers.filter((p) => kindOf(p) === kind);
  const searchable = ofKind.filter((p) => p.api);
  const others = ofKind.filter((p) => !p.api);
  const providerName = (key: string) => providers.find((p) => p.key === key)?.name ?? key;
  const appName = (id: string) => installed?.apps.find((a) => a.id === id)?.name ?? id;

  function switchKind(next: HubOfferKind) {
    setKind(next);
    setOnly([]);
    setResults(null);
    setErrors([]);
  }

  // What is already here: by package (the library's provenance) and by name and kind (any application).
  const installedRefs = useMemo(() => {
    const out = new Map<string, string>();
    for (const s of installed?.skills ?? []) {
      if (s.provenance && s.libraryKey) out.set(`${s.provenance.provider} ${s.provenance.reference}`, s.libraryKey);
    }
    return out;
  }, [installed]);
  const byName = useMemo(() => {
    const out = new Map<string, string[]>();
    for (const s of installed?.skills ?? []) {
      const itemKind = s.kind === "PLUGIN" || s.kind === "CONNECTOR" ? s.kind : "SKILL";
      const key = `${itemKind}:${family(s.name)}`;
      const app = installed?.apps.find((a) => a.id === s.app)?.name ?? s.app;
      const list = out.get(key) ?? [];
      if (!list.includes(app)) list.push(app);
      out.set(key, list);
    }
    return out;
  }, [installed]);

  function installedKey(result: HubResult): string | null {
    const direct = installedRefs.get(`${result.provider} ${result.ref}`);
    if (direct) return direct;
    if (result.provider === "skills-sh") {
      const [owner, repo, skill] = result.ref.split("/");
      for (const [ref, key] of installedRefs) {
        const [provider, reference] = ref.split(" ");
        if (provider === "skills-sh" && reference.startsWith(`${owner}/${repo}:`) && reference.split("/").pop() === skill) return key;
      }
    }
    return null;
  }

  function presentIn(result: HubResult): string[] {
    const k = result.kind ?? "SKILL";
    const names = new Set([family(result.name), family(lastPart(result.ref))]);
    return [...new Set([...names].flatMap((n) => byName.get(`${k}:${n}`) ?? []))];
  }

  async function search(event?: FormEvent) {
    event?.preventDefault();
    if (query.trim().length < 2) return;
    setBusy(true);
    setProblem(null);
    try {
      const found = await api.hubSearch(query.trim(), only, kind);
      setResults(found.results ?? []);
      setErrors(found.errors ?? []);
      setTitle(`${current.label} · risultati per «${query.trim()}»`);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  async function browse(provider: HubMarketplace) {
    setBusy(true);
    setProblem(null);
    try {
      setResults(await api.hubBrowse(provider.key));
      setErrors([]);
      setTitle(provider.key === "clawhub" ? "ClawHub · di tendenza" : `Sfoglia ${provider.name}`);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  function openSite(provider: HubMarketplace) {
    const url = query.trim() && provider.searchUrl
      ? provider.searchUrl.replace("{q}", encodeURIComponent(query.trim()))
      : provider.homepage;
    const w = window.open(url, `aicos-skills-${provider.key}`);
    if (w) w.opener = null;
  }

  function openResult(result: HubResult) {
    if (!result.url) return;
    const w = window.open(result.url, "_blank");
    if (w) w.opener = null;
  }

  const grouped = useMemo(() => {
    const out = new Map<string, HubResult[]>();
    for (const r of results ?? []) out.set(r.provider, [...(out.get(r.provider) ?? []), r]);
    return [...out.entries()];
  }, [results]);

  return (
    <div className="stack">
      <div className="hub-kinds" role="tablist" aria-label="Cosa cercare">
        {KINDS.map((k) => (
          <button key={k.kind} role="tab" className="hub-kind-tab" aria-selected={kind === k.kind} onClick={() => switchKind(k.kind)}>
            <strong>{k.label}</strong><small>{k.hint}</small>
          </button>
        ))}
      </div>
      <form className="card card-body stack" onSubmit={search}>
        <div className="row">
          <div className="search" style={{ flex: 1, minWidth: 260 }}><Icon name="search" size={15} />
            <input aria-label="Cerca nei marketplace" placeholder={current.placeholder} value={query}
                   onChange={(e) => setQuery(e.target.value)} /></div>
          <button className="btn btn-primary" type="submit" disabled={busy || query.trim().length < 2 || searchable.length === 0}>
            {busy ? "Cerco…" : "Cerca nei marketplace"}</button>
        </div>
        <div className="row" style={{ fontSize: 12.5 }}>
          <span className="muted">Dove cercare:</span>
          <div className="tabs">
            <button type="button" className="tab" aria-selected={only.length === 0} onClick={() => setOnly([])}>Tutti</button>
            {searchable.map((p) => (
              <button type="button" key={p.key} className="tab" aria-selected={only.includes(p.key)}
                      onClick={() => setOnly((o) => o.includes(p.key) ? o.filter((k) => k !== p.key) : [...o, p.key])}>{p.name}</button>
            ))}
          </div>
        </div>
        <div className="muted" style={{ fontSize: 12 }}>{current.note}</div>
      </form>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />

      {results && (
        <div className="stack">
          <div className="row"><h2 style={{ margin: 0 }}>{title}</h2><span className="chip">{results.length}</span><span className="spacer" />
            <button className="btn btn-small" onClick={() => { setResults(null); setErrors([]); }}>Chiudi risultati</button></div>
          {errors.length > 0 && (
            <div className="notice notice-warn" style={{ fontSize: 12.5 }}>
              {errors.map((e) => <div key={e.provider}><strong>{providerName(e.provider)}</strong>: {e.message}</div>)}
            </div>
          )}
          {results.length === 0 && <div className="muted">Nessun risultato.</div>}
          {grouped.map(([provider, list]) => (
            <div key={provider} className="stack" style={{ gap: 8 }}>
              <div className="section-title">{providerName(provider)} · {list.length}</div>
              <div className="hub-cards">
                {list.map((r) => r.kind === "PLUGIN" || r.kind === "CONNECTOR"
                  ? <ExtensionCard key={`${r.provider}:${r.ref}`} result={r} presentIn={presentIn(r)} appName={appName}
                                   onOpen={() => openResult(r)} onInstall={() => setInstalling(r)} />
                  : <SkillCard key={`${r.provider}:${r.ref}`} result={r} libraryKey={installedKey(r)} presentIn={presentIn(r)}
                               onOpen={() => openResult(r)} onInstall={() => setInstalling(r)} />)}
              </div>
            </div>
          ))}
        </div>
      )}

      <div className="section-title">{current.label} · cataloghi installabili da qui</div>
      {searchable.length === 0 && <div className="muted" style={{ fontSize: 12.5 }}>Nessun catalogo con un'interfaccia da usare qui.</div>}
      <div className="tile-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))" }}>
        {searchable.map((p) => (
          <button key={p.key} className="tile" onClick={() => browse(p)} title={p.description}>
            <Icon name="knowledge" size={24} />
            <h3>{p.name}</h3>
            <span className="role">{p.description}</span>
            <div>
              <span className={p.official ? "badge badge-accent" : "chip"}>{p.official ? "ufficiale" : "community"}</span>
              <span className="badge badge-ok">cerca e installa qui</span>
              {(p.targets ?? []).length > 0 && <span className="chip">per {(p.targets ?? []).map(appName).join(", ")}</span>}
            </div>
          </button>
        ))}
      </div>

      <div className="section-title">{current.label} · altri marketplace e directory, si aprono nel browser</div>
      <div className="tile-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))" }}>
        {others.map((p) => (
          <button key={p.key} className="tile" onClick={() => openSite(p)} title={p.description}>
            <Icon name="external" size={22} />
            <h3>{p.name}</h3>
            <span className="role">{p.description}</span>
            <div>
              <span className={p.official ? "badge badge-accent" : "chip"}>{p.official ? "ufficiale" : "community"}</span>
              <span className="chip">{p.searchUrl && query.trim() ? `cerca «${query.trim()}»` : "apre il sito"}</span>
            </div>
          </button>
        ))}
      </div>
      {kind === "SKILL" && (
        <div className="muted" style={{ fontSize: 12 }}>
          Da un sito esterno puoi copiare il link di un SKILL.md e usarlo in «Libreria e catalogo → Importa da URL».
        </div>
      )}

      {installing && (installing.kind === "PLUGIN" || installing.kind === "CONNECTOR"
        ? <ExtensionInstallDialog result={installing} installed={installed} providerName={providerName(installing.provider)}
                                  onClose={() => setInstalling(null)} onInstalled={onInstalled} />
        : <InstallDialog result={installing} installed={installed} providerName={providerName(installing.provider)}
                         onClose={() => setInstalling(null)}
                         onInstalled={(outcome) => {
                           setInstalling(null);
                           const copies = outcome.copies.map((c) => `${appName(c.app)}: ${
                             c.outcome === "FAILED" ? `non installata (${c.message})` : "installata"}`);
                           onInstalled(`Installata nella libreria come «${outcome.key}»${copies.length ? " · " + copies.join(" · ") : ""}`);
                         }} />)}
    </div>
  );
}

function SkillCard({ result: r, libraryKey, presentIn, onOpen, onInstall }: {
  result: HubResult; libraryKey: string | null; presentIn: string[]; onOpen: () => void; onInstall: () => void;
}) {
  return (
    <div className="agent-card hub-card" style={{ cursor: "default" }}>
      <div className="row" style={{ gap: 6 }}>
        <strong>{r.name}</strong><span className="spacer" />
        {r.installs ? <span className="chip" title="Installazioni o download">↓ {formatCount(r.installs)}</span> : null}
      </div>
      {r.description && <small className="muted desc">{r.description}</small>}
      <div>
        {r.source && <span className="chip">{r.source}</span>}
        {r.version && <span className="chip">v{r.version}</span>}
        {r.suspicious && <span className="badge badge-danger">segnalata come sospetta</span>}
        {libraryKey && <span className="badge badge-ok">installata come {libraryKey}</span>}
        {!libraryKey && presentIn.length > 0 && <span className="chip" title="Una skill con lo stesso nome è già presente">già in: {presentIn.join(", ")}</span>}
      </div>
      <div className="row">
        {r.url && <button className="btn btn-small" onClick={onOpen}><Icon name="external" size={12} /> Apri</button>}
        <span className="spacer" />
        <button className="btn btn-small btn-primary" disabled={!!libraryKey} onClick={onInstall}>
          {libraryKey ? "Installata" : "Anteprima e installa"}</button>
      </div>
    </div>
  );
}

function ExtensionCard({ result: r, presentIn, appName, onOpen, onInstall }: {
  result: HubResult; presentIn: string[]; appName: (id: string) => string; onOpen: () => void; onInstall: () => void;
}) {
  const details = r.details ?? {};
  const targets = r.targets ?? [];
  return (
    <div className={`agent-card hub-card hub-card-${(r.kind ?? "skill").toLowerCase()}`} style={{ cursor: "default" }}>
      <div className="row" style={{ gap: 6 }}>
        <span className="chip hub-kind">{r.kind === "PLUGIN" ? "plugin" : "MCP"}</span>
        <strong style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{r.name}</strong><span className="spacer" />
        {r.version && <span className="chip">v{r.version}</span>}
      </div>
      {r.description && <small className="muted desc">{r.description}</small>}
      <div>
        {r.author && <span className="chip">{r.author}</span>}
        {details.Categoria && <span className="chip">{details.Categoria}</span>}
        {details.Tipo && <span className="chip">{details.Tipo}</span>}
        {details.Richiede && <span className="chip" title="Da inserire all'installazione">richiede {details.Richiede}</span>}
        {targets.length > 0 && <span className="chip">per {targets.map(appName).join(", ")}</span>}
        {presentIn.length > 0 && <span className="badge badge-ok" title="Lo stesso nome è già installato">già in: {presentIn.join(", ")}</span>}
      </div>
      <div className="row">
        {r.url && <button className="btn btn-small" onClick={onOpen}><Icon name="external" size={12} /> Apri</button>}
        <span className="spacer" />
        {targets.length > 0
          ? <button className="btn btn-small btn-primary" onClick={onInstall}>Dettagli e installa</button>
          : <span className="muted" style={{ fontSize: 12 }}>da configurare a mano</span>}
      </div>
    </div>
  );
}

function InstallDialog({ result, installed, providerName, onClose, onInstalled }: {
  result: HubResult; installed: HubInstalled | null; providerName: string; onClose: () => void;
  onInstalled: (outcome: HubInstallResult) => void;
}) {
  const api = useApi();
  const [preview, setPreview] = useState<HubPreview | null>(null);
  const [key, setKey] = useState(slug(result.name));
  const [targets, setTargets] = useState<string[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const installable = (installed?.apps ?? []).filter((a) => a.installable && a.id !== "aicos");

  useEffect(() => {
    api.hubPreview(result.provider, result.ref).then((p) => { setPreview(p); setKey(slug(p.name || result.name)); }).catch(setProblem);
  }, [api, result.provider, result.ref, result.name]);

  async function install() {
    setBusy(true);
    setProblem(null);
    try {
      onInstalled(await api.hubInstall(result.provider, result.ref, key, targets));
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={`Installa «${result.name}»`} onClose={onClose} wide>
      <div className="stack">
        <div className="muted" style={{ fontSize: 12.5 }}>
          Da <strong>{providerName}</strong>{result.source ? ` · ${result.source}` : ""}. La skill entra nella libreria di AI Company OS
          (associabile agli agenti) e, se scegli, anche nelle app qui sotto. Gli aggiornamenti futuri compaiono in Aggiornamenti.
        </div>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {!preview && !problem && <div className="muted">Leggo il pacchetto…</div>}
        {preview && (
          <>
            {(preview.warnings ?? []).length > 0 && (
              <div className="notice notice-warn" style={{ fontSize: 12.5 }}>
                {(preview.warnings ?? []).map((w) => <div key={w}><Icon name="warning" size={12} /> {w}</div>)}
              </div>
            )}
            <div className="row" style={{ fontSize: 12.5 }}>
              {preview.version && <span className="chip">v{preview.version}</span>}
              {preview.security && <span className={preview.security === "clean" ? "badge badge-ok" : "badge badge-warn"}>controllo: {preview.security}</span>}
              <span className="chip">{(preview.files ?? []).length} file</span>
              {preview.sourceUrl && <a href={preview.sourceUrl} target="_blank" rel="noreferrer"><Icon name="external" size={12} /> sorgente</a>}
            </div>
            {preview.description && <div style={{ whiteSpace: "pre-wrap" }}>{preview.description}</div>}
            <details>
              <summary style={{ cursor: "pointer" }}>File del pacchetto</summary>
              <ul className="mono" style={{ fontSize: 12 }}>{(preview.files ?? []).map((f) => <li key={f.path}>{f.path} <span className="muted">({f.size} B)</span></li>)}</ul>
            </details>
            <div className="section-title">SKILL.md</div>
            <pre className="doc" style={{ maxHeight: 260 }} aria-label="SKILL.md del pacchetto">{preview.skillMd ?? "(non leggibile)"}</pre>
            <Field label="Chiave nella libreria" hint="Minuscole, cifre e trattini. È anche il nome della cartella nelle app.">
              <input value={key} onChange={(e) => setKey(e.target.value)} />
            </Field>
            <div className="stack" style={{ gap: 6 }}>
              <div className="section-title">Installa anche in</div>
              {installable.length === 0 && <div className="muted" style={{ fontSize: 12.5 }}>Nessuna app con una cartella di skill personali su questo PC.</div>}
              <div className="row">
                {installable.map((a) => (
                  <label key={a.id} className="chip hub-check">
                    <input type="checkbox" checked={targets.includes(a.id)}
                           onChange={(e) => setTargets((t) => e.target.checked ? [...t, a.id] : t.filter((x) => x !== a.id))} />
                    {" "}{a.name}
                  </label>
                ))}
              </div>
              <div className="muted" style={{ fontSize: 12 }}>
                Claude Desktop riceve le skill dal tuo account: dopo l'installazione, dalla skill in libreria puoi scaricare
                lo .zip da caricare in Impostazioni → Capacità.
              </div>
            </div>
          </>
        )}
        <div className="row modal-actions"><span className="spacer" />
          <button className="btn" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" disabled={!preview || busy || key.length < 2} onClick={install}>
            {busy ? "Installo…" : "Installa"}</button>
        </div>
      </div>
    </Modal>
  );
}
