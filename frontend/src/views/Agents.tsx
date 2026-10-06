import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactElement, type ReactNode } from "react";
import { useApi } from "../context";
import { ApiProblem, type Versioned } from "../api/client";
import type {
  AgentConfiguration, AgentTemplate, Binding, CatalogModel, ExecutionTarget, InProgressPolicy, PlannedAgent, Provider,
  Resource, Software, SubAgentsPolicy,
} from "../api/types";
import { Field, Modal, ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";
import { BindingChain, DELIVERY_LABEL } from "../components/BindingChain";
import { ConfirmDeletion, DeleteButton, SelectionBar, useSelection } from "../components/Deletion";
import { useIsAdmin } from "../context";

const ORIGIN_LABEL = { SEED: "Seed iniziale", TEMPLATE: "Da template", USER: "Creato da te" } as const;
const KINDS = [
  { key: "SKILL", label: "Skill" }, { key: "KNOWLEDGE", label: "Knowledge" }, { key: "TOOL", label: "Tool" },
  { key: "MCP", label: "MCP" }, { key: "FRAMEWORK", label: "Framework" },
];
const FIELD_LABEL: Record<string, string> = {
  role: "ruolo", specialization: "specializzazione", description: "descrizione", capabilities: "capability",
  responsibilities: "responsabilità", systemPrompt: "system prompt", directives: "direttive", limits: "limiti",
  outputFormat: "output", contextPolicy: "politica di contesto", domain: "dominio", parentId: "agente padre",
  model: "modello", executionTarget: "execution target", resources: "risorse dell'harness", software: "software",
};

/**
 * Agents and sub-agents (directive §4-§5): who they are, with which model,
 * served by which provider, working where -- and, for seed and template agents,
 * what is still the initial configuration and what a person changed.
 */
export function Agents() {
  const api = useApi();
  const [agents, setAgents] = useState<AgentConfiguration[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);
  const [templates, setTemplates] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [deleting, setDeleting] = useState<AgentConfiguration[] | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const selection = useSelection<number>();
  const isAdmin = useIsAdmin();

  const reload = useCallback(() => api.ecosystemBindings().then(setAgents).catch(setProblem), [api]);
  useEffect(() => { reload(); }, [reload]);

  // PHASE 30 (ADR-035): after a delete the list is read again, and what was selected or open and is gone, is let go.
  function deleted(names: string[]) {
    setDeleting(null);
    selection.clear();
    setSelecting(false);
    setDone(names.length === 1 ? `Eliminato «${names[0]}».` : `Eliminati ${names.length} agenti.`);
    reload().then(() => setSelected(null));
  }

  const byId = new Map((agents ?? []).map((a) => [a.id, a]));
  const roots = (agents ?? []).filter((a) => !a.parent || !byId.has(a.parent.id));
  const childrenOf = (id: number) => (agents ?? []).filter((a) => a.parent?.id === id);

  const node = (a: AgentConfiguration, depth: number): ReactElement => (
    <div key={a.id} style={{ display: "grid", gap: 6, marginLeft: depth * 18 }}>
      <button className="agent-card" aria-pressed={selecting ? selection.has(a.id) : selected === a.id}
              onClick={() => selecting ? selection.toggle(a.id) : setSelected(a.id)}>
        <div className="row">
          {selecting && <span className={selection.has(a.id) ? "select-box is-on" : "select-box"} aria-hidden="true">
            {selection.has(a.id) && <Icon name="check" size={11} />}</span>}
          {depth > 0 && <span className="muted" title="Sottoagente">└</span>}
          <Icon name="agents" size={15} />
          <strong>{a.name}</strong>
          <span className="muted" style={{ fontSize: 12 }}>{a.role}</span>
          <span className="spacer" />
          <span className="chip origin-badge">{ORIGIN_LABEL[a.origin]}</span>
          {a.modified.length > 0 && <span className="badge badge-warn" title={a.modified.map((f) => FIELD_LABEL[f] ?? f).join(", ")}>
            {a.modified.length} modific{a.modified.length === 1 ? "a" : "he"}</span>}
          {a.status !== "ACTIVE" && <span className="badge">inattivo</span>}
        </div>
        <BindingChain binding={a.binding} compact />
        {!a.binding.valid && <span className="badge badge-danger">legame non valido</span>}
      </button>
      {childrenOf(a.id).map((c) => node(c, depth + 1))}
    </div>
  );

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Agenti</h1>
          <p>Ogni agente: chi è, con quale modello, servito da quale provider, e dove lavora — il motore interno o un'app/CLI come Claude Code e Codex.</p></div>
        <div className="row">
          {isAdmin && (agents?.length ?? 0) > 0 && (
            <button className="btn" aria-pressed={selecting} onClick={() => { setSelecting(!selecting); selection.clear(); setSelected(null); }}>
              <Icon name="check" size={14} /> {selecting ? "Fine selezione" : "Seleziona"}</button>
          )}
          <button className="btn" onClick={() => setTemplates(true)}><Icon name="plus" size={14} /> Da template</button>
          <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={14} /> Nuovo agente</button>
        </div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {done && <div className="notice row" role="status"><Icon name="check" size={13} /> {done}<span className="spacer" />
        <button className="btn btn-small" onClick={() => setDone(null)}>Chiudi</button></div>}
      {selecting && (
        <SelectionBar count={selection.size} noun={["agente", "agenti"]} onClear={selection.clear}
                      onSelectAll={() => selection.setAll((agents ?? []).map((a) => a.id))}
                      onDelete={() => setDeleting((agents ?? []).filter((a) => selection.has(a.id)))} />
      )}
      <div className={selected ? "split" : ""}>
        <div className="stack">
          {roots.map((r) => node(r, 0))}
          {agents?.length === 0 && <div className="card card-body muted">Nessun agente: creane uno o installa un template.</div>}
        </div>
        {selected && byId.has(selected) && !selecting && (
          <AgentEditor key={selected} id={selected} agents={agents ?? []} onClose={() => setSelected(null)} onSaved={reload}
                       onDelete={isAdmin ? (agent) => setDeleting([agent]) : undefined} />
        )}
      </div>
      {deleting && deleting.length > 0 && (
        <DeleteAgents agents={deleting} onClose={() => setDeleting(null)} onDeleted={deleted}
                      onDeactivated={() => { setDeleting(null); reload(); }} />
      )}
      {creating && <NewAgentModal onClose={() => setCreating(false)}
                                  onCreated={(id) => { setCreating(false); reload().then(() => setSelected(id)); }} />}
      {templates && <TemplatesModal onClose={() => setTemplates(false)} onInstalled={() => reload()} />}
    </div>
  );
}

