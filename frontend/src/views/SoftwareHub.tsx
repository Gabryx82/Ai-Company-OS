import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { useApi } from "../context";
import type { Availability, Project, Software, SoftwareCategory } from "../api/types";
import { ProblemNote } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon } from "../components/icons";

export const CATEGORY_LABEL: Record<SoftwareCategory, string> = {
  AGENTIC_IDE: "Agentic IDE", IDE: "IDE", EDITOR: "Editor", TERMINAL: "Terminale", AI_CLOUD: "AI Cloud",
  AI_LOCAL: "AI Locale", DATABASE: "Database", API: "API", INFRASTRUCTURE: "Infrastruttura", DIAGRAM: "Diagrammi",
  DESIGN: "Design", THREE_D: "3D", VCS: "Repository", HOSTING: "Hosting", PRODUCTIVITY: "Produttività",
};

export const AVAILABILITY: Record<Availability, { label: string; dot: string }> = {
  INSTALLED: { label: "Installato", dot: "dot-ok" },
  RUNNING: { label: "In esecuzione", dot: "dot-ok" },
  STOPPED: { label: "Fermo", dot: "dot-warn" },
  WEB: { label: "Web", dot: "dot-ok" },
  NOT_INSTALLED: { label: "Non installato", dot: "dot-off" },
  INCOMPATIBLE_HARDWARE: { label: "Hardware incompatibile", dot: "dot-danger" },
  UNKNOWN: { label: "Sconosciuto", dot: "dot-off" },
};

/** Opens a web entry in a named window the console reuses: one click, always the same window. */
export function openWeb(software: Software) {
  if (!software.url) return;
  const opened = window.open(software.url, `aicos-${software.key}`);
  if (opened) opened.opener = null;
}

/** Launch through the control plane, and a line that says exactly what ran. */
export function useLauncher() {
  const api = useApi();
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const launch = useCallback((software: Software, projectId?: number) => {
    if (software.launchKind === "WEB") { openWeb(software); return; }
    setResult(null);
    api.launch(software.key, projectId)
      .then((r) => setResult({ ok: true, text: `Avviato: ${r.command.join(" ")}` }))
      .catch((problem) => setResult({ ok: false, text: problem instanceof Error ? problem.message : String(problem) }));
  }, [api]);
  const outcome: ReactNode = result
    ? <div className={result.ok ? "notice" : "notice notice-danger"} style={{ margin: 12 }}>
        <span className="mono" style={{ fontSize: 12 }}>{result.text}</span></div>
    : null;
  return { launch, outcome };
}

