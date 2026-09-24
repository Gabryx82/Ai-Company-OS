import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi } from "../context";
import { ApiProblem, type Versioned } from "../api/client";
import {
  TASK_PRIORITIES, TRANSITIONS, type Agent, type ModelInfo, type Project, type Run, type Suggestion, type Task,
  type TaskPriority, type Transition,
} from "../api/types";
import { Field, PriorityBadge, ProblemNote, StatusBadge, when } from "../components/ui";
import { OrchestratorPanel } from "./OrchestratorPanel";

const STEP_LABEL: Record<Transition, string> = { start: "Start", complete: "Mark done", stop: "Stop", reopen: "Reopen" };

/**
 * One task, with the ETag it was read at.
 *
 * Every action here sends that tag (ADR-009). When somebody else wrote in the
 * meantime the server answers 412: the drawer re-reads the task and says so,
 * instead of retrying with the new tag -- retrying would overwrite a change the
 * operator never saw, which is exactly what the protocol exists to prevent.
 */
export function TaskDrawer({ taskId, agents, projects, onClose, onChanged }: {
  taskId: number; agents: Agent[]; projects: Project[]; onClose: () => void; onChanged: () => void;
}) {
  const api = useApi();
  const [version, setVersion] = useState<Versioned<Task> | null>(null);
  const [runs, setRuns] = useState<Run[]>([]);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const [task, taskRuns, fits] = await Promise.all([api.task(taskId), api.runs(taskId), api.suggestions(taskId)]);
    setVersion(task);
    setRuns(taskRuns);
    setSuggestions(fits);
  }, [api, taskId]);

  useEffect(() => {
    load().catch(setProblem);
    api.engineModels().then((list) => setModels(list.models.filter((m) => m.available))).catch(() => setModels([]));
  }, [api, load]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // Poll while a run is unfinished; stop as soon as none is.
  const unfinished = runs.some((r) => r.status === "QUEUED" || r.status === "RUNNING");
  useEffect(() => {
    if (!unfinished) return;
    const timer = window.setInterval(() => {
      Promise.all([api.runs(taskId), api.task(taskId)]).then(([taskRuns, task]) => {
        setRuns(taskRuns);
        setVersion(task);
      }).catch(setProblem);
    }, 1500);
    return () => window.clearInterval(timer);
  }, [api, taskId, unfinished]);

  /** Runs one mutation with the tag in hand; on 412 re-reads and reports it. */
  async function act(mutation: (etag: string) => Promise<unknown>) {
    if (!version) return;
    setBusy(true);
    setProblem(null);
    try {
      await mutation(version.etag);
      await load();
      onChanged();
    } catch (error) {
      setProblem(error);
      if (error instanceof ApiProblem && error.isStale) await load().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  const task = version?.body;
  const agent = agents.find((a) => a.id === task?.agentId);
  const project = projects.find((p) => p.id === task?.projectId);

  return (
    <div className="overlay" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={task ? `Task ${task.title}` : "Task"}>
        <div className="row">
          <span className="mono muted">Task #{taskId}</span>
          <span className="spacer" />
          <button className="btn btn-small" onClick={onClose}>Close</button>
        </div>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {!task ? <div className="muted">Loading…</div> : (
          <>
            <div className="section">
              <h1>{task.title}</h1>
              <div className="row">
                <StatusBadge status={task.status} />
                <PriorityBadge priority={task.priority} />
                <span className="muted">{agent ? `${agent.name}${agent.active ? "" : " (inactive)"}` : "unassigned"}</span>
                {project && <span className="muted">· {project.name}{project.status === "ARCHIVED" ? " (archived)" : ""}</span>}
              </div>
              <div className="row">
                {(Object.keys(TRANSITIONS) as Transition[]).filter((step) => TRANSITIONS[step] === task.status).map((step) => (
                  <button key={step} className={step === "complete" ? "btn btn-primary" : "btn"} disabled={busy}
                          onClick={() => act((etag) => api.transition(task.id, step, etag))}>
                    {STEP_LABEL[step]}
                  </button>
                ))}
              </div>
            </div>

            {task.code && <OrchestratorPanel task={task} busy={busy} act={act} />}

            <RunSection task={task} agent={agent} runs={runs} models={models} busy={busy}
                        onLaunch={(model) => act((etag) => api.launchRun(task.id, etag, model))} />

            <AssignSection task={task} agents={agents} projects={projects} suggestions={suggestions} busy={busy}
                           onAgent={(id) => act((etag) => api.assignAgent(task.id, etag, id))}
                           onProject={(id) => act((etag) => api.assignProject(task.id, etag, id))} />

            <DetailsSection key={version.etag} task={task} busy={busy}
                            onSave={(details) => act((etag) => api.updateTask(task.id, etag, details))} />
          </>
        )}
      </aside>
    </div>
  );
}

function RunSection({ task, agent, runs, models, busy, onLaunch }: {
  task: Task; agent: Agent | undefined; runs: Run[]; models: ModelInfo[]; busy: boolean; onLaunch: (model?: string) => void;
}) {
  const [model, setModel] = useState("");
  const blocked = task.status === "DONE" ? "Reopen the task to run it again."
    : !agent ? "Assign an agent first."
      : !agent.active ? "The agent is inactive: activate it or reassign the task."
        : runs.some((r) => r.status === "QUEUED" || r.status === "RUNNING") ? "A run is in progress." : null;

  return (
    <div className="section">
      <div className="section-title">Runs</div>
      <div className="row">
        <select aria-label="Model" value={model} onChange={(e) => setModel(e.target.value)} style={{ maxWidth: 320 }}>
          <option value="">{agent?.model ? `Agent's model (${agent.model})` : "Engine default"}</option>
          {models.map((m) => <option key={m.id} value={m.id}>{m.id}{m.billed ? " — billed" : ""}</option>)}
        </select>
        <button className="btn btn-primary" disabled={busy || blocked !== null} onClick={() => onLaunch(model || undefined)}>
          Run with {agent?.name ?? "agent"}
        </button>
      </div>
      {blocked && <div className="muted">{blocked}</div>}
      {runs.length === 0 && <div className="muted">No run yet.</div>}
      {runs.map((run) => <RunCard key={run.id} run={run} />)}
    </div>
  );
}

function RunCard({ run }: { run: Run }) {
  return (
    <article className="run" aria-label={`Run ${run.id}`}>
      <div className="row">
        <StatusBadge status={run.status} />
        <span className="mono">run #{run.id}</span>
        <span className="muted">{run.servedModel ?? run.requestedModel ?? "engine default"}</span>
        <span className="spacer" />
        <span className="muted">{when(run.createdAt)}</span>
      </div>
      {run.status === "SUCCEEDED" && (
        <>
          {run.finishReason === "refusal" && <div className="notice notice-warn">The model declined this request.</div>}
          {run.finishReason === "length" && <div className="notice notice-warn">The output was cut at the token limit.</div>}
          <pre className="output">{run.output || "(empty)"}</pre>
          <div className="muted">
            {run.inputTokens} → {run.outputTokens} tokens · {((run.latencyMs ?? 0) / 1000).toFixed(1)} s · by {run.requestedBy}
          </div>
        </>
      )}
      {run.status === "FAILED" && (
        <div className="notice notice-danger">
          <div>{run.failureDetail ?? "The run failed."}</div>
          <div className="mono">{run.failureType}</div>
        </div>
      )}
      {(run.status === "QUEUED" || run.status === "RUNNING") && (
        <div className="muted">{run.status === "QUEUED" ? "Waiting for an executor…" : "The model is working…"}</div>
      )}
      <details>
        <summary>What was sent</summary>
        <pre>{run.systemPrompt}{"\n\n"}{run.userPrompt}</pre>
        <div className="mono muted">{run.correlationId}</div>
      </details>
    </article>
  );
}

function AssignSection({ task, agents, projects, suggestions, busy, onAgent, onProject }: {
  task: Task; agents: Agent[]; projects: Project[]; suggestions: Suggestion[]; busy: boolean;
  onAgent: (id: number) => void; onProject: (id: number) => void;
}) {
  const [agentId, setAgentId] = useState("");
  const [projectId, setProjectId] = useState("");
  const best = suggestions[0];
  return (
    <div className="section">
      <div className="section-title">Assignment</div>
      {best && best.score > 0 && best.agentId !== task.agentId && (
        <div className="notice">
          <div className="row">
            <span>Suggested: <strong>{best.name}</strong> — matches {best.matchedTerms.map((t) => <code key={t}>{t} </code>)}</span>
            <span className="spacer" />
            <button className="btn btn-small" disabled={busy} onClick={() => onAgent(best.agentId)}>Assign</button>
          </div>
        </div>
      )}
      <div className="row">
        <select aria-label="Agent" value={agentId} onChange={(e) => setAgentId(e.target.value)} style={{ maxWidth: 280 }}>
          <option value="">Choose an agent…</option>
          {agents.filter((a) => a.active).map((a) => <option key={a.id} value={a.id}>{a.name} — {a.role}</option>)}
        </select>
        <button className="btn" disabled={busy || !agentId} onClick={() => onAgent(Number(agentId))}>Assign agent</button>
      </div>
      <div className="row">
        <select aria-label="Project" value={projectId} onChange={(e) => setProjectId(e.target.value)} style={{ maxWidth: 280 }}>
          <option value="">Choose a project…</option>
          {projects.filter((p) => p.status === "ACTIVE").map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        <button className="btn" disabled={busy || !projectId} onClick={() => onProject(Number(projectId))}>Move to project</button>
      </div>
    </div>
  );
}

function DetailsSection({ task, busy, onSave }: {
  task: Task; busy: boolean; onSave: (details: { title: string; description?: string; priority: TaskPriority }) => void;
}) {
  const [title, setTitle] = useState(task.title);
  const [description, setDescription] = useState(task.description ?? "");
  const [priority, setPriority] = useState<TaskPriority>(task.priority);
  const dirty = title !== task.title || description !== (task.description ?? "") || priority !== task.priority;

  function submit(event: FormEvent) {
    event.preventDefault();
    onSave({ title, description: description || undefined, priority });
  }

  return (
    <form className="section" onSubmit={submit}>
      <div className="section-title">Details</div>
      <Field label="Title"><input value={title} onChange={(e) => setTitle(e.target.value)} required /></Field>
      <Field label="Description"><textarea value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
      <Field label="Priority">
        <select value={priority} onChange={(e) => setPriority(e.target.value as TaskPriority)} style={{ maxWidth: 160 }}>
          {TASK_PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
        </select>
      </Field>
      <div className="row"><span className="spacer" /><button className="btn" type="submit" disabled={busy || !dirty}>Save details</button></div>
    </form>
  );
}
