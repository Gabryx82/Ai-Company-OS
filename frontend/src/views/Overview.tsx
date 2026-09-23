import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { Agent, ModelList, Project, Task } from "../api/types";
import { ProblemNote } from "../components/ui";

export function Overview() {
  const api = useApi();
  const [data, setData] = useState<{ tasks: Task[]; agents: Agent[]; projects: Project[] } | null>(null);
  const [models, setModels] = useState<ModelList | null>(null);
  const [engineProblem, setEngineProblem] = useState<unknown>(null);
  const [problem, setProblem] = useState<unknown>(null);

  useEffect(() => {
    Promise.all([api.tasks(), api.agents(), api.projects()])
      .then(([tasks, agents, projects]) => setData({ tasks, agents, projects }))
      .catch(setProblem);
    api.engineModels().then(setModels).catch(setEngineProblem);
  }, [api]);

  const count = (status: Task["status"]) => data?.tasks.filter((t) => t.status === status).length ?? "…";
  const available = models?.models.filter((m) => m.available) ?? [];

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Overview</h1>
          <p>What the company is working on, who is available, and which models can run.</p>
        </div>
        <a className="btn btn-primary" href="#/tasks">Open the board</a>
      </div>
      <ProblemNote problem={problem} />
      <div className="stats">
        <div className="card stat"><span className="muted">Open tasks</span><strong>{count("OPEN")}</strong></div>
        <div className="card stat"><span className="muted">In progress</span><strong>{count("IN_PROGRESS")}</strong></div>
        <div className="card stat"><span className="muted">Done</span><strong>{count("DONE")}</strong></div>
        <div className="card stat"><span className="muted">Active agents</span>
          <strong>{data ? data.agents.filter((a) => a.active).length : "…"}</strong></div>
        <div className="card stat"><span className="muted">Active projects</span>
          <strong>{data ? data.projects.filter((p) => p.status === "ACTIVE").length : "…"}</strong></div>
        <div className="card stat"><span className="muted">Models available</span>
          <strong>{models ? available.length : engineProblem ? "—" : "…"}</strong></div>
      </div>
      {engineProblem ? (
        <div className="notice notice-warn">
          <strong>The AI Engine is not answering.</strong> Runs will fail as <code>engine-unreachable</code> until
          it is started: <code>cd ai-engine &amp;&amp; .venv/Scripts/python -m app</code>.
        </div>
      ) : null}
      <div className="card card-body stack">
        <h2>How work flows here</h2>
        <ol className="muted" style={{ margin: 0, paddingLeft: 18 }}>
          <li>Create a task on the board, optionally inside a project.</li>
          <li>Open it: the console suggests which agent fits, and you assign one.</li>
          <li>Run it. The agent's model answers through the AI Engine; the task moves to <em>In progress</em>.</li>
          <li>Read the output. You decide whether the work is done — a run never completes a task by itself.</li>
        </ol>
      </div>
    </div>
  );
}
