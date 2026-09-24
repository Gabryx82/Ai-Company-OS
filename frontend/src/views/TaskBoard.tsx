import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi } from "../context";
import { ApiProblem } from "../api/client";
import { TASK_PRIORITIES, TASK_STATUSES, type Agent, type Project, type Task, type TaskPriority } from "../api/types";
import { Field, Modal, PriorityBadge, ProblemNote } from "../components/ui";
import { TaskDrawer } from "./TaskDrawer";

const COLUMN_TITLE = { OPEN: "Da fare", IN_PROGRESS: "In corso", DONE: "Completate" } as const;

/**
 * The board: three columns, one per status of ADR-011. Cards move only through
 * the drawer, which holds the task's ETag -- the listing carries none (TD-33).
 */
export function TaskBoard() {
  const api = useApi();
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [openTask, setOpenTask] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);

  const reload = useCallback(() => {
    Promise.all([api.tasks(), api.agents(), api.projects()])
      .then(([t, a, p]) => { setTasks(t); setAgents(a); setProjects(p); })
      .catch(setProblem);
  }, [api]);

  useEffect(reload, [reload]);

  const agentName = (id: number | null) => agents.find((a) => a.id === id)?.name;
  const projectName = (id: number | null) => projects.find((p) => p.id === id)?.name;

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Task</h1>
          <p>Apri una card per assegnarla, orchestrarla, eseguirla e portarla lungo il suo ciclo di vita.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setCreating(true)}>Nuova task</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="board">
        {TASK_STATUSES.map((status) => {
          const column = tasks?.filter((t) => t.status === status) ?? [];
          return (
            <section key={status} className="column" aria-label={COLUMN_TITLE[status]}>
              <div className="column-head"><span>{COLUMN_TITLE[status]}</span><span className="badge">{tasks ? column.length : "…"}</span></div>
              {column.map((task) => (
                <button key={task.id} className="task-card" onClick={() => setOpenTask(task.id)}>
                  <strong>{task.title}</strong>
                  <div className="meta">
                    <span className="mono">#{task.id}</span>
                    <PriorityBadge priority={task.priority} />
                    <span>{agentName(task.agentId) ?? "non assegnata"}</span>
                    {task.projectId && <span>· {projectName(task.projectId)}</span>}
                  </div>
                </button>
              ))}
              {tasks && column.length === 0 && <div className="muted" style={{ padding: 8 }}>Niente qui.</div>}
            </section>
          );
        })}
      </div>
      {creating && (
        <CreateTask agents={agents} projects={projects} onClose={() => setCreating(false)}
                    onCreated={(id) => { setCreating(false); reload(); setOpenTask(id); }} />
      )}
      {openTask !== null && (
        <TaskDrawer taskId={openTask} agents={agents} projects={projects}
                    onClose={() => { setOpenTask(null); reload(); }} onChanged={reload} />
      )}
    </div>
  );
}

function CreateTask({ agents, projects, onClose, onCreated }: {
  agents: Agent[]; projects: Project[]; onClose: () => void; onCreated: (id: number) => void;
}) {
  const api = useApi();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState<TaskPriority>("MEDIUM");
  const [agentId, setAgentId] = useState("");
  const [projectId, setProjectId] = useState("");
  const [problem, setProblem] = useState<unknown>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      const created = await api.createTask({
        title, description: description || undefined, status: "OPEN", priority,
        agentId: agentId ? Number(agentId) : undefined, projectId: projectId ? Number(projectId) : undefined,
      });
      onCreated(created.body.id);
    } catch (error) {
      setProblem(error);
    }
  }

  const errors = problem instanceof ApiProblem ? problem.errors : {};
  return (
    <Modal title="Nuova task" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Field label="Titolo" error={errors.title}><input value={title} onChange={(e) => setTitle(e.target.value)} required autoFocus /></Field>
        <Field label="Descrizione" hint="Ciò che l'agente leggerà, alla lettera.">
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <div className="row">
          <Field label="Priorità">
            <select value={priority} onChange={(e) => setPriority(e.target.value as TaskPriority)}>
              {TASK_PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
            </select>
          </Field>
          <Field label="Agente">
            <select value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">Non assegnata</option>
              {agents.filter((a) => a.active).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </Field>
          <Field label="Progetto">
            <select value={projectId} onChange={(e) => setProjectId(e.target.value)}>
              <option value="">Nessuno</option>
              {projects.filter((p) => p.status === "ACTIVE").map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </Field>
        </div>
        <ProblemNote problem={problem} />
        <div className="row"><span className="spacer" /><button className="btn btn-primary" type="submit">Crea</button></div>
      </form>
    </Modal>
  );
}
