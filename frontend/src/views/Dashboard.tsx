import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { Agent, ModelList, Project, Software, Task } from "../api/types";
import { PriorityBadge, ProblemNote, StatusBadge } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon } from "../components/icons";
import { displayName } from "../preferences";
import { useLauncher } from "./SoftwareHub";

const QUICK = ["claude-code", "codex", "antigravity-ide", "vscode-continue", "intellij-junie", "chatgpt-classic",
  "postman", "mysql-workbench", "open-webui", "omniverse-3d"];

function greeting(): string {
  const hour = new Date().getHours();
  return hour < 13 ? "Buongiorno" : hour < 18 ? "Buon pomeriggio" : "Buonasera";
}

export function Dashboard({ navigate }: { navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const [data, setData] = useState<{ tasks: Task[]; agents: Agent[]; projects: Project[] } | null>(null);
  const [software, setSoftware] = useState<Software[] | null>(null);
  const [models, setModels] = useState<ModelList | null>(null);
  const [engineDown, setEngineDown] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const { launch, outcome } = useLauncher();

  useEffect(() => {
    Promise.all([api.tasks(), api.agents(), api.projects()])
      .then(([tasks, agents, projects]) => setData({ tasks, agents, projects }))
      .catch(setProblem);
    api.software().then(setSoftware).catch(() => setSoftware([]));
    api.engineModels().then(setModels).catch(() => setEngineDown(true));
  }, [api]);

  const active = data?.projects.filter((p) => p.status === "ACTIVE") ?? [];
  const inProgress = data?.tasks.filter((t) => t.status === "IN_PROGRESS") ?? [];
  const open = data?.tasks.filter((t) => t.status === "OPEN") ?? [];
  const usable = software?.filter((s) => s.availability === "INSTALLED" || s.availability === "RUNNING"
    || s.availability === "WEB") ?? [];
  const quick = QUICK.map((key) => software?.find((s) => s.key === key)).filter((s): s is Software => !!s);

  const progress = (project: Project) => {
    const tasks = data?.tasks.filter((t) => t.projectId === project.id) ?? [];
    const done = tasks.filter((t) => t.status === "DONE").length;
    return { total: tasks.length, pct: tasks.length ? Math.round((done / tasks.length) * 100) : 0 };
  };

  return (
    <div className="stack">
      <div className="card hero">
        <span className="brand-mark" style={{ width: 52, height: 52 }}><Icon name="sparkle" size={26} /></span>
        <div>
          <h1>{greeting()}, {displayName()}</h1>
          <p className="muted" style={{ margin: "4px 0 0" }}>La tua seconda mente operativa è online.</p>
        </div>
        <span className="spacer" />
        <button className="btn btn-primary" onClick={() => navigate("projects")}><Icon name="plus" size={14} /> Nuovo progetto</button>
      </div>

      <ProblemNote problem={problem} />
      {engineDown && (
        <div className="notice notice-warn">
          <strong>L'AI Engine non risponde.</strong> Le run falliranno come <code>engine-unreachable</code> finché non
          è avviato: <code>.\scripts\start-dev.ps1</code>.
        </div>
      )}

      <div className="grid grid-4">
        <div className="card kpi"><span className="kpi-icon glow-blue"><Icon name="projects" /></span>
          <div><small>Progetti attivi</small><strong>{data ? active.length : "…"}</strong></div></div>
        <div className="card kpi"><span className="kpi-icon glow-green"><Icon name="tasks" /></span>
          <div><small>Task in corso</small><strong>{data ? inProgress.length : "…"}</strong></div></div>
        <div className="card kpi"><span className="kpi-icon glow-violet"><Icon name="agents" /></span>
          <div><small>Agenti attivi</small><strong>{data ? data.agents.filter((a) => a.active).length : "…"}</strong></div></div>
        <div className="card kpi"><span className="kpi-icon glow-pink"><Icon name="software" /></span>
          <div><small>Software disponibili</small><strong>{software ? usable.length : "…"}</strong></div></div>
        <div className="card kpi"><span className="kpi-icon glow-amber"><Icon name="models" /></span>
          <div><small>Modelli pronti</small>
            <strong>{models ? models.models.filter((m) => m.available).length : engineDown ? "—" : "…"}</strong></div></div>
      </div>

      <div className="grid grid-2">
        <div className="card">
          <div className="card-head"><h2>Progetti recenti</h2><span className="spacer" />
            <button className="btn btn-small" onClick={() => navigate("projects")}>Tutti</button></div>
          {active.length === 0 && <div className="list-item muted">Nessun progetto attivo.</div>}
          {active.slice(0, 6).map((project) => {
            const p = progress(project);
            return (
              <div key={project.id} className="list-item">
                <Icon name="projects" />
                <div style={{ flex: 1 }}>
                  <strong>{project.name}</strong>
                  <div className="muted" style={{ fontSize: 12 }}>{p.total} task</div>
                </div>
                <div style={{ width: 120 }}><div className="progress"><span style={{ width: `${p.pct}%` }} /></div></div>
                <span className="muted" style={{ width: 36, textAlign: "right" }}>{p.pct}%</span>
              </div>
            );
          })}
        </div>
        <div className="card">
          <div className="card-head"><h2>Task da seguire</h2><span className="spacer" />
            <button className="btn btn-small" onClick={() => navigate("tasks")}>Board</button></div>
          {[...inProgress, ...open].length === 0 && <div className="list-item muted">Niente in coda.</div>}
          {[...inProgress, ...open].slice(0, 7).map((task) => (
            <div key={task.id} className="list-item">
              <StatusBadge status={task.status} />
              <span style={{ flex: 1 }}>#{task.id} {task.title}</span>
              <PriorityBadge priority={task.priority} />
            </div>
          ))}
        </div>
      </div>

      <div className="card">
        <div className="card-head"><h2>Accesso rapido software</h2><span className="spacer" />
          <button className="btn btn-small" onClick={() => navigate("software")}>Software Hub</button></div>
        <div className="card-body quick">
          {quick.map((s) => (
            <button key={s.key} title={s.role} onClick={() => s.launchKind === "LOCAL_SERVICE" && s.embeddable
              ? navigate("integrations", s.key) : launch(s)}>
              <AppIcon software={s} size={44} />{s.name.replace(/ \(.*\)/, "")}
            </button>
          ))}
        </div>
        {outcome}
      </div>
    </div>
  );
}