// --- the editor ----------------------------------------------------------------------

type Tab = "identity" | "binding" | "prompt" | "harness" | "software";

interface Form {
  name: string; role: string; specialization: string; description: string; capabilities: string; parentId: string;
  domain: string; systemPrompt: string; responsibilities: string; limits: string; outputFormat: string;
  directives: string; contextPolicy: string; model: string; executionTarget: string;
}

function formOf(c: AgentConfiguration): Form {
  return {
    name: c.name, role: c.role, specialization: c.specialization, description: c.description ?? "",
    capabilities: c.capabilities.join("\n"), parentId: c.parent ? String(c.parent.id) : "", domain: c.domain ?? "",
    systemPrompt: c.systemPrompt ?? "", responsibilities: c.responsibilities ?? "", limits: c.limits ?? "",
    outputFormat: c.outputFormat ?? "", directives: c.directives.join("\n"), contextPolicy: c.contextPolicy ?? "",
    model: c.model ?? "", executionTarget: c.executionTarget,
  };
}

function AgentEditor({ id, agents, onClose, onSaved, onDelete }: {
  id: number; agents: AgentConfiguration[]; onClose: () => void; onSaved: () => void;
  onDelete?: (agent: AgentConfiguration) => void;
}) {
  const api = useApi();
  const [current, setCurrent] = useState<Versioned<AgentConfiguration> | null>(null);
  const [form, setForm] = useState<Form | null>(null);
  const [tab, setTab] = useState<Tab>("identity");
  const [targets, setTargets] = useState<ExecutionTarget[]>([]);
  const [models, setModels] = useState<CatalogModel[]>([]);
  const [providers, setProviders] = useState<Provider[]>([]);
  const [resources, setResources] = useState<Resource[]>([]);
  const [software, setSoftware] = useState<Software[]>([]);
  const [preview, setPreview] = useState<Binding | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);

  const load = useCallback(async () => {
    const read = await api.agentConfiguration(id);
    setCurrent(read);
    setForm(formOf(read.body));
  }, [api, id]);

  useEffect(() => {
    load().catch(setProblem);
    api.executionTargets().then(setTargets).catch(() => setTargets([]));
    api.modelCatalog().then((c) => setModels(c.models)).catch(() => setModels([]));
    api.providers().then(setProviders).catch(() => setProviders([]));
    api.resources().then(setResources).catch(() => setResources([]));
    api.software().then(setSoftware).catch(() => setSoftware([]));
  }, [api, load]);

  // Live preview of the chain while model and target change, before saving.
  useEffect(() => {
    if (!form) return;
    const timer = window.setTimeout(() => {
      api.checkBinding(form.executionTarget, form.model || null).then(setPreview).catch(() => setPreview(null));
    }, 150);
    return () => window.clearTimeout(timer);
  }, [api, form?.executionTarget, form?.model]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!current || !form) {
    return <div className="card card-body muted">Carico la configurazione…</div>;
  }
  const c = current.body;
  const set = (key: keyof Form) => (value: string) => { setForm({ ...form, [key]: value }); setSaved(false); };
  const state = (field: string) => <FieldState field={field} config={c} />;
  const target = targets.find((t) => t.key === form.executionTarget);
  const providerName = (key: string) => providers.find((p) => p.key === key)?.name ?? key;
  const compatible = models.filter((m) => !target || target.providers.includes(m.providerKey));
  const dirty = JSON.stringify(form) !== JSON.stringify(formOf(c));

  async function save() {
    setProblem(null);
    try {
      const next = await api.saveAgentConfiguration(id, current!.etag, {
        name: form!.name, role: form!.role, specialization: form!.specialization, description: form!.description || null,
        capabilities: form!.capabilities, parentId: form!.parentId === "" ? null : Number(form!.parentId),
        domain: form!.domain || null, systemPrompt: form!.systemPrompt || null,
        responsibilities: form!.responsibilities || null, limits: form!.limits || null,
        outputFormat: form!.outputFormat || null, directives: form!.directives, contextPolicy: form!.contextPolicy || null,
        model: form!.model || null, executionTarget: form!.executionTarget,
      });
      setCurrent(next);
      setForm(formOf(next.body));
      setSaved(true);
      onSaved();
    } catch (error) {
      setProblem(error);
      if (error instanceof ApiProblem && error.isStale) await load().catch(() => undefined);
    }
  }

  async function reset() {
    setProblem(null);
    try {
      const next = await api.resetAgentConfiguration(id, current!.etag);
      setCurrent(next);
      setForm(formOf(next.body));
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }

  async function toggleActive() {
    setProblem(null);
    try {
      const agent = await api.agent(id);
      await api.setAgentActive(id, agent.etag, !agent.body.active);
      await load();
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }

  async function membership(action: Promise<void>) {
    setProblem(null);
    try { await action; await load(); onSaved(); } catch (error) { setProblem(error); }
  }

  const equipped = new Set(Object.values(c.resources).flat().map((r) => r.key));
  const allowed = new Set(c.software.map((s) => s.key));

  return (
    <div className="card" style={{ position: "sticky", top: 80 }}>
      <div className="card-head">
        <h2>{c.name}</h2>
        <span className="chip origin-badge">{ORIGIN_LABEL[c.origin]}</span>
        {c.status !== "ACTIVE" && <span className="badge">inattivo</span>}
        <span className="spacer" />
        {onDelete && <DeleteButton label={`Elimina ${c.name}`} onClick={() => onDelete(c)} />}
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi"><Icon name="close" size={14} /></button>
      </div>
      <div className="card-body stack">
        <BindingChain agentName={c.name} agentRole={c.role} binding={c.binding} />
        {c.origin !== "USER" && (
          <div className={c.modified.length ? "notice notice-warn" : "notice"}>
            {c.modified.length === 0
              ? <>Configurazione iniziale ({ORIGIN_LABEL[c.origin].toLowerCase()}), non modificata.</>
              : <>Modificato rispetto alla configurazione iniziale: {c.modified.map((f) => FIELD_LABEL[f] ?? f).join(", ")}
                {c.customizedAt && <> · ultima modifica {when(c.customizedAt)}{c.customizedBy ? ` da ${c.customizedBy}` : ""}</>}.
                <button className="btn btn-small" style={{ marginLeft: 8 }} onClick={reset}>Ripristina iniziale</button></>}
          </div>
        )}
        <div className="tabs">
          <button className="tab" aria-selected={tab === "identity"} onClick={() => setTab("identity")}>Identità</button>
          <button className="tab" aria-selected={tab === "binding"} onClick={() => setTab("binding")}>Modello ed esecuzione</button>
          <button className="tab" aria-selected={tab === "prompt"} onClick={() => setTab("prompt")}>Prompt e direttive</button>
          <button className="tab" aria-selected={tab === "harness"} onClick={() => setTab("harness")}>Skill e strumenti</button>
          <button className="tab" aria-selected={tab === "software"} onClick={() => setTab("software")}>Software</button>
        </div>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />

        {tab === "identity" && (
          <>
            <Field label="Nome"><input value={form.name} onChange={(e) => set("name")(e.target.value)} /></Field>
            <Labelled label="Ruolo" state={state("role")}><input aria-label="Ruolo" value={form.role} onChange={(e) => set("role")(e.target.value)} /></Labelled>
            <Labelled label="Specializzazione" state={state("specialization")} hint="Anche ciò su cui il router confronta il lavoro.">
              <input aria-label="Specializzazione" value={form.specialization} onChange={(e) => set("specialization")(e.target.value)} /></Labelled>
            <Labelled label="Descrizione" state={state("description")}>
              <textarea aria-label="Descrizione" value={form.description} onChange={(e) => set("description")(e.target.value)} /></Labelled>
            <Labelled label="Capability" state={state("capabilities")} hint="Una per riga: cosa sa fare.">
              <textarea aria-label="Capability" value={form.capabilities} onChange={(e) => set("capabilities")(e.target.value)} style={{ minHeight: 70 }} /></Labelled>
            <Labelled label="Sottoagente di" state={state("parentId")}>
              <select aria-label="Sottoagente di" value={form.parentId} onChange={(e) => set("parentId")(e.target.value)}>
                <option value="">— nessuno: agente principale</option>
                {agents.filter((a) => a.id !== id).map((a) => <option key={a.id} value={a.id}>{a.name} ({a.role})</option>)}
              </select></Labelled>
            {c.children.length > 0 && <div className="muted" style={{ fontSize: 12.5 }}>Sottoagenti: {c.children.map((k) => <span key={k.id} className="chip">{k.name}</span>)}</div>}
            <div className="row">
              <span>Stato: {c.status === "ACTIVE" ? <span className="badge badge-ok">attivo</span> : <span className="badge">inattivo</span>}</span>
              <button className="btn btn-small" onClick={toggleActive}>{c.status === "ACTIVE" ? "Disattiva" : "Riattiva"}</button>
            </div>
          </>
        )}

        {tab === "binding" && (
          <>
            <Labelled label="Execution target — dove lavora" state={state("executionTarget")}>
              <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))", gap: 8 }}>
                {targets.map((t) => {
                  const sw = software.find((s) => s.key === t.software);
                  const missing = sw && sw.availability === "NOT_INSTALLED";
                  return (
                    <button key={t.key} type="button" className="agent-card" aria-pressed={form.executionTarget === t.key}
                            onClick={() => setForm({ ...form, executionTarget: t.key,
                              model: models.some((m) => m.key === form.model && t.providers.includes(m.providerKey)) ? form.model : "" })}>
                      <strong>{t.name}</strong>
                      <small className="muted">{DELIVERY_LABEL[t.delivery]}</small>
                      {missing && <span className="badge badge-warn">non installato</span>}
                    </button>
                  );
                })}
              </div>
            </Labelled>
            {target && <div className="muted" style={{ fontSize: 12.5 }}><Icon name="sparkle" size={12} /> {target.howItWorks}</div>}
            <Labelled label="Modello" state={state("model")}
                      hint={target?.key === "engine" ? "Solo modelli che l'AI Engine sa eseguire." : "Modelli dei provider che questo strumento usa."}>
              <select aria-label="Modello" value={form.model} onChange={(e) => set("model")(e.target.value)}>
                <option value="">{target?.key === "engine" ? "Default dell'engine" : "Scelto nell'app / nella CLI"}</option>
                {Array.from(new Set(compatible.map((m) => m.providerKey))).map((p) => (
                  <optgroup key={p} label={providerName(p)}>
                    {compatible.filter((m) => m.providerKey === p).map((m) => (
                      <option key={m.key} value={m.key}>{m.displayName}{m.lifecycle === "DEPRECATED" ? " (superato)" : ""}</option>
                    ))}
                  </optgroup>
                ))}
                {form.model && !compatible.some((m) => m.key === form.model) && <option value={form.model}>{form.model} (non compatibile)</option>}
              </select>
            </Labelled>
            <div className="section-title">Anteprima del legame</div>
            {preview && <BindingChain agentName={form.name} agentRole={form.role} binding={preview} />}
          </>
        )}

        {tab === "prompt" && (
          <>
            <Labelled label="System prompt / role prompt" state={state("systemPrompt")} hint="Chi è l'agente e come si comporta.">
              <textarea aria-label="System prompt" value={form.systemPrompt} onChange={(e) => set("systemPrompt")(e.target.value)} style={{ minHeight: 110 }} /></Labelled>
            <Labelled label="Responsabilità" state={state("responsibilities")}>
              <textarea aria-label="Responsabilità" value={form.responsibilities} onChange={(e) => set("responsibilities")(e.target.value)} /></Labelled>
            <Labelled label="Direttive" state={state("directives")} hint="Una per riga, nell'ordine in cui valgono.">
              <textarea aria-label="Direttive" value={form.directives} onChange={(e) => set("directives")(e.target.value)} /></Labelled>
            <Labelled label="Limiti" state={state("limits")}>
              <textarea aria-label="Limiti" value={form.limits} onChange={(e) => set("limits")(e.target.value)} /></Labelled>
            <Labelled label="Output atteso" state={state("outputFormat")}>
              <textarea aria-label="Output atteso" value={form.outputFormat} onChange={(e) => set("outputFormat")(e.target.value)} /></Labelled>
            <Labelled label="Politica di contesto" state={state("contextPolicy")} hint="Quali file legge per primi, cosa ignora.">
              <textarea aria-label="Politica di contesto" value={form.contextPolicy} onChange={(e) => set("contextPolicy")(e.target.value)} style={{ minHeight: 50 }} /></Labelled>
            <Labelled label="Dominio" state={state("domain")}><input aria-label="Dominio" value={form.domain} onChange={(e) => set("domain")(e.target.value)} /></Labelled>
          </>
        )}

        {tab === "harness" && (
          <div className="stack">
            {c.modified.includes("resources") && <span className="badge badge-warn">risorse modificate rispetto all'iniziale</span>}
            {KINDS.map((kind) => (
              <div key={kind.key}>
                <div className="section-title">{kind.label} <span className="muted">({(c.resources[kind.key] ?? []).length})</span></div>
                {resources.filter((r) => r.kind === kind.key).map((r) => {
                  const on = equipped.has(r.key);
                  return (
                    <label key={r.key} className="row" style={{ fontSize: 13, cursor: "pointer" }} title={r.description ?? ""}>
                      <input type="checkbox" style={{ width: "auto" }} checked={on}
                             onChange={() => membership(on ? api.detachResource(id, r.key) : api.attachResource(id, r.key))} />
                      {r.name}{r.configuration && <span className="chip">file</span>}
                    </label>
                  );
                })}
              </div>
            ))}
            <div className="muted" style={{ fontSize: 12.5 }}>Le skill si vedono e si modificano, come file, nel Knowledge Hub.</div>
          </div>
        )}

        {tab === "software" && (
          <div className="stack">
            {c.modified.includes("software") && <span className="badge badge-warn">software modificato rispetto all'iniziale</span>}
            {software.filter((s) => s.launchKind !== "WEB").map((s) => {
              const on = allowed.has(s.key);
              return (
                <label key={s.key} className="row" style={{ fontSize: 13, cursor: "pointer" }}>
                  <input type="checkbox" style={{ width: "auto" }} checked={on}
                         onChange={() => membership(on ? api.disallowSoftware(id, s.key) : api.allowSoftware(id, s.key))} />
                  {s.name} <span className="muted" style={{ fontSize: 11 }}>{s.role}</span>
                  {s.availability === "NOT_INSTALLED" && <span className="badge">non installato</span>}
                </label>
              );
            })}
          </div>
        )}

        {tab !== "harness" && tab !== "software" && (
          <div className="row">
            <span className="spacer" />
            {saved && !dirty && <span className="badge badge-ok">Salvato</span>}
            {dirty && <button className="btn" onClick={() => setForm(formOf(c))}>Annulla modifiche</button>}
            <button className="btn btn-primary" disabled={!dirty || (preview !== null && !preview.valid)} onClick={save}>Salva configurazione</button>
          </div>
        )}
      </div>
    </div>
  );
}

/** A field with its own "default / modificato" state for seed and template agents. */
function Labelled({ label, state, hint, children }: { label: string; state: ReactNode; hint?: string; children: ReactNode }) {
  return (
    <div className="field">
      <div className="row" style={{ gap: 4 }}><span className="field-label">{label}</span>{state}</div>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

function FieldState({ field, config }: { field: string; config: AgentConfiguration }) {
  if (!config.defaults) return null;
  const initial = config.defaults[field];
  const text = initial === null || initial === undefined || initial === "" ? "(vuoto)" : String(initial);
  return config.modified.includes(field)
    ? <span className="badge badge-warn field-state" title={`Valore iniziale: ${text}`}>modificato</span>
    : <span className="badge badge-ok field-state" title="Valore della configurazione iniziale">iniziale</span>;
}

// --- creation --------------------------------------------------------------------------

function NewAgentModal({ onClose, onCreated }: { onClose: () => void; onCreated: (id: number) => void }) {
  const api = useApi();
  const [name, setName] = useState("");
  const [role, setRole] = useState("");
  const [specialization, setSpecialization] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      const created = await api.createAgent({ name, role, specialization });
      onCreated(created.body.id);
    } catch (error) {
      setProblem(error);
    }
  }
  const errors = problem instanceof ApiProblem ? problem.errors : {};
  return (
    <Modal title="Nuovo agente" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Field label="Nome" error={errors.name}><input value={name} onChange={(e) => setName(e.target.value)} required autoFocus /></Field>
        <Field label="Ruolo" error={errors.role}><input value={role} onChange={(e) => setRole(e.target.value)} required placeholder="Backend Engineer" /></Field>
        <Field label="Specializzazione" error={errors.specialization}><input value={specialization} onChange={(e) => setSpecialization(e.target.value)} required /></Field>
        <div className="muted" style={{ fontSize: 12.5 }}>Modello, execution target, prompt e skill si configurano subito dopo, nell'editor dell'agente.</div>
        <ProblemNote problem={problem} />
        <div className="row"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit">Crea e configura</button></div>
      </form>
    </Modal>
  );
}

// --- deleting (PHASE 30, ADR-035 §2) ---------------------------------------------------------

/**
 * Deleting one agent or a selection. What happens to sub-agents and to work in
 * progress is chosen here, and the preview -- computed by the server with the
 * rules it deletes with -- follows the choice. Deactivating stays offered, as
 * the reversible gesture, when a single active agent is being deleted.
 */
function DeleteAgents({ agents, onClose, onDeleted, onDeactivated }: {
  agents: AgentConfiguration[]; onClose: () => void; onDeleted: (names: string[]) => void; onDeactivated: () => void;
}) {
  const api = useApi();
  const [subagents, setSubagents] = useState<SubAgentsPolicy>("DETACH");
  const [inProgress, setInProgress] = useState<InProgressPolicy>("BLOCK");
  const ids = agents.map((a) => a.id);
  const loadPreview = useCallback(() => api.agentDeletionPreview(ids, subagents, inProgress),
    [api, ids.join(","), subagents, inProgress]); // eslint-disable-line react-hooks/exhaustive-deps
  const hasChildren = agents.some((a) => a.children.some((child) => !ids.includes(child.id)));
  const single = agents.length === 1 ? agents[0] : null;
  const name = single ? single.name : `${agents.length} agenti`;

  return (
    <ConfirmDeletion title={single ? "Elimina agente" : "Elimina agenti"} subject={name} loadPreview={loadPreview}
                     confirmLabel="Elimina definitivamente" onClose={onClose}
                     intro={single
                       ? <>Stai per eliminare definitivamente l'agente <strong>«{single.name}»</strong>. Non si annulla.</>
                       : <>Stai per eliminare definitivamente <strong>{agents.length} agenti</strong>: {agents.map((a) => a.name).join(", ")}. Non si annulla.</>}
                     alternative={single && single.status === "ACTIVE" ? {
                       label: "Disattiva", hint: "Vuoi solo toglierlo dal lavoro? «Disattiva» è reversibile: lo riattivi quando vuoi.",
                       onClick: async () => {
                         const agent = await api.agent(single.id);
                         await api.setAgentActive(single.id, agent.etag, false);
                         onDeactivated();
                       },
                     } : undefined}
                     onConfirm={async () => {
                       await api.deleteAgents(agents.map((a) => ({ id: a.id, version: a.version })), subagents, inProgress);
                       onDeleted(agents.map((a) => a.name));
                     }}>
      {hasChildren && (
        <fieldset className="bp-fieldset">
          <legend>Sottoagenti</legend>
          <label className="row"><input type="radio" className="select-check" name="subagents" checked={subagents === "DETACH"}
                                        onChange={() => setSubagents("DETACH")} /> Diventano agenti principali (consigliato)</label>
          <label className="row"><input type="radio" className="select-check" name="subagents" checked={subagents === "DELETE"}
                                        onChange={() => setSubagents("DELETE")} /> Elimina anche i sottoagenti</label>
        </fieldset>
      )}
      <label className="row" style={{ fontSize: 13 }}>
        <input type="checkbox" className="select-check" checked={inProgress === "STOP"}
               onChange={(e) => setInProgress(e.target.checked ? "STOP" : "BLOCK")} />
        Se ha task in corso, riportale a «Da fare» senza agente (altrimenti l'eliminazione si ferma)
      </label>
    </ConfirmDeletion>
  );
}

/**
 * Installing templates (directive §15, §22), with the plan first: for every
 * agent of every template, whether it would be created or is already there.
 * Deleted template agents never come back on their own -- only from here, and
 * never twice (ADR-035 §4).
 */
function TemplatesModal({ onClose, onInstalled }: { onClose: () => void; onInstalled: () => void }) {
  const api = useApi();
  const [templates, setTemplates] = useState<AgentTemplate[]>([]);
  const [plan, setPlan] = useState<PlannedAgent[]>([]);
  const [done, setDone] = useState<string | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const loadPlan = useCallback(() => api.agentTemplatePlan().then(setPlan).catch(setProblem), [api]);
  useEffect(() => { api.agentTemplates().then(setTemplates).catch(setProblem); loadPlan(); }, [api, loadPlan]);
  const sorted = useMemo(() => [...templates].sort((a, b) => a.name.localeCompare(b.name)), [templates]);
  const missing = plan.filter((p) => p.outcome === "CREATED");

  async function install(action: () => Promise<{ name: string; outcome: string }[]>) {
    setProblem(null);
    setBusy(true);
    try {
      const result = await action();
      const created = result.filter((r) => r.outcome === "CREATED").map((r) => r.name);
      setDone(created.length ? `Creati: ${created.join(", ")}. Gli agenti già presenti non sono stati toccati.`
        : "Niente da creare: erano già tutti presenti.");
      onInstalled();
      await loadPlan();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="Installa agenti da template" onClose={onClose} wide>
      <div className="stack">
        <div className="notice row">
          <span style={{ flex: 1 }}>
            {missing.length === 0 ? "Il set di template è installato per intero."
              : <>Mancano <strong>{missing.length}</strong> agenti del set di template: {missing.map((p) => p.name).join(", ")}.</>}
            {" "}Un agente con lo stesso nome non viene mai duplicato né sovrascritto.
          </span>
          <button className="btn btn-primary" disabled={busy || missing.length === 0}
                  onClick={() => install(() => api.installAllAgentTemplates())}>Installa tutti i mancanti</button>
        </div>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {done && <div className="notice"><Icon name="check" size={13} /> {done}</div>}
        {sorted.map((t) => {
          const agentsOf = plan.filter((p) => p.templateKey === t.key);
          const toCreate = agentsOf.filter((p) => p.outcome === "CREATED").length;
          return (
            <div key={t.key} className="row" style={{ borderBottom: "1px solid var(--border)", paddingBottom: 8 }}>
              <div style={{ flex: 1 }}>
                <strong>{t.name}</strong> <span className="muted">{t.role}</span>
                <div className="muted" style={{ fontSize: 12.5 }}>{t.specialization}</div>
                <div style={{ fontSize: 12 }}><span className="chip mono">{t.model}</span>
                  {agentsOf.map((p) => (
                    <span key={p.name} className={p.outcome === "CREATED" ? "chip" : "chip muted"}
                          title={p.outcome === "CREATED" ? "verrà creato" : "già presente: non viene toccato"}>
                      {p.parentName ? "+ " : ""}{p.name} · {p.outcome === "CREATED" ? "da creare" : "presente"}</span>
                  ))}</div>
              </div>
              <button className="btn btn-small btn-primary" disabled={busy || toCreate === 0}
                      onClick={() => install(() => api.installAgentTemplate(t.key))}>
                {toCreate === 0 ? "Installato" : `Installa (${toCreate})`}</button>
            </div>
          );
        })}
      </div>
    </Modal>
  );
}
