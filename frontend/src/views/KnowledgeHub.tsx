import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi } from "../context";
import { ApiProblem, type Versioned } from "../api/client";
import type { AgentProfile, AgentTemplate, HubInstalled, LibraryInfo, Project, Resource, ResourceFile } from "../api/types";
import { Field, Modal, ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";
import { ConfirmDeletion, DeleteButton } from "../components/Deletion";
import { useIsAdmin } from "../context";
import { InstalledPanel } from "./knowledge/InstalledPanel";
import { MarketplacePanel } from "./knowledge/MarketplacePanel";
import { UpdatesPanel } from "./knowledge/UpdatesPanel";
import { ActivityPanel } from "./knowledge/ActivityPanel";
import { CopiesSection } from "./knowledge/CopiesSection";

const KINDS = [
  { key: "", label: "Tutto" }, { key: "SKILL", label: "Skill" }, { key: "KNOWLEDGE", label: "Knowledge" },
  { key: "FRAMEWORK", label: "Framework" }, { key: "MCP", label: "MCP" }, { key: "TOOL", label: "Tool" },
];
const ORIGIN_LABEL: Record<string, string> = { CATALOG: "catalogo", FILE: "dalla cartella", WEB: "importata", USER: "creata da te" };

const NEW_TEMPLATE = `---
key: nuova-skill
name: Nuova skill
kind: SKILL
description: Cosa sa fare l'agente con questa skill.
tags: [esempio]
---

# Nuova skill

## Quando usarla

## Istruzioni

- Regola 1
- Regola 2

## Da evitare
`;

type HubTab = "installed" | "marketplace" | "updates" | "activity" | "library";
const TABS: { key: HubTab; label: string }[] = [
  { key: "installed", label: "Installate" }, { key: "marketplace", label: "Marketplace" },
  { key: "updates", label: "Aggiornamenti" }, { key: "activity", label: "Attività" },
  { key: "library", label: "Libreria e catalogo" },
];
const TAB_KEY = "aicos.knowledgeHub.tab";

function rememberedTab(): HubTab {
  try {
    const value = localStorage.getItem(TAB_KEY);
    return TABS.some((t) => t.key === value) ? value as HubTab : "installed";
  } catch {
    return "installed";
  }
}

/**
 * The Knowledge Hub (PHASE 31, ADR-036): one place for every skill and
 * knowledge item of the operator's agents -- the library of AI Company OS,
 * Claude Code, Claude Desktop, Codex and the other applications on this
 * machine -- kept in sync, checked for updates, and fed from online
 * marketplaces. The library and its catalog (PHASE 19) are the last tab, as
 * they were.
 */
export function KnowledgeHub() {
  const api = useApi();
  const [tab, setTabState] = useState<HubTab>(rememberedTab);
  const [installed, setInstalled] = useState<HubInstalled | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const load = useCallback((rescan = false) => api.hubInstalled(rescan).then(setInstalled).catch(setProblem), [api]);
  // Opening the page reads the applications' folders again, and so does every minute the page stays in view:
  // what is installed, removed or changed in Claude Code, Codex & co. shows up here without a click.
  useEffect(() => {
    load(true);
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") load(true);
    }, 60_000);
    return () => window.clearInterval(timer);
  }, [load]);

  function setTab(next: HubTab) {
    setTabState(next);
    try {
      localStorage.setItem(TAB_KEY, next);
    } catch {
      // not remembered; the default stays
    }
  }

  function changed(message: string) {
    setDone(message);
    load(true);
    setRefreshKey((k) => k + 1);
  }

  async function sync() {
    setBusy("sync");
    setProblem(null);
    try {
      const report = await api.hubSync();
      setDone(report.firstRun
        ? `Prima sincronizzazione: ${report.total} skill e knowledge fotografate nelle app`
        : `Sincronizzato: ${report.added} installate, ${report.removed} rimosse, ${report.modified} modificate nelle app`
          + ((report.libraryCreated ?? []).length ? ` · ${report.libraryCreated.length} nuove nella cartella della libreria` : "")
          + ((report.libraryInvalid ?? []).length ? ` · ${report.libraryInvalid.length} file della libreria non validi` : ""));
      await load();
      setRefreshKey((k) => k + 1);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(null);
    }
  }

  async function checkUpdates() {
    setBusy("check");
    setProblem(null);
    try {
      const updates = await api.hubCheckUpdates();
      setDone(`Verifica completata: ${updates.items.length} aggiornament${updates.items.length === 1 ? "o" : "i"} disponibil${updates.items.length === 1 ? "e" : "i"}`);
      setTab("updates");
      await load();
      setRefreshKey((k) => k + 1);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(null);
    }
  }

  const summary = installed?.summary;
  const minutes = installed && installed.syncIntervalSeconds > 0 ? Math.round(installed.syncIntervalSeconds / 60) : null;

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Knowledge Hub</h1>
          <p>Tutte le skill e le knowledge dei tuoi agenti in un posto: la libreria di AI Company OS, Claude Code, Claude Desktop,
            Codex e le altre app di questo PC, sincronizzate, con gli aggiornamenti e i marketplace online.</p></div>
        <div className="row">
          <button className="btn" onClick={sync} disabled={busy !== null} title="Rilegge la libreria e le cartelle di tutte le app">
            <Icon name="refresh" size={14} /> {busy === "sync" ? "Sincronizzo…" : "Sincronizza ora"}</button>
          <button className="btn btn-primary" onClick={checkUpdates} disabled={busy !== null}>
            <Icon name="sparkle" size={14} /> {busy === "check" ? "Verifico…" : "Verifica aggiornamenti"}</button>
        </div>
      </div>
      <div className="hub-kpis">
        <div className="card kpi"><div className="kpi-icon" style={{ background: "var(--accent)" }}><Icon name="knowledge" /></div>
          <div><strong>{summary?.total ?? "…"}</strong><small>skill e knowledge in {summary?.apps ?? "…"} app</small></div></div>
        <div className="card kpi"><div className="kpi-icon" style={{ background: "var(--accent-2)" }}><Icon name="folder" /></div>
          <div><strong>{summary?.libraryEntries ?? "…"}</strong><small>nella libreria · {summary?.copies ?? 0} copie nelle app</small></div></div>
        <button className="card kpi hub-kpi-button" onClick={() => setTab("updates")}>
          <div className="kpi-icon" style={{ background: summary && summary.updates > 0 ? "#d97706" : "#059669" }}><Icon name="refresh" /></div>
          <div><strong>{summary?.updates ?? "…"}</strong><small>aggiornamenti da fare</small></div></button>
        <div className="card kpi"><div className="kpi-icon" style={{ background: "#0e7490" }}><Icon name="clock" /></div>
          <div><strong style={{ fontSize: 16 }}>{installed ? new Date(installed.lastSync ?? installed.scannedAt).toLocaleTimeString() : "…"}</strong>
            <small>ultima lettura{minutes ? ` · automatica ogni ${minutes} min` : ""}</small></div></div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {done && <div className="notice"><Icon name="check" size={13} /> {done}</div>}
      <div className="tabs" role="tablist" aria-label="Sezioni del Knowledge Hub">
        {TABS.map((t) => (
          <button key={t.key} role="tab" className="tab" aria-selected={tab === t.key} onClick={() => setTab(t.key)}>
            {t.label}{t.key === "updates" && summary && summary.updates > 0 ? ` (${summary.updates})` : ""}</button>
        ))}
      </div>
      {tab === "installed" && (installed
        ? <InstalledPanel installed={installed} onChanged={changed}
                          renderLibraryDetail={(key, close) => (
                            <ResourceDetail key={key} resourceKey={key} onClose={close} onChanged={changed}
                                            onDeleted={(message) => { close(); changed(message); }} />
                          )} />
        : !problem && <div className="muted">Leggo le skill delle app…</div>)}
      {tab === "marketplace" && <MarketplacePanel installed={installed} onInstalled={changed} />}
      {tab === "updates" && <UpdatesPanel apps={installed?.apps ?? []} refreshKey={refreshKey} onApplied={changed} />}
      {tab === "activity" && <ActivityPanel apps={installed?.apps ?? []} refreshKey={refreshKey} />}
      {tab === "library" && <LibraryPanel onChanged={() => load(true)} />}
    </div>
  );
}

