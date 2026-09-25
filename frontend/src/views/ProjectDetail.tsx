import { useCallback, useEffect, useState } from "react";
import { useApi } from "../context";
import { CodeGraphView } from "./CodeGraphView";
import type {
  Agent, AutonomyLevel, ModelInfo, Phase, Plan, PlanRun, Project, ProjectTypeInfo, Software, WorkspaceDocuments,
} from "../api/types";
import { PriorityBadge, ProblemNote, StatusBadge, when } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon } from "../components/icons";
import { TaskDrawer } from "./TaskDrawer";
import { AUTONOMY } from "./projectLabels";
import { useLauncher } from "./SoftwareHub";

const TABS = ["Panoramica", "Master Prompt", "Piano", "Documenti", "Codice", "Reference", "Strumenti"] as const;
type Tab = typeof TABS[number];

const APPROVAL: Record<string, { label: string; tone: string }> = {
  PENDING: { label: "Da approvare", tone: "badge badge-warn" },
  APPROVED: { label: "Approvata", tone: "badge badge-ok" },
  CHANGES_REQUESTED: { label: "Modifiche richieste", tone: "badge badge-danger" },
};
const PHASE_STATUS: Record<string, string> = { PLANNED: "Pianificata", IN_PROGRESS: "In corso", DONE: "Completata" };

/**
 * One project, the whole way: Brainstorming → Master Prompt → Plan → Phases →
 * Tasks → Execution → Review (directive §7). Every step reads and writes the
 * project's files through the control plane (ADR-020).
 */
export function ProjectDetail({ projectId, types, navigate }: {
  projectId: number; types: ProjectTypeInfo[]; navigate: (page: string, detail?: string) => void;
}) {
  const api = useApi();
  const [plan, setPlan] = useState<Plan | null>(null);
  const [docs, setDocs] = useState<WorkspaceDocuments | null>(null);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [tab, setTab] = useState<Tab>("Panoramica");
  const [problem, setProblem] = useState<unknown>(null);
  const [openTask, setOpenTask] = useState<number | null>(null);

  const reload = useCallback(() => {
    api.plan(projectId).then(setPlan).catch(setProblem);
    api.documents(projectId).then(setDocs).catch(() => setDocs(null));
  }, [api, projectId]);

  useEffect(() => {
    reload();
    api.agents().then(setAgents).catch(() => undefined);
    api.projects().then(setProjects).catch(() => undefined);
  }, [api, reload]);

  const project = plan?.project;
  if (!project) {
    return <div className="stack"><ProblemNote problem={problem} /><div className="muted">Carico il progetto…</div></div>;
  }
  const type = types.find((t) => t.type === project.projectType);
  const allTasks = plan.phases.flatMap((p) => p.tasks);
  const done = allTasks.filter((t) => t.status === "DONE").length;

  return (
    <div className="stack">
      <div className="row">
        <button className="btn btn-small" onClick={() => navigate("projects")}>← Progetti</button>
      </div>
      <div className="card hero">
        <span className="brand-mark" style={{ width: 48, height: 48 }}><Icon name="projects" size={24} /></span>
        <div style={{ minWidth: 0 }}>
          <h1>{project.name}</h1>
          <div className="row" style={{ marginTop: 4 }}>
            <span className="chip">{type?.label ?? "Tipologia non indicata"}</span>
            <span className="chip">{AUTONOMY[project.autonomyLevel as AutonomyLevel]?.label ?? project.autonomyLevel}</span>
            {project.workspacePath && <span className="mono muted" style={{ fontSize: 12 }}>{project.workspacePath}</span>}
          </div>
        </div>
        <span className="spacer" />
        <div style={{ textAlign: "right" }}>
          <strong style={{ fontSize: 22 }}>{allTasks.length ? Math.round((done / allTasks.length) * 100) : 0}%</strong>
          <div className="muted" style={{ fontSize: 12 }}>{done}/{allTasks.length} task completate</div>
        </div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />

      <div className="tabs" role="tablist">
        {TABS.map((t) => <button key={t} className="tab" role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{t}</button>)}
      </div>

      {tab === "Panoramica" && <Overview project={project} plan={plan} docs={docs} onGo={setTab} onReload={reload} setProblem={setProblem} />}
      {tab === "Master Prompt" && <MasterPrompt project={project} docs={docs} onSaved={reload} />}
      {tab === "Piano" && <PlanTab project={project} plan={plan} docs={docs} agents={agents} onReload={reload}
                                   onOpenTask={setOpenTask} />}
      {tab === "Documenti" && <Documents project={project} docs={docs} />}
      {tab === "Codice" && <CodeGraphView projectId={project.id} />}
      {tab === "Reference" && <References project={project} docs={docs} onUploaded={reload} />}
      {tab === "Strumenti" && <Tools project={project} type={type} />}

      {openTask !== null && (
        <TaskDrawer taskId={openTask} agents={agents} projects={projects}
                    onClose={() => setOpenTask(null)} onChanged={reload} />
      )}
    </div>
  );
}

