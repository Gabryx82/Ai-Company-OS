import { useCallback, useEffect, useState } from "react";
import { useApi, useIsAdmin } from "../context";
import { DeleteDialog } from "../components/DeleteDialog";
import type { TasksPolicy } from "../api/types";
import type { AutonomyLevel, Project, ProjectType, ProjectTypeInfo, Task } from "../api/types";
import { Field, Modal, ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";
import { AUTONOMY, TYPE_ICON } from "./projectLabels";

import { ProjectDetail } from "./ProjectDetail";

const PLAN_LABEL: Record<string, { label: string; tone: string }> = {
  NONE: { label: "Senza piano", tone: "badge" },
  DRAFT: { label: "Piano da approvare", tone: "badge badge-warn" },
  APPROVED: { label: "Piano approvato", tone: "badge badge-ok" },
};

export function Projects({ selected, navigate }: { selected: string | null; navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [types, setTypes] = useState<ProjectTypeInfo[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const [deleting, setDeleting] = useState<Project | null>(null);
  const isAdmin = useIsAdmin();

  const reload = useCallback(() => {
    api.projects().then(setProjects).catch(setProblem);
    api.tasks().then(setTasks).catch(() => setTasks([]));
  }, [api]);
  useEffect(() => {
    reload();
    api.projectTypes().then(setTypes).catch(() => setTypes([]));
  }, [api, reload]);

  if (selected) {
    return <ProjectDetail projectId={Number(selected)} types={types} navigate={navigate} />;
  }

  async function toggle(project: Project) {
    setProblem(null);
    try {
      const current = await api.project(project.id);
      await api.setProjectArchived(project.id, current.etag, current.body.status === "ACTIVE");
      reload();
    } catch (error) {
      setProblem(error);
    }
  }

  const shown = (projects ?? []).filter((p) => showArchived || p.status === "ACTIVE");

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Progetti</h1><p>Idea → Master Prompt → Piano → Fasi → Task → Esecuzione → Review. Archiviare congela i task ed è reversibile; eliminare è definitivo e riservato agli admin.</p></div>
        <div className="row">
          <label className="muted row" style={{ gap: 6 }}>
            <input type="checkbox" style={{ width: "auto" }} checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
            archiviati
          </label>
          <button className="btn btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={14} /> Nuovo progetto</button>
        </div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="tile-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))" }}>
        {shown.map((project) => {
          const own = tasks.filter((t) => t.projectId === project.id);
          const done = own.filter((t) => t.status === "DONE").length;
          const pct = own.length ? Math.round((done / own.length) * 100) : 0;
          const type = types.find((t) => t.type === project.projectType);
          return (
            <div key={project.id} className="tile" style={{ justifyItems: "stretch", textAlign: "left" }}
                 onClick={() => navigate("projects", String(project.id))} role="button" tabIndex={0}>
              <div className="row">
                <span className="kpi-icon glow-violet" style={{ width: 36, height: 36, borderRadius: 10, display: "grid", placeItems: "center" }}>
                  <Icon name={TYPE_ICON[project.projectType ?? "OTHER"] ?? "projects"} /></span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <h3 style={{ margin: 0 }}>{project.name}</h3>
                  <span className="chip">{type?.label ?? "Tipologia non indicata"}</span>
                </div>
              </div>
              {project.description && <div className="muted" style={{ fontSize: 12.5 }}>{project.description}</div>}
              <div className="row" style={{ fontSize: 12 }}>
                <span className="muted">Progresso</span><span className="spacer" /><strong>{pct}%</strong>
              </div>
              <div className="progress"><span style={{ width: `${pct}%` }} /></div>
              <div className="row" style={{ fontSize: 12 }}>
                <span className="muted">{own.length} task</span><span className="spacer" />
                <span className={PLAN_LABEL[project.planStatus ?? "NONE"].tone}>{PLAN_LABEL[project.planStatus ?? "NONE"].label}</span>
              </div>
              <div className="row" onClick={(e) => e.stopPropagation()}>
                {project.status === "ARCHIVED" && <span className="badge">archiviato</span>}
                <span className="spacer" />
                <button className="btn btn-small" onClick={() => toggle(project)}>
                  {project.status === "ACTIVE" ? "Archivia" : "Ripristina"}</button>
                {isAdmin && <button className="btn btn-small btn-danger" onClick={() => setDeleting(project)}>Elimina</button>}
              </div>
            </div>
          );
        })}
      </div>
      {projects?.length === 0 && <div className="card card-body muted">Nessun progetto ancora: creane uno.</div>}
      {deleting && <DeleteProject project={deleting} onClose={() => setDeleting(null)}
                                  onDeleted={() => { setDeleting(null); reload(); }} />}
      {creating && <CreateProject types={types} onClose={() => setCreating(false)}
                                  onCreated={(id) => { setCreating(false); reload(); navigate("projects", String(id)); }} />}
    </div>
  );
}

/** ADR-027: the tasks' fate is chosen, never defaulted; archiving stays the reversible way. */
function DeleteProject({ project, onClose, onDeleted }: { project: Project; onClose: () => void; onDeleted: () => void }) {
  const api = useApi();
  const [policy, setPolicy] = useState<TasksPolicy>("DETACH");
  const load = useCallback(() => api.projectDeletionPreview(project.id, policy), [api, project.id, policy]);
  return (
    <DeleteDialog key={policy} what="il progetto" name={project.name} loadImpact={load} onClose={onClose}
                  archiveHint={project.status === "ACTIVE" ? "Se vuoi solo metterlo da parte, usa «Archivia»: si ripristina quando vuoi." : undefined}
                  onConfirm={async (confirm) => {
                    const current = await api.project(project.id);
                    await api.deleteProject(project.id, current.etag, policy, confirm);
                    onDeleted();
                  }}>
      <div className="stack" style={{ gap: 4 }}>
        <strong>Le task del progetto</strong>
        <label className="row" style={{ gap: 6 }}><input type="radio" style={{ width: "auto" }} checked={policy === "DETACH"}
               onChange={() => setPolicy("DETACH")} /> Restano, senza progetto</label>
        <label className="row" style={{ gap: 6 }}><input type="radio" style={{ width: "auto" }} checked={policy === "DELETE"}
               onChange={() => setPolicy("DELETE")} /> Vengono eliminate anche loro</label>
      </div>
    </DeleteDialog>
  );
}