/**
 * The Framework / Skill / Knowledge Explorer (directive §22), file-based since
 * PHASE 19 (ADR-026): every skill and knowledge entry is a Markdown file you can
 * read, edit here or in your editor, version with git, and still import from the
 * web -- then equip an agent with it or adopt it in a project. Since PHASE 31 the
 * Knowledge Hub's "Libreria e catalogo" tab.
 */
function LibraryPanel({ onChanged }: { onChanged?: () => void }) {
  const api = useApi();
  const [kind, setKind] = useState("");
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Resource[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [info, setInfo] = useState<LibraryInfo | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);
  const [templates, setTemplates] = useState<AgentTemplate[]>([]);

  const search = useCallback(() => api.resources(kind || undefined, query || undefined)
    .then((r) => setResults(r.filter((x) => x.kind !== "TEMPLATE_PROVIDER"))).catch(setProblem), [api, kind, query]);
  const refreshed = useCallback(() => { search(); onChanged?.(); }, [search, onChanged]);

  useEffect(() => {
    const timer = window.setTimeout(search, 200);
    return () => window.clearTimeout(timer);
  }, [search]);

  useEffect(() => {
    api.library().then(setInfo).catch(() => setInfo(null));
    api.agentTemplates().then(setTemplates).catch(() => setTemplates([]));
  }, [api]);

  async function sync() {
    setProblem(null);
    try {
      const report = await api.syncLibrary();
      setDone(`Cartella riletta: ${report.created.length} nuove, ${report.updated.length} aggiornate`
        + (report.invalid.length ? `, ${report.invalid.length} non valide (${report.invalid.join("; ")})` : ""));
      refreshed();
    } catch (error) {
      setProblem(error);
    }
  }

  async function install(key: string) {
    setProblem(null);
    try {
      const result = await api.installAgentTemplate(key);
      setDone(result.map((r) => `${r.name}: ${r.outcome === "CREATED" ? "creato" : "già presente"}`).join(" · "));
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <div className="stack">
      <div className="page-head">
        <div><h2>Libreria e catalogo</h2>
          <p>Skill e knowledge sono file Markdown che puoi leggere e modificare qui o nel tuo editor, versionare con git e importare dal web.
            Framework, MCP e tool sono voci di catalogo con la loro configurazione.</p></div>
        <div className="row">
          <button className="btn" onClick={sync} title="Indicizza i file aggiunti o modificati a mano"><Icon name="refresh" size={14} /> Rileggi cartella</button>
          <button className="btn" onClick={() => setImporting(true)}><Icon name="external" size={14} /> Importa da URL</button>
          <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={14} /> Nuova skill</button>
        </div>
      </div>
      {info && (
        <div className="notice">
          <Icon name="folder" size={13} /> Cartella della libreria: <code>{info.root}</code>{!info.exists && " (verrà creata al primo file)"}
          <span className="muted"> · struttura {info.layout} · {info.skills} skill, {info.knowledge} knowledge</span>
        </div>
      )}
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {done && <div className="notice"><Icon name="check" size={13} /> {done}</div>}
      <div className="row">
        <div className="search" style={{ maxWidth: 380 }}><Icon name="search" size={15} />
          <input placeholder="Cerca per nome, descrizione o tag…" value={query} onChange={(e) => setQuery(e.target.value)} /></div>
        <div className="tabs">{KINDS.map((k) => <button key={k.key} className="tab" aria-selected={kind === k.key} onClick={() => setKind(k.key)}>{k.label}</button>)}</div>
      </div>
      <div className={selected ? "split" : ""}>
        <div className="tile-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))", alignContent: "start" }}>
          {results.map((r) => (
            <button key={r.key} className="agent-card" aria-pressed={selected === r.key} onClick={() => setSelected(r.key)}>
              <div className="row"><span className="chip">{r.kind}</span><strong>{r.name}</strong></div>
              {r.description && <small className="muted">{r.description}</small>}
              <div>
                {(r.tags ?? []).map((t) => <span key={t} className="chip">{t}</span>)}
                <span className="chip">{ORIGIN_LABEL[r.origin] ?? r.origin}</span>
                {r.fileBacked && <span className={r.filePath ? "badge badge-ok" : "badge"}>{r.filePath ? "file" : "senza file"}</span>}
              </div>
            </button>
          ))}
          {results.length === 0 && <div className="muted">Nessuna voce.</div>}
        </div>
        {selected && <ResourceDetail key={selected} resourceKey={selected} onClose={() => setSelected(null)}
                                     onChanged={(message) => { setDone(message); refreshed(); }}
                                     onDeleted={(message) => { setSelected(null); setDone(message); refreshed(); }} />}
      </div>

      <div className="section-title">Template di agenti</div>
      <div className="tile-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))" }}>
        {templates.map((t) => (
          <div key={t.key} className="tile" style={{ justifyItems: "stretch", textAlign: "left", cursor: "default" }}>
            <div className="row"><Icon name="agents" /><strong>{t.name}</strong></div>
            <div className="muted" style={{ fontSize: 12.5 }}>{t.specialization}</div>
            {(t.children ?? []).length > 0 && <div style={{ fontSize: 12 }}>Sottoagenti: {(t.children ?? []).map((c) => <span key={c.key} className="chip">{c.name}</span>)}</div>}
            <div className="row"><span className="mono muted" style={{ fontSize: 11 }}>{t.model}</span><span className="spacer" />
              <button className="btn btn-small" onClick={() => install(t.key)}>Installa</button></div>
          </div>
        ))}
      </div>

      {creating && <NewEntryModal onClose={() => setCreating(false)}
                                  onCreated={(r) => { setCreating(false); setDone(`Creata ${r.name}`); refreshed(); setSelected(r.key); }} />}
      {importing && <ImportModal onClose={() => setImporting(false)}
                                 onImported={(r) => { setImporting(false); setDone(`Importata ${r.name}`); refreshed(); setSelected(r.key); }} />}
    </div>
  );
}