export function SoftwareHub({ selected, navigate }: { selected: string | null; navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const [all, setAll] = useState<Software[] | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [category, setCategory] = useState<SoftwareCategory | "ALL" | "TARGETS">("ALL");
  const [query, setQuery] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const { launch, outcome } = useLauncher();
  const [projectId, setProjectId] = useState<number | "">("");

  const load = useCallback(() => {
    api.software().then(setAll).catch(setProblem);
  }, [api]);
  useEffect(() => {
    load();
    api.projects().then((p) => setProjects(p.filter((x) => x.status === "ACTIVE"))).catch(() => undefined);
  }, [api, load]);

  const categories = useMemo(() => [...new Set((all ?? []).map((s) => s.category))], [all]);
  const needle = query.trim().toLowerCase();
  const shown = (all ?? []).filter((s) =>
    (category === "ALL" || (category === "TARGETS" ? s.executionTarget : s.category === category))
    && (!needle || [s.name, s.role, s.purpose ?? "", ...s.capabilities].join(" ").toLowerCase().includes(needle)));
  const current = all?.find((s) => s.key === selected) ?? null;

  const refresh = () => api.refreshSoftware().then(load).catch(setProblem);

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Software Hub</h1>
          <p>Tutti gli strumenti dell'ecosistema: ruolo, capability, stato rilevato su questa macchina, apertura.</p>
        </div>
        <button className="btn" onClick={refresh}><Icon name="refresh" size={14} /> Rileva di nuovo</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />

      <div className="row">
        <div className="search" style={{ maxWidth: 360 }}>
          <Icon name="search" size={15} />
          <input placeholder="Cerca un software o una capability…" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <div className="tabs" role="tablist">
          <button className="tab" role="tab" aria-selected={category === "ALL"} onClick={() => setCategory("ALL")}>Tutti</button>
          <button className="tab" role="tab" aria-selected={category === "TARGETS"} onClick={() => setCategory("TARGETS")}>Execution target</button>
          {categories.map((c) => (
            <button key={c} className="tab" role="tab" aria-selected={category === c} onClick={() => setCategory(c)}>
              {CATEGORY_LABEL[c]}
            </button>
          ))}
        </div>
      </div>

      <div className={current ? "split" : ""}>
        <div className="tile-grid">
          {!all && <div className="muted">Rilevamento in corso…</div>}
          {shown.map((s) => (
            <button key={s.key} className="tile" onClick={() => navigate("software", s.key)}
                    style={s.key === selected ? { borderColor: "var(--accent)" } : undefined}>
              <span className={`dot ${AVAILABILITY[s.availability].dot}`} title={AVAILABILITY[s.availability].label} />
              <AppIcon software={s} size={46} />
              <h3>{s.name}</h3>
              <span className="role">{s.role}</span>
              <span className="chip">{CATEGORY_LABEL[s.category]}</span>
            </button>
          ))}
        </div>

        {current && (
          <div className="card" style={{ position: "sticky", top: 80 }}>
            <div className="card-head">
              <AppIcon software={current} size={40} />
              <div><h2>{current.name}</h2><span className="muted" style={{ fontSize: 12 }}>{current.role}</span></div>
              <span className="spacer" />
              <button className="btn btn-small" onClick={() => navigate("software")} aria-label="Chiudi"><Icon name="close" size={14} /></button>
            </div>
            <div className="card-body stack">
              <div className="row">
                <span className={`dot ${AVAILABILITY[current.availability].dot}`} />
                <strong>{AVAILABILITY[current.availability].label}</strong>
                {current.executionTarget && <span className="badge badge-accent">Execution target</span>}
                {current.embeddable && <span className="badge badge-ok">Incorporabile</span>}
              </div>
              {current.purpose && <p style={{ margin: 0 }}>{current.purpose}</p>}
              <div className="row">
                {current.launchKind === "WEB" && (
                  <button className="btn btn-primary" onClick={() => openWeb(current)}><Icon name="external" size={14} /> Apri</button>
                )}
                {current.launchKind === "LOCAL_SERVICE" && current.embeddable && (
                  <button className="btn btn-primary" onClick={() => navigate("integrations", current.key)}>
                    <Icon name="integrations" size={14} /> Apri nella console</button>
                )}
                {current.launchKind !== "WEB" && (
                  <button className="btn" disabled={!current.launchable} onClick={() => launch(current, projectId || undefined)}>
                    <Icon name="play" size={14} /> {current.launchKind === "LOCAL_SERVICE" ? "Avvia servizio" : "Apri app"}</button>
                )}
                {(current.openFolder || current.launchKind === "CLI") && projects.length > 0 && (
                  <select style={{ width: "auto" }} value={projectId} aria-label="Cartella di progetto"
                          onChange={(e) => setProjectId(e.target.value ? Number(e.target.value) : "")}>
                    <option value="">senza progetto</option>
                    {projects.map((p) => <option key={p.id} value={p.id}>nel progetto {p.name}</option>)}
                  </select>
                )}
              </div>
              {outcome}
              {current.embedNote && <div className="notice">{current.embedNote}</div>}
              {current.availabilityDetail && current.availability !== "WEB" && (
                <div className="muted" style={{ fontSize: 12 }}>{current.availabilityDetail}</div>
              )}
              <dl className="kv">
                <dt>Categoria</dt><dd>{CATEGORY_LABEL[current.category]}</dd>
                <dt>Apertura</dt><dd>{current.launchKind}{current.openFolder ? " · apre la cartella del progetto" : ""}</dd>
                {current.resolvedExecutable && <><dt>Eseguibile</dt><dd className="mono">{current.resolvedExecutable}</dd></>}
                {current.appId && <><dt>AppID</dt><dd className="mono">{current.appId}</dd></>}
                {current.cliCommand && <><dt>Comando</dt><dd className="mono">{current.cliCommand}</dd></>}
                {current.url && <><dt>URL</dt><dd className="mono">{current.url}</dd></>}
                {current.requirements && <><dt>Requisiti</dt><dd>{current.requirements}</dd></>}
                {current.configuration && <><dt>Configurazione</dt><dd>{current.configuration}</dd></>}
              </dl>
              {current.capabilities.length > 0 && (
                <div><div className="section-title">Capability</div>{current.capabilities.map((c) => <span key={c} className="chip">{c}</span>)}</div>
              )}
              {current.projectTypes.length > 0 && (
                <div><div className="section-title">Tipi di progetto</div>{current.projectTypes.map((c) => <span key={c} className="chip">{c}</span>)}</div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