const STEPS = ["Tipologia", "Dettagli", "Stack", "Conferma"];

/**
 * The directive's creation flow: the type first, then the details, then the
 * stack the Master Orchestrator proposes for that type, then the workspace.
 */
function CreateProject({ types, onClose, onCreated }: {
  types: ProjectTypeInfo[]; onClose: () => void; onCreated: (id: number) => void;
}) {
  const api = useApi();
  const [step, setStep] = useState(0);
  const [type, setType] = useState<ProjectType | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [stack, setStack] = useState("");
  const [folder, setFolder] = useState("");
  const [autonomy, setAutonomy] = useState<AutonomyLevel>("GUIDED");
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const info = types.find((t) => t.type === type);

  function choose(next: ProjectTypeInfo) {
    setType(next.type as ProjectType);
    setStack(next.stack.join("\n"));
    setStep(1);
  }

  useEffect(() => {
    if (step !== 3 || folder || !name.trim()) return;
    api.defaultFolder(name).then((f) => setFolder(f.path)).catch(() => undefined);
  }, [api, step, name, folder]);

  async function create() {
    setBusy(true);
    setProblem(null);
    try {
      const created = await api.createProject({ name, description: description || undefined });
      const configured = await api.configureProject(created.body.id, created.etag,
        { projectType: type, stack: stack.trim() || null, workspacePath: folder.trim() || null, autonomyLevel: autonomy });
      await api.scaffold(configured.body.id);
      onCreated(configured.body.id);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="Nuovo progetto" onClose={onClose} wide>
      <div className="stepper">
        {STEPS.map((label, i) => (
          <span key={label} className={`step ${i < step ? "done" : i === step ? "current" : ""}`}>
            <b>{i < step ? "✓" : i + 1}</b>{label}{i < STEPS.length - 1 && <span className="muted"> —</span>}
          </span>
        ))}
      </div>
      <ProblemNote problem={problem} />

      {step === 0 && (
        <div className="stack">
          <p className="muted" style={{ margin: 0 }}>Seleziona la tipologia: il Master Orchestrator propone lo stack e gli strumenti.</p>
          <div className="type-grid">
            {types.map((t) => (
              <button key={t.type} className="type-card" aria-pressed={type === t.type} onClick={() => choose(t)}>
                <Icon name={TYPE_ICON[t.type] ?? "projects"} size={22} />
                <strong style={{ fontSize: 13 }}>{t.label}</strong>
                <small>{t.description}</small>
              </button>
            ))}
          </div>
        </div>
      )}

      {step === 1 && (
        <div className="stack">
          <Field label="Nome"><input value={name} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
          <Field label="Descrizione" hint="Il dettaglio vero arriverà dal MASTER PROMPT del brainstorming.">
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
          <div className="row"><button className="btn" onClick={() => setStep(0)}>Indietro</button><span className="spacer" />
            <button className="btn btn-primary" disabled={!name.trim()} onClick={() => setStep(2)}>Avanti</button></div>
        </div>
      )}

      {step === 2 && (
        <div className="stack">
          <Field label={`Stack proposto per ${info?.label ?? "il progetto"}`} hint="Una riga per tecnologia. Il piano potrà raffinarlo.">
            <textarea value={stack} onChange={(e) => setStack(e.target.value)} style={{ minHeight: 110 }} /></Field>
          {info && info.software.length > 0 && (
            <div><div className="section-title">Software consigliati</div>{info.software.map((s) => <span key={s} className="chip">{s}</span>)}</div>
          )}
          {info && info.agentRoles.length > 0 && (
            <div><div className="section-title">Ruoli coinvolti</div>{info.agentRoles.map((s) => <span key={s} className="chip">{s}</span>)}</div>
          )}
          <div className="row"><button className="btn" onClick={() => setStep(1)}>Indietro</button><span className="spacer" />
            <button className="btn btn-primary" onClick={() => setStep(3)}>Avanti</button></div>
        </div>
      )}

      {step === 3 && (
        <div className="stack">
          <Field label="Cartella del progetto" hint="Assoluta. Una cartella esistente va bene: nessun file già presente viene sovrascritto.">
            <input value={folder} onChange={(e) => setFolder(e.target.value)} className="mono" /></Field>
          <Field label="Livello di Human-in-the-Loop" hint={AUTONOMY[autonomy].hint}>
            <select value={autonomy} onChange={(e) => setAutonomy(e.target.value as AutonomyLevel)}>
              {(Object.keys(AUTONOMY) as AutonomyLevel[]).map((level) => <option key={level} value={level}>{AUTONOMY[level].label}</option>)}
            </select></Field>
          <div className="notice">Verranno creati <code>MASTER_PROMPT.md</code>, <code>AGENTS.md</code>, <code>CLAUDE.md</code>,
            <code> docs/</code>, <code>tasks/</code>, <code>references/</code> e <code>.aicos/</code>.</div>
          <div className="row"><button className="btn" onClick={() => setStep(2)}>Indietro</button><span className="spacer" />
            <button className="btn btn-primary" disabled={busy} onClick={create}>Crea progetto</button></div>
        </div>
      )}
    </Modal>
  );
}