export function ResourceDetail({ resourceKey, onClose, onChanged, onDeleted }: {
  resourceKey: string; onClose: () => void; onChanged: (message: string) => void; onDeleted: (message: string) => void;
}) {
  const api = useApi();
  const isAdmin = useIsAdmin();
  const [deleting, setDeleting] = useState(false);
  const loadPreview = useCallback(() => api.resourceDeletionPreview(resourceKey), [api, resourceKey]);
  const [resource, setResource] = useState<Resource | null>(null);
  const [file, setFile] = useState<Versioned<ResourceFile> | null>(null);
  const [draft, setDraft] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [agents, setAgents] = useState<AgentProfile[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [target, setTarget] = useState("");

  const load = useCallback(async () => {
    const all = await api.resources();
    const found = all.find((r) => r.key === resourceKey) ?? null;
    setResource(found);
    if (found?.fileBacked) {
      const read = await api.resourceFile(resourceKey);
      setFile(read);
      setDraft(read.body.content ?? "");
    }
  }, [api, resourceKey]);

  useEffect(() => {
    load().catch(setProblem);
    api.agentProfiles().then(setAgents).catch(() => undefined);
    api.projects().then((p) => setProjects(p.filter((x) => x.status === "ACTIVE"))).catch(() => undefined);
  }, [api, load]);

  async function run(action: () => Promise<unknown>, message: string) {
    setProblem(null);
    try {
      await action();
      await load();
      onChanged(message);
    } catch (error) {
      setProblem(error);
      if (error instanceof ApiProblem && error.isStale) await load().catch(() => undefined);
    }
  }

  async function equip() {
    if (!target || !resource) return;
    const [type, id] = target.split(":");
    await run(() => type === "agent" ? api.attachResource(Number(id), resource.key) : api.adoptResource(Number(id), resource.key),
      `${resource.name} → ${type === "agent" ? agents.find((a) => a.agentId === Number(id))?.name : projects.find((p) => p.id === Number(id))?.name}`);
  }

  if (!resource) return <div className="card card-body muted">Carico…</div>;
  const dirty = file?.body.exists && draft !== (file.body.content ?? "");

  return (
    <div className="card" style={{ position: "sticky", top: 80 }}>
      <div className="card-head">
        <span className="chip">{resource.kind}</span><h2>{resource.name}</h2><span className="spacer" />
        {isAdmin && <DeleteButton label={`Elimina ${resource.name}`} onClick={() => setDeleting(true)} />}
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi"><Icon name="close" size={14} /></button>
      </div>
      <div className="card-body stack">
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {resource.description && <div className="muted">{resource.description}</div>}
        <div className="row" style={{ fontSize: 12 }}>
          <span className="chip">{ORIGIN_LABEL[resource.origin] ?? resource.origin}</span>
          {resource.sourceUrl && <a href={resource.sourceUrl} target="_blank" rel="noreferrer"><Icon name="external" size={12} /> fonte</a>}
        </div>

        {resource.fileBacked && file && (
          <>
            <div className="notice" style={{ fontSize: 12.5 }}>
              <Icon name="file" size={12} /> <code>{file.body.absolutePath}</code>
              {file.body.exists ? <span className="muted"> · modificato {when(file.body.modified)}</span> : <span className="muted"> · il file non esiste ancora</span>}
            </div>
            {!file.body.exists ? (
              <button className="btn btn-primary" onClick={() => run(() => api.materializeResourceFile(resource.key), `File creato per ${resource.name}`)}>
                Crea il file da modificare</button>
            ) : (
              <>
                <textarea aria-label="Contenuto del file" className="mono" value={draft} onChange={(e) => setDraft(e.target.value)}
                          style={{ minHeight: 320, fontSize: 12.5 }} spellCheck={false} />
                <div className="row">
                  <button className="btn" onClick={() => run(() => api.openResource(resource.key), `Aperta in VS Code: ${resource.name}`)}>
                    <Icon name="external" size={13} /> Apri in VS Code</button>
                  <button className="btn" onClick={() => load().catch(setProblem)} title="Rilegge il file dal disco">Ricarica</button>
                  <span className="spacer" />
                  {dirty && <button className="btn" onClick={() => setDraft(file.body.content ?? "")}>Annulla</button>}
                  <button className="btn btn-primary" disabled={!dirty}
                          onClick={() => run(() => api.saveResourceFile(resource.key, file.etag, draft), `Salvato ${resource.key}`)}>Salva file</button>
                </div>
                <div className="muted" style={{ fontSize: 12 }}>Il frontmatter tra le righe <code>---</code> è l'indice (nome, descrizione, tag); il resto sono le istruzioni che l'agente riceve.</div>
              </>
            )}
          </>
        )}
        {resource.kind === "SKILL" && file?.body.exists && <CopiesSection skillKey={resource.key} onChanged={onChanged} />}
        {!resource.fileBacked && resource.configuration && (
          <div><div className="section-title">Configurazione</div><pre className="doc">{resource.configuration}</pre></div>
        )}

        <div className="section-title">Associa</div>
        <div className="row">
          <select value={target} onChange={(e) => setTarget(e.target.value)} aria-label="Destinazione">
            <option value="">Scegli un agente o un progetto…</option>
            <optgroup label="Agenti">{agents.map((a) => <option key={a.agentId} value={`agent:${a.agentId}`}>{a.name} ({a.role})</option>)}</optgroup>
            <optgroup label="Progetti">{projects.map((p) => <option key={p.id} value={`project:${p.id}`}>{p.name}</option>)}</optgroup>
          </select>
          <button className="btn btn-primary" disabled={!target} onClick={equip}>Associa</button>
        </div>
      </div>
      {deleting && (
        <ConfirmDeletion title="Elimina dal catalogo" subject={resource.name} loadPreview={loadPreview}
                         onClose={() => setDeleting(false)}
                         onConfirm={async () => {
                           await api.deleteResource(resource.key, resource.version);
                           onDeleted(`Eliminata «${resource.name}».`);
                         }} />
      )}
    </div>
  );
}

function NewEntryModal({ onClose, onCreated }: { onClose: () => void; onCreated: (r: Resource) => void }) {
  const api = useApi();
  const [content, setContent] = useState(NEW_TEMPLATE);
  const [problem, setProblem] = useState<unknown>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    try { onCreated(await api.createLibraryEntry(content)); } catch (error) { setProblem(error); }
  }
  return (
    <Modal title="Nuova skill o knowledge" onClose={onClose} wide>
      <form className="stack" onSubmit={submit}>
        <div className="muted" style={{ fontSize: 12.5 }}>
          Scrivi il file come lo scriveresti a mano: <code>key</code> (minuscole e trattini), <code>name</code>,
          <code>kind</code> (SKILL o KNOWLEDGE), <code>description</code>, <code>tags</code>, poi le istruzioni.
        </div>
        <textarea aria-label="Contenuto" className="mono" value={content} onChange={(e) => setContent(e.target.value)}
                  style={{ minHeight: 340, fontSize: 12.5 }} spellCheck={false} />
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row modal-actions"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit">Crea il file</button></div>
      </form>
    </Modal>
  );
}

function ImportModal({ onClose, onImported }: { onClose: () => void; onImported: (r: Resource) => void }) {
  const api = useApi();
  const [url, setUrl] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try { onImported(await api.importSkill(url)); } catch (error) { setProblem(error); } finally { setBusy(false); }
  }
  return (
    <Modal title="Importa una skill dal web" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Field label="URL del file Markdown" hint="https:// di un host pubblico. Un link «blob» di GitHub viene convertito nel file raw. Il file importato resta modificabile.">
          <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://github.com/…/SKILL.md" required />
        </Field>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row modal-actions"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit" disabled={busy}>{busy ? "Importo…" : "Importa"}</button></div>
      </form>
    </Modal>
  );
}
