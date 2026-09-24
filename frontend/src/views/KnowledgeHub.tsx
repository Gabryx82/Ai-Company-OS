import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { AgentProfile, AgentTemplate, Project, Resource } from "../api/types";
import { ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

const KINDS = [
  { key: "", label: "Tutto" }, { key: "SKILL", label: "Skills" }, { key: "FRAMEWORK", label: "Framework" },
  { key: "KNOWLEDGE", label: "Knowledge" }, { key: "MCP", label: "MCP" }, { key: "TOOL", label: "Tool" },
];

/**
 * The Framework / Skill / Knowledge Explorer (directive §22): search the
 * catalog, equip an agent, adopt into a project -- and install agent templates.
 */
export function KnowledgeHub() {
  const api = useApi();
  const [kind, setKind] = useState("");
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Resource[]>([]);
  const [agents, setAgents] = useState<AgentProfile[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [templates, setTemplates] = useState<AgentTemplate[]>([]);
  const [target, setTarget] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      api.resources(kind || undefined, query || undefined).then((r) => setResults(r.filter((x) => x.kind !== "TEMPLATE_PROVIDER")))
        .catch(setProblem);
    }, 200);
    return () => window.clearTimeout(timer);
  }, [api, kind, query]);

  useEffect(() => {
    api.agentProfiles().then(setAgents).catch(() => undefined);
    api.projects().then((p) => setProjects(p.filter((x) => x.status === "ACTIVE"))).catch(() => undefined);
    api.agentTemplates().then(setTemplates).catch(() => undefined);
  }, [api]);

  async function equip(resource: Resource) {
    if (!target) return;
    setProblem(null);
    const [type, id] = target.split(":");
    try {
      if (type === "agent") await api.attachResource(Number(id), resource.key);
      else await api.adoptResource(Number(id), resource.key);
      setDone(`${resource.name} → ${type === "agent" ? agents.find((a) => a.agentId === Number(id))?.name : projects.find((p) => p.id === Number(id))?.name}`);
    } catch (error) {
      setProblem(error);
    }
  }

  async function install(key: string) {
    setProblem(null);
    try {
      const result = await api.installAgentTemplate(key);
      setDone(result.map((r) => `${r.name}: ${r.outcome === "CREATED" ? "creato" : "già presente"}`).join(" · "));
      api.agentProfiles().then(setAgents).catch(() => undefined);
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Knowledge Hub</h1><p>Skills, framework, knowledge, MCP, tool e template di agenti: cercali e associali agli agenti o ai progetti.</p></div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {done && <div className="notice"><Icon name="check" size={13} /> {done}</div>}
      <div className="row">
        <div className="search" style={{ maxWidth: 380 }}><Icon name="search" size={15} />
          <input placeholder="Cerca per nome, descrizione o tag…" value={query} onChange={(e) => setQuery(e.target.value)} /></div>
        <div className="tabs">{KINDS.map((k) => <button key={k.key} className="tab" aria-selected={kind === k.key} onClick={() => setKind(k.key)}>{k.label}</button>)}</div>
        <span className="spacer" />
        <select value={target} onChange={(e) => setTarget(e.target.value)} style={{ width: "auto" }} aria-label="Destinazione">
          <option value="">Associa a…</option>
          <optgroup label="Agenti">{agents.map((a) => <option key={a.agentId} value={`agent:${a.agentId}`}>{a.name}</option>)}</optgroup>
          <optgroup label="Progetti">{projects.map((p) => <option key={p.id} value={`project:${p.id}`}>{p.name}</option>)}</optgroup>
        </select>
      </div>
      <div className="tile-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))" }}>
        {results.map((r) => (
          <div key={r.key} className="tile" style={{ justifyItems: "stretch", textAlign: "left", cursor: "default" }}>
            <div className="row"><span className="chip">{r.kind}</span><strong>{r.name}</strong></div>
            {r.description && <div className="muted" style={{ fontSize: 12.5 }}>{r.description}</div>}
            <div>{r.tags.map((t) => <span key={t} className="chip">{t}</span>)}</div>
            {r.configuration && <code style={{ fontSize: 11 }}>{r.configuration}</code>}
            <div className="row">
              {r.sourceUrl && <a className="btn btn-small" href={r.sourceUrl} target="_blank" rel="noreferrer"><Icon name="external" size={12} /> Fonte</a>}
              <span className="spacer" />
              <button className="btn btn-small btn-primary" disabled={!target} onClick={() => equip(r)}>Associa</button>
            </div>
          </div>
        ))}
      </div>
      <div className="section-title">Template di agenti</div>
      <div className="tile-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))" }}>
        {templates.map((t) => (
          <div key={t.key} className="tile" style={{ justifyItems: "stretch", textAlign: "left", cursor: "default" }}>
            <div className="row"><Icon name="agents" /><strong>{t.name}</strong></div>
            <div className="muted" style={{ fontSize: 12.5 }}>{t.specialization}</div>
            {t.children.length > 0 && <div style={{ fontSize: 12 }}>Sottoagenti: {t.children.map((c) => <span key={c.key} className="chip">{c.name}</span>)}</div>}
            <div className="row"><span className="mono muted" style={{ fontSize: 11 }}>{t.model}</span><span className="spacer" />
              <button className="btn btn-small" onClick={() => install(t.key)}>Installa</button></div>
          </div>
        ))}
      </div>
    </div>
  );
}
