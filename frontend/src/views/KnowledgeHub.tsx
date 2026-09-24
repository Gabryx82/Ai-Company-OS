import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi } from "../context";
import { ApiProblem, type Versioned } from "../api/client";
import type { AgentProfile, AgentTemplate, LibraryInfo, Project, Resource, ResourceFile } from "../api/types";
import { Field, Modal, ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";

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

/**
 * The Framework / Skill / Knowledge Explorer (directive §22), file-based since
 * PHASE 19 (ADR-026): every skill and knowledge entry is a Markdown file you can
 * read, edit here or in your editor, version with git, and still import from the
 * web -- then equip an agent with it or adopt it in a project.
 */
export function KnowledgeHub() {
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
      search();
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
        <div><h1>Knowledge Hub</h1>
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
                                     onChanged={(message) => { setDone(message); search(); }} />}
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
                                  onCreated={(r) => { setCreating(false); setDone(`Creata ${r.name}`); search(); setSelected(r.key); }} />}
      {importing && <ImportModal onClose={() => setImporting(false)}
                                 onImported={(r) => { setImporting(false); setDone(`Importata ${r.name}`); search(); setSelected(r.key); }} />}
    </div>
  );
}

function ResourceDetail({ resourceKey, onClose, onChanged }: {
  resourceKey: string; onClose: () => void; onChanged: (message: string) => void;
}) {
  const api = useApi();
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
        <div className="row"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
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
        <div className="row"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit" disabled={busy}>{busy ? "Importo…" : "Importa"}</button></div>
      </form>
    </Modal>
  );
}