// --- overview: where the project stands in the workflow ------------------------

function Overview({ project, plan, docs, onGo, onReload, setProblem }: {
  project: Project; plan: Plan; docs: WorkspaceDocuments | null; onGo: (tab: Tab) => void; onReload: () => void;
  setProblem: (p: unknown) => void;
}) {
  const api = useApi();
  const tasks = plan.phases.flatMap((p) => p.tasks);
  const hasMaster = !!docs?.masterPrompt && !docs.masterPromptIsTemplate;
  const steps = [
    { label: "Brainstorming", done: hasMaster, hint: "Con ChatGPT Classic" },
    { label: "Master Prompt", done: hasMaster, hint: "MASTER_PROMPT.md" },
    { label: "Implementation Plan", done: project.planStatus !== "NONE", hint: "Master Orchestrator" },
    { label: "Approvazione", done: project.planStatus === "APPROVED", hint: "Human-in-the-Loop" },
    { label: "Esecuzione", done: tasks.some((t) => t.status !== "OPEN"), hint: "Agenti" },
    { label: "Review", done: tasks.length > 0 && tasks.every((t) => t.status === "DONE"), hint: "Operatore" },
  ];
  const current = steps.findIndex((s) => !s.done);

  const [autonomy, setAutonomy] = useState(project.autonomyLevel as AutonomyLevel);
  async function saveAutonomy(level: AutonomyLevel) {
    setAutonomy(level);
    try {
      const current = await api.project(project.id);
      await api.configureProject(project.id, current.etag, { projectType: project.projectType, stack: project.stack,
        workspacePath: project.workspacePath, autonomyLevel: level });
      onReload();
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <div className="grid grid-2">
      <div className="card card-body stack">
        <h2>Percorso del progetto</h2>
        <div className="stack" style={{ gap: 8 }}>
          {steps.map((s, i) => (
            <div key={s.label} className={`step ${s.done ? "done" : i === current ? "current" : ""}`}>
              <b>{s.done ? "✓" : i + 1}</b><span>{s.label}</span><span className="muted">· {s.hint}</span>
            </div>
          ))}
        </div>
        {!docs?.exists && (
          <div className="notice notice-warn">La cartella del progetto non esiste ancora.
            <button className="btn btn-small" style={{ marginLeft: 8 }}
                    onClick={() => api.scaffold(project.id).then(onReload).catch(setProblem)}>Prepara workspace</button></div>
        )}
        {current >= 0 && (
          <button className="btn btn-primary" style={{ justifySelf: "start", alignSelf: "start" }}
                  onClick={() => onGo(current <= 1 ? "Master Prompt" : "Piano")}>
            Prossimo passo: {steps[current].label}</button>
        )}
      </div>
      <div className="card card-body stack">
        <h2>Profilo</h2>
        <dl className="kv">
          <dt>Stack</dt><dd style={{ whiteSpace: "pre-wrap" }}>{project.stack || "—"}</dd>
          <dt>Cartella</dt><dd className="mono">{project.workspacePath || "—"}</dd>
          <dt>Piano</dt><dd>{project.planStatus}</dd>
          <dt>Fasi</dt><dd>{plan.phases.length} ({plan.phases.filter((p) => p.approval === "APPROVED").length} approvate)</dd>
        </dl>
        <div className="field">
          <label>Livello di Human-in-the-Loop</label>
          <select value={autonomy} onChange={(e) => saveAutonomy(e.target.value as AutonomyLevel)}>
            {(Object.keys(AUTONOMY) as AutonomyLevel[]).map((l) => <option key={l} value={l}>{AUTONOMY[l].label}</option>)}
          </select>
          <span className="hint">{AUTONOMY[autonomy].hint} Vale per l'AI Engine e per gli agenti esterni.</span>
        </div>
      </div>
    </div>
  );
}

// --- master prompt --------------------------------------------------------------

function MasterPrompt({ project, docs, onSaved }: { project: Project; docs: WorkspaceDocuments | null; onSaved: () => void }) {
  const api = useApi();
  const [text, setText] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const { launch, outcome } = useLauncher();
  const [chatgpt, setChatgpt] = useState<Software | null>(null);

  useEffect(() => {
    api.readFile(project.id, "MASTER_PROMPT.md").then(setText).catch(() => setText(""));
    api.softwareEntry("chatgpt-classic").then((s) => setChatgpt(s.body)).catch(() => undefined);
  }, [api, project.id]);

  async function save() {
    setProblem(null);
    try {
      await api.writeFile(project.id, "MASTER_PROMPT.md", text ?? "");
      setSaved(true);
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <div className="card card-body stack">
      <div className="row">
        <h2>MASTER_PROMPT.md</h2>
        {docs?.masterPromptIsTemplate && <span className="badge badge-warn">ancora il modello vuoto</span>}
        <span className="spacer" />
        {chatgpt && <button className="btn" onClick={() => launch(chatgpt)}><AppIcon software={chatgpt} size={18} /> Brainstorming con ChatGPT Classic</button>}
      </div>
      <p className="muted" style={{ margin: 0 }}>
        Fai brainstorming con ChatGPT Classic, chiedigli di produrre il MASTER PROMPT, incollalo qui e salva. Il Master
        Orchestrator lo legge per generare l'Implementation Plan.
      </p>
      {outcome}
      <ProblemNote problem={problem} />
      <textarea value={text ?? ""} onChange={(e) => { setText(e.target.value); setSaved(false); }}
                style={{ minHeight: 380, fontFamily: "var(--mono)", fontSize: 12.5 }} disabled={text === null} />
      <div className="row"><span className="spacer" />{saved && <span className="badge badge-ok">Salvato</span>}
        <button className="btn btn-primary" onClick={save} disabled={text === null}>Salva Master Prompt</button></div>
    </div>
  );
}

// --- plan -----------------------------------------------------------------------

function PlanTab({ project, plan, docs, agents, onReload, onOpenTask }: {
  project: Project; plan: Plan; docs: WorkspaceDocuments | null; agents: Agent[]; onReload: () => void;
  onOpenTask: (id: number) => void;
}) {
  const api = useApi();
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [model, setModel] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [running, setRunning] = useState<PlanRun | null>(plan.runs.find((r) => r.status === "RUNNING") ?? null);
  const [handoffText, setHandoffText] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const { launch, outcome } = useLauncher();
  const [claude, setClaude] = useState<Software | null>(null);

  useEffect(() => {
    api.engineModels().then((list) => {
      const ready = list.models.filter((m) => m.available);
      setModels(ready);
      setModel(ready.find((m) => m.id === "ollama:qwen3.5:9b")?.id ?? "");
    }).catch(() => setModels([]));
    api.softwareEntry("claude-code").then((s) => setClaude(s.body)).catch(() => undefined);
  }, [api]);

  useEffect(() => {
    if (!running || running.status !== "RUNNING") return;
    const timer = window.setInterval(() => {
      api.planRun(running.id).then((run) => {
        setRunning(run);
        if (run.status !== "RUNNING") onReload();
      }).catch(setProblem);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [api, running, onReload]);

  const locked = project.planStatus === "APPROVED";
  const masterReady = !!docs?.masterPrompt && !docs.masterPromptIsTemplate;
  const last = running ?? plan.runs[0] ?? null;

  async function guard(action: () => Promise<unknown>) {
    setProblem(null);
    try {
      await action();
      onReload();
    } catch (error) {
      setProblem(error);
    }
  }

  async function planWithClaude() {
    setProblem(null);
    try {
      const text = await (await fetchHandoff()).trim();
      await api.writeFile(project.id, ".aicos/handoffs/PLAN-claude-code.md", text);
      const prompt = "Leggi .aicos/handoffs/PLAN-claude-code.md ed esegui quello che chiede.";
      setHandoffText(prompt);
      try { await navigator.clipboard.writeText(prompt); setCopied(true); } catch { setCopied(false); }
      if (claude) launch(claude, project.id);
    } catch (error) {
      setProblem(error);
    }
  }

  async function fetchHandoff(): Promise<string> {
    return api.readPlanHandoff(project.id);
  }

  return (
    <div className="stack">
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {!locked && (
        <div className="card card-body stack">
          <h2>Genera l'Implementation Plan</h2>
          {!masterReady && <div className="notice notice-warn">Prima scrivi il MASTER_PROMPT.md (scheda Master Prompt).</div>}
          <div className="grid grid-2">
            <div className="stack">
              <div className="section-title">Con l'AI Engine</div>
              <div className="row">
                <select value={model} onChange={(e) => setModel(e.target.value)} style={{ maxWidth: 280 }} aria-label="Modello">
                  <option value="">Default dell'engine</option>
                  {models.map((m) => <option key={m.id} value={m.id}>{m.id}{m.billed ? " — a consumo" : ""}</option>)}
                </select>
                <button className="btn btn-primary" disabled={!masterReady || running?.status === "RUNNING"}
                        onClick={() => guard(async () => setRunning(await api.generatePlan(project.id, model || undefined)))}>
                  <Icon name="sparkle" size={14} /> Genera piano</button>
              </div>
              <span className="muted" style={{ fontSize: 12 }}>Misurato su questa macchina: col 9B circa 13 minuti (fasi, poi task per fase, con l'avanzamento qui sotto); col 4B circa la metà. Il piano resta una bozza finché non lo approvi.</span>
            </div>
            <div className="stack">
              <div className="section-title">Con un agente esterno</div>
              <div className="row">
                <button className="btn" disabled={!masterReady} onClick={planWithClaude}>
                  <Icon name="terminal" size={14} /> Pianifica con Claude Code</button>
                <button className="btn" onClick={() => guard(() => api.importPlan(project.id))}>
                  <Icon name="refresh" size={14} /> Importa .aicos/plan.json</button>
              </div>
              <span className="muted" style={{ fontSize: 12 }}>Claude Code scrive <code>.aicos/plan.json</code> seguendo l'handoff; poi premi «Importa».</span>
              {handoffText && <div className="notice"><div>Prompt {copied ? "copiato negli appunti" : "da incollare"}:</div><code>{handoffText}</code></div>}
              {outcome}
            </div>
          </div>
          {last && (
            <div className={last.status === "FAILED" ? "notice notice-danger" : "notice"}>
              <div className="row">
                <strong>{last.status === "RUNNING" ? `Generazione in corso… ${last.progress ?? ""}` : last.status === "SUCCEEDED" ? "Ultima pianificazione riuscita" : "Ultima pianificazione fallita"}</strong>
                <span className="muted">{last.source === "ENGINE" ? last.servedModel ?? last.requestedModel ?? "engine" : "import"} · {when(last.createdAt)}</span>
              </div>
              {last.status === "SUCCEEDED" && <div>{last.phases} fasi, {last.tasks} task{last.outputTokens ? ` · ${last.inputTokens}→${last.outputTokens} token` : ""}</div>}
              {last.failureDetail && <div className="mono" style={{ fontSize: 12 }}>{last.failureDetail}</div>}
              {last.output && last.status === "FAILED" && <details><summary>Risposta del modello</summary><pre>{last.output}</pre></details>}
            </div>
          )}
        </div>
      )}

      {plan.phases.length > 0 && (
        <div className="row">
          <h2>Fasi e task</h2><span className="spacer" />
          {project.planStatus === "DRAFT" && (
            <button className="btn btn-primary" onClick={() => guard(async () => {
              const current = await api.project(project.id);
              await api.approvePlan(project.id, current.etag, true);
            })}><Icon name="check" size={14} /> Approva piano e tutte le fasi</button>
          )}
          {locked && <span className="badge badge-ok">Piano approvato</span>}
        </div>
      )}

      {plan.phases.map((phase) => (
        <PhaseCard key={phase.id} phase={phase} agents={agents} onOpenTask={onOpenTask}
                   onReview={(approve, note) => guard(() => api.reviewPhase(phase, approve, note))} />
      ))}
    </div>
  );
}

function PhaseCard({ phase, agents, onOpenTask, onReview }: {
  phase: Phase; agents: Agent[]; onOpenTask: (id: number) => void; onReview: (approve: boolean, note?: string) => void;
}) {
  const done = phase.tasks.filter((t) => t.status === "DONE").length;
  return (
    <div className="card">
      <div className="card-head">
        <span className="badge badge-accent">FASE {phase.number}</span>
        <h2>{phase.title}</h2>
        <span className={APPROVAL[phase.approval].tone}>{APPROVAL[phase.approval].label}</span>
        <span className="muted">{PHASE_STATUS[phase.status]} · {done}/{phase.tasks.length}</span>
        <span className="spacer" />
        {phase.approval !== "APPROVED" && <button className="btn btn-small btn-primary" onClick={() => onReview(true)}>Approva fase</button>}
        {phase.approval !== "CHANGES_REQUESTED" && (
          <button className="btn btn-small" onClick={() => {
            const note = window.prompt("Che cosa va cambiato in questa fase?") ?? undefined;
            if (note !== undefined) onReview(false, note);
          }}>Richiedi modifiche</button>
        )}
      </div>
      {phase.objective && <div className="card-body muted" style={{ paddingBottom: 0 }}>{phase.objective}</div>}
      {phase.approvalNote && <div className="card-body" style={{ paddingBottom: 0, fontSize: 12.5 }}>Nota: {phase.approvalNote}</div>}
      <table>
        <thead><tr><th>Codice</th><th>Task</th><th>Agente</th><th>Stato</th><th>Priorità</th></tr></thead>
        <tbody>
          {phase.tasks.map((task) => (
            <tr key={task.id} onClick={() => onOpenTask(task.id)} style={{ cursor: "pointer" }}>
              <td className="mono">{task.code}</td>
              <td>{task.title}</td>
              <td className="muted">{agents.find((a) => a.id === task.agentId)?.name ?? "da assegnare"}</td>
              <td><StatusBadge status={task.status} /></td>
              <td><PriorityBadge priority={task.priority} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// --- documents ------------------------------------------------------------------

function Documents({ project, docs }: { project: Project; docs: WorkspaceDocuments | null }) {
  const api = useApi();
  const [open, setOpen] = useState<string | null>(null);
  const [content, setContent] = useState<string>("");

  useEffect(() => {
    if (!open) return;
    setContent("…");
    api.readFile(project.id, open).then(setContent).catch((e) => setContent(String(e)));
  }, [api, project.id, open]);

  if (!docs) return <div className="muted">Nessuna cartella.</div>;
  const groups: [string, { path: string; size: number }[]][] = [
    ["Progetto", [docs.masterPrompt, docs.agents, docs.implementationPlan, docs.planJson].filter((d): d is NonNullable<typeof d> => !!d)],
    ["Fasi", docs.phases], ["Task", docs.tasks], ["Handoff", docs.handoffs],
  ];
  return (
    <div className="split">
      <div className="card">
        {groups.map(([title, list]) => (
          <div key={title}>
            <div className="card-head"><div className="section-title">{title}</div></div>
            {list.length === 0 && <div className="list-item muted">—</div>}
            {list.map((d) => (
              <button key={d.path} className="list-item" style={{ width: "100%", background: open === d.path ? "var(--surface-2)" : "none", border: 0, color: "inherit", cursor: "pointer", textAlign: "left" }}
                      onClick={() => setOpen(d.path)}>
                <Icon name="file" size={15} /><span className="mono" style={{ fontSize: 12.5 }}>{d.path}</span>
                <span className="spacer" /><span className="muted" style={{ fontSize: 11 }}>{Math.max(1, Math.round(d.size / 1024))} KB</span>
              </button>
            ))}
          </div>
        ))}
      </div>
      <div className="card card-body">
        {open ? <><div className="mono muted" style={{ marginBottom: 8 }}>{open}</div><pre className="doc">{content}</pre></>
          : <div className="muted">Scegli un documento.</div>}
      </div>
    </div>
  );
}

// --- references -----------------------------------------------------------------

function References({ project, docs, onUploaded }: { project: Project; docs: WorkspaceDocuments | null; onUploaded: () => void }) {
  const api = useApi();
  const [urls, setUrls] = useState<Record<string, string>>({});
  const [gemini, setGemini] = useState<Software | null>(null);
  const [folder, setFolder] = useState("images");
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const { launch } = useLauncher();

  useEffect(() => {
    let cancelled = false;
    (docs?.references ?? []).forEach((ref) => {
      api.fileUrl(project.id, ref.path).then((url) => {
        if (!cancelled && url) setUrls((prev) => ({ ...prev, [ref.path]: url }));
      });
    });
    api.softwareEntry("gemini").then((s) => setGemini(s.body)).catch(() => undefined);
    return () => { cancelled = true; };
  }, [api, project.id, docs]);

  async function upload(files: File[]) {
    setBusy(true);
    setProblem(null);
    try {
      for (const file of files) {
        await api.uploadReference(project.id, folder, file, file.name || `incollata-${Date.now()}.png`);
      }
      onUploaded();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  // Ctrl+V of an image copied from Gemini, a screenshot tool or the browser.
  useEffect(() => {
    const onPaste = (event: ClipboardEvent) => {
      const images = Array.from(event.clipboardData?.files ?? []).filter((f) => f.type.startsWith("image/"));
      if (images.length > 0) {
        event.preventDefault();
        upload(images);
      }
    };
    window.addEventListener("paste", onPaste);
    return () => window.removeEventListener("paste", onPaste);
  }); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="stack">
      <div className="notice">
        Le immagini in <code>{project.workspacePath}\references\</code> (reference di Gemini, mockup, screenshot, design target)
        diventano contesto per gli agenti di UI, grafica e 3D.
        {gemini && <button className="btn btn-small" style={{ marginLeft: 8 }} onClick={() => launch(gemini)}>Apri Gemini</button>}
      </div>
      <div className="card card-body row">
        <select value={folder} onChange={(e) => setFolder(e.target.value)} aria-label="Cartella" style={{ width: "auto" }}>
          <option value="images">Immagini</option><option value="mockups">Mockup</option>
          <option value="screenshots">Screenshot</option><option value="design-targets">Design target</option>
        </select>
        <label className="btn btn-primary" style={{ cursor: "pointer" }}>
          {busy ? "Carico…" : "Aggiungi immagini"}
          <input type="file" accept="image/png,image/jpeg,image/gif,image/webp" multiple style={{ display: "none" }}
                 onChange={(e) => { const files = Array.from(e.target.files ?? []); e.target.value = ""; if (files.length) upload(files); }} />
        </label>
        <span className="muted" style={{ fontSize: 12.5 }}>…oppure incolla un'immagine con Ctrl+V. PNG, JPEG, GIF o WebP, fino a 10 MB.</span>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="tile-grid">
        {(docs?.references ?? []).map((ref) => (
          <div key={ref.path} className="tile" style={{ cursor: "default" }}>
            {urls[ref.path] ? <img src={urls[ref.path]} alt={ref.path} style={{ width: "100%", borderRadius: 8, maxHeight: 160, objectFit: "cover" }} />
              : <div className="muted">…</div>}
            <span className="mono" style={{ fontSize: 11 }}>{ref.path.replace("references/", "")}</span>
          </div>
        ))}
      </div>
      {docs && docs.references.length === 0 && <div className="muted">Nessuna immagine ancora.</div>}
    </div>
  );
}

// --- tools ----------------------------------------------------------------------

function Tools({ project, type }: { project: Project; type: ProjectTypeInfo | undefined }) {
  const api = useApi();
  const [software, setSoftware] = useState<Software[]>([]);
  const { launch, outcome } = useLauncher();

  useEffect(() => {
    api.software().then(setSoftware).catch(() => setSoftware([]));
  }, [api]);

  const recommended = (type?.software ?? []).map((k) => software.find((s) => s.key === k)).filter((s): s is Software => !!s);
  const others = software.filter((s) => (s.openFolder || s.launchKind === "CLI") && !recommended.includes(s));

  const card = (s: Software) => (
    <button key={s.key} className="tile" disabled={s.launchKind !== "WEB" && !s.launchable}
            onClick={() => launch(s, s.openFolder || s.launchKind === "CLI" ? project.id : undefined)}>
      <AppIcon software={s} size={40} /><h3>{s.name}</h3>
      <span className="role">{s.openFolder ? "apre la cartella del progetto" : s.launchKind === "CLI" ? "terminale nella cartella" : s.role}</span>
    </button>
  );

  return (
    <div className="stack">
      {outcome}
      <div className="section-title">Consigliati per {type?.label ?? "questo progetto"}</div>
      <div className="tile-grid">{recommended.map(card)}</div>
      <div className="section-title">Altri IDE e terminali</div>
      <div className="tile-grid">{others.map(card)}</div>
    </div>
  );
}
