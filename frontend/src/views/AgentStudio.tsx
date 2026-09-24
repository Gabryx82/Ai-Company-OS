import { useCallback, useEffect, useMemo, useState, type ReactElement } from "react";
import { useApi } from "../context";
import type { AgentProfile, ModelInfo, Resource, Software } from "../api/types";
import { Field, ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

const KIND_LABEL: Record<string, string> = {
  SKILL: "Skill", KNOWLEDGE: "Knowledge", MCP: "MCP", TOOL: "Tool", FRAMEWORK: "Framework", TEMPLATE_PROVIDER: "Template",
};

/**
 * The agent ecosystem (ADR-023): agents and sub-agents as a hierarchy, each with
 * its prompt engineering (role, responsibilities, limits, output, directives) and
 * its harness (skills, knowledge, MCP, tools, software).
 */
export function AgentStudio({ onChanged }: { onChanged: () => void }) {
  const api = useApi();
  const [profiles, setProfiles] = useState<AgentProfile[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [installing, setInstalling] = useState(false);

  const reload = useCallback(() => { api.agentProfiles().then(setProfiles).catch(setProblem); }, [api]);
  useEffect(reload, [reload]);

  const roots = (profiles ?? []).filter((p) => p.parentId === null || !(profiles ?? []).some((q) => q.agentId === p.parentId));
  const childrenOf = (id: number) => (profiles ?? []).filter((p) => p.parentId === id);

  async function installAll() {
    setInstalling(true);
    setProblem(null);
    try {
      await api.installAllAgentTemplates();
      reload();
      onChanged();
    } catch (error) {
      setProblem(error);
    } finally {
      setInstalling(false);
    }
  }

  const node = (p: AgentProfile, depth: number): ReactElement => (
    <div key={p.agentId}>
      <button className="list-item" onClick={() => setSelected(p.agentId)}
              style={{ width: "100%", border: 0, cursor: "pointer", color: "inherit", textAlign: "left", paddingLeft: 14 + depth * 22,
                background: selected === p.agentId ? "var(--surface-2)" : "none" }}>
        {depth > 0 && <span className="muted">└</span>}
        <span className="kpi-icon glow-violet" style={{ width: 28, height: 28, borderRadius: 8, display: "grid", placeItems: "center" }}>
          <Icon name="agents" size={15} /></span>
        <div style={{ flex: 1 }}>
          <strong>{p.name}</strong> <span className="muted" style={{ fontSize: 12 }}>{p.role}</span>
          <div style={{ fontSize: 11 }}>
            {p.resources.slice(0, 4).map((r) => <span key={r.key} className="chip">{r.name}</span>)}
            {p.resources.length > 4 && <span className="chip">+{p.resources.length - 4}</span>}
          </div>
        </div>
        <span className="mono muted" style={{ fontSize: 11 }}>{p.model ?? "default"}</span>
        {!p.active && <span className="badge">inattivo</span>}
      </button>
      {childrenOf(p.agentId).map((c) => node(c, depth + 1))}
    </div>
  );

  const current = profiles?.find((p) => p.agentId === selected) ?? null;

  return (
    <div className="stack">
      <div className="row">
        <h2>Ecosistema degli agenti</h2>
        <span className="muted">agenti e sottoagenti, con prompt e harness</span>
        <span className="spacer" />
        <button className="btn" disabled={installing} onClick={installAll}>
          <Icon name="plus" size={14} /> {installing ? "Installo…" : "Installa ecosistema base"}</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className={current ? "split" : ""}>
        <div className="card">{roots.map((r) => node(r, 0))}
          {profiles?.length === 0 && <div className="list-item muted">Nessun agente: installa l'ecosistema base.</div>}</div>
        {current && <AgentConfig key={current.agentId} profile={current} profiles={profiles ?? []}
                                 onClose={() => setSelected(null)} onSaved={() => { reload(); onChanged(); }} />}
      </div>
    </div>
  );
}

function AgentConfig({ profile, profiles, onClose, onSaved }: {
  profile: AgentProfile; profiles: AgentProfile[]; onClose: () => void; onSaved: () => void;
}) {
  const api = useApi();
  const [tab, setTab] = useState<"prompt" | "harness" | "software">("prompt");
  const [form, setForm] = useState({
    parentId: profile.parentId ?? "", domain: profile.domain ?? "", systemPrompt: profile.systemPrompt ?? "",
    responsibilities: profile.responsibilities ?? "", limits: profile.limits ?? "", outputFormat: profile.outputFormat ?? "",
    directives: profile.directives.join("\n"), contextPolicy: profile.contextPolicy ?? "",
  });
  const [resources, setResources] = useState<Resource[]>([]);
  const [software, setSoftware] = useState<Software[]>([]);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api.resources().then(setResources).catch(() => setResources([]));
    api.software().then(setSoftware).catch(() => setSoftware([]));
    api.engineModels().then((l) => setModels(l.models)).catch(() => setModels([]));
  }, [api]);

  const set = (key: keyof typeof form) => (value: string) => { setForm({ ...form, [key]: value }); setSaved(false); };
  const equipped = useMemo(() => new Set(profile.resources.map((r) => r.key)), [profile]);
  const allowed = useMemo(() => new Set(profile.software.map((s) => s.key)), [profile]);

  async function save() {
    setProblem(null);
    try {
      const current = await api.agentProfile(profile.agentId);
      await api.configureAgent(profile.agentId, current.etag, {
        parentId: form.parentId === "" ? null : Number(form.parentId), domain: form.domain, systemPrompt: form.systemPrompt,
        responsibilities: form.responsibilities, limits: form.limits, outputFormat: form.outputFormat,
        directives: form.directives, contextPolicy: form.contextPolicy,
      });
      setSaved(true);
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }

  async function toggle(action: Promise<void>) {
    setProblem(null);
    try { await action; onSaved(); } catch (error) { setProblem(error); }
  }

  const byKind = (kind: string) => resources.filter((r) => r.kind === kind);

  return (
    <div className="card" style={{ position: "sticky", top: 80 }}>
      <div className="card-head">
        <h2>{profile.name}</h2><span className="muted">{profile.role}</span><span className="spacer" />
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi"><Icon name="close" size={14} /></button>
      </div>
      <div className="card-body stack">
        <div className="tabs">
          <button className="tab" aria-selected={tab === "prompt"} onClick={() => setTab("prompt")}>Ruolo e prompt</button>
          <button className="tab" aria-selected={tab === "harness"} onClick={() => setTab("harness")}>Harness</button>
          <button className="tab" aria-selected={tab === "software"} onClick={() => setTab("software")}>Software e modelli</button>
        </div>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {tab === "prompt" && (
          <>
            <Field label="Agente padre (sottoagente di)">
              <select value={form.parentId} onChange={(e) => set("parentId")(e.target.value)}>
                <option value="">— nessuno (agente principale)</option>
                {profiles.filter((p) => p.agentId !== profile.agentId).map((p) => <option key={p.agentId} value={p.agentId}>{p.name}</option>)}
              </select></Field>
            <Field label="Dominio"><input value={form.domain} onChange={(e) => set("domain")(e.target.value)} /></Field>
            <Field label="System role" hint="Chi è l'agente e come si comporta.">
              <textarea value={form.systemPrompt} onChange={(e) => set("systemPrompt")(e.target.value)} /></Field>
            <Field label="Responsabilità"><textarea value={form.responsibilities} onChange={(e) => set("responsibilities")(e.target.value)} /></Field>
            <Field label="Limiti"><textarea value={form.limits} onChange={(e) => set("limits")(e.target.value)} /></Field>
            <Field label="Output atteso"><textarea value={form.outputFormat} onChange={(e) => set("outputFormat")(e.target.value)} /></Field>
            <Field label="Direttive" hint="Una per riga, nell'ordine in cui valgono.">
              <textarea value={form.directives} onChange={(e) => set("directives")(e.target.value)} /></Field>
            <Field label="Politica di contesto" hint="Quali file legge per primi, cosa ignora.">
              <textarea value={form.contextPolicy} onChange={(e) => set("contextPolicy")(e.target.value)} style={{ minHeight: 50 }} /></Field>
            <div className="row"><span className="spacer" />{saved && <span className="badge badge-ok">Salvato</span>}
              <button className="btn btn-primary" onClick={save}>Salva</button></div>
          </>
        )}
        {tab === "harness" && (
          <div className="stack">
            {["SKILL", "KNOWLEDGE", "MCP", "TOOL", "FRAMEWORK"].map((kind) => (
              <div key={kind}>
                <div className="section-title">{KIND_LABEL[kind]}</div>
                {byKind(kind).map((r) => {
                  const on = equipped.has(r.key);
                  return (
                    <label key={r.key} className="row" style={{ fontSize: 13, cursor: "pointer" }} title={r.description ?? ""}>
                      <input type="checkbox" style={{ width: "auto" }} checked={on}
                             onChange={() => toggle(on ? api.detachResource(profile.agentId, r.key) : api.attachResource(profile.agentId, r.key))} />
                      {r.name}
                    </label>
                  );
                })}
              </div>
            ))}
          </div>
        )}
        {tab === "software" && (
          <div className="stack">
            <div className="muted" style={{ fontSize: 12.5 }}>
              Modello principale: <code>{profile.model ?? "default dell'engine"}</code> (si cambia con «Edit» nel registro).
              Modelli pronti: {models.filter((m) => m.available).map((m) => <span key={m.id} className="chip">{m.id}</span>)}
            </div>
            {software.map((s) => {
              const on = allowed.has(s.key);
              return (
                <label key={s.key} className="row" style={{ fontSize: 13, cursor: "pointer" }}>
                  <input type="checkbox" style={{ width: "auto" }} checked={on}
                         onChange={() => toggle(on ? api.disallowSoftware(profile.agentId, s.key) : api.allowSoftware(profile.agentId, s.key))} />
                  {s.name} <span className="muted" style={{ fontSize: 11 }}>{s.role}</span>
                </label>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
