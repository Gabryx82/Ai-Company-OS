import { useEffect, useMemo, useState, type ReactNode } from "react";
import type { Agent, Project, Task, TaskMove } from "../../api/types";
import { useApi } from "../../context";
import { Icon, type IconName } from "../../components/icons";

/**
 * The dashboard's widgets. Every figure is counted from what the control plane holds:
 * task states, the history of task moves, projects and agents. Nothing is estimated.
 */

const MAX_HISTORIES = 80;

/** The moves of the tasks (started, completed, stopped, reopened), newest first. */
export function useTaskMoves(tasks: Task[] | null): TaskMove[] | null {
  const api = useApi();
  const [moves, setMoves] = useState<TaskMove[] | null>(null);
  const ids = useMemo(() => (tasks ?? []).slice(0, MAX_HISTORIES).map((t) => t.id).join(","), [tasks]);
  useEffect(() => {
    if (!tasks) return;
    let cancelled = false;
    Promise.all(tasks.slice(0, MAX_HISTORIES).map((t) => api.taskTransitions(t.id).catch(() => [] as TaskMove[])))
      .then((all) => { if (!cancelled) setMoves(all.flat().sort((a, b) => b.at.localeCompare(a.at))); });
    return () => { cancelled = true; };
  }, [api, ids]); // eslint-disable-line react-hooks/exhaustive-deps
  return moves;
}

function WidgetHead({ icon, title, hint, action }: { icon: IconName; title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="widget-head">
      <span className="widget-icon"><Icon name={icon} size={16} /></span>
      <div><h2>{title}</h2>{hint && <small>{hint}</small>}</div>
      <span className="spacer" />
      {action}
    </div>
  );
}

/** How far the work is: the share of tasks in each state, and the completed share in the middle. */
export function ProgressDonut({ tasks }: { tasks: Task[] | null }) {
  const all = tasks ?? [];
  const parts = [
    { key: "DONE", label: "Completate", tone: "var(--accent)" },
    { key: "IN_PROGRESS", label: "In corso", tone: "var(--ok)" },
    { key: "OPEN", label: "Da fare", tone: "var(--border-strong)" },
  ].map((p) => ({ ...p, count: all.filter((t) => t.status === p.key).length }));
  const total = all.length;
  const done = parts[0].count;
  const pct = total ? Math.round((done / total) * 100) : 0;
  const R = 46, C = 2 * Math.PI * R;
  let offset = 0;
  return (
    <section className="card widget" aria-label="Avanzamento globale">
      <WidgetHead icon="tasks" title="Avanzamento globale" hint="Tutte le task, per stato" />
      <div className="donut-row">
        <svg className="donut" viewBox="0 0 120 120" role="img" aria-label={`${pct}% delle task completate`}>
          <circle cx="60" cy="60" r={R} className="donut-track" />
          {total > 0 && parts.map((p) => {
            const len = (p.count / total) * C;
            const el = p.count > 0 && (
              <circle key={p.key} cx="60" cy="60" r={R} className="donut-arc" style={{ stroke: p.tone }}
                      strokeDasharray={`${Math.max(len - 2, 0.01)} ${C}`} strokeDashoffset={-offset} />
            );
            offset += len;
            return el;
          })}
          <text x="60" y="58" className="donut-value">{tasks ? `${pct}%` : "…"}</text>
          <text x="60" y="75" className="donut-label">completate</text>
        </svg>
        <ul className="legend">
          {parts.map((p) => (
            <li key={p.key}><span className="dot" style={{ background: p.tone }} />{p.label}
              <span className="spacer" /><strong>{p.count}</strong>
              <span className="muted">{total ? `${Math.round((p.count / total) * 100)}%` : "—"}</span></li>
          ))}
        </ul>
      </div>
    </section>
  );
}

const DAY = new Intl.DateTimeFormat("it-IT", { weekday: "short" });
const dayKey = (d: Date) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;

/** Tasks completed on each of the last seven days, from the history of their moves. */
export function WeekBars({ moves }: { moves: TaskMove[] | null }) {
  const days = useMemo(() => {
    const today = new Date();
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (6 - i));
      const label = DAY.format(d).replace(".", "");
      return { key: dayKey(d), label: label.charAt(0).toUpperCase() + label.slice(1), count: 0, today: i === 6 };
    });
  }, []);
  const counted = days.map((d) => ({ ...d, count: (moves ?? []).filter((m) => m.transition === "COMPLETE"
    && dayKey(new Date(m.at)) === d.key).length }));
  const max = Math.max(1, ...counted.map((d) => d.count));
  const week = counted.reduce((n, d) => n + d.count, 0);
  return (
    <section className="card widget" aria-label="Produttività">
      <WidgetHead icon="usage" title="Produttività" hint="Task completate negli ultimi 7 giorni"
                  action={<strong className="widget-figure">{moves ? week : "…"}</strong>} />
      <div className="bars" role="img" aria-label={counted.map((d) => `${d.label}: ${d.count}`).join(", ")}>
        {counted.map((d) => (
          <div key={d.key} className={`bar${d.today ? " is-today" : ""}`}>
            <span className="bar-value">{d.count > 0 ? d.count : ""}</span>
            <span className="bar-fill" style={{ height: `${Math.max((d.count / max) * 100, d.count ? 8 : 2)}%` }} />
            <span className="bar-label">{d.label}</span>
          </div>
        ))}
      </div>
      {moves && week === 0 && <p className="widget-note">Nessuna task completata in questi giorni.</p>}
    </section>
  );
}

/** Each active project with how much of it is done. */
export function ProjectBars({ projects, tasks, navigate }: {
  projects: Project[]; tasks: Task[] | null; navigate: (page: string, detail?: string) => void;
}) {
  return (
    <section className="card widget" aria-label="Progetti attivi">
      <WidgetHead icon="projects" title="Progetti attivi" hint="Avanzamento per progetto"
                  action={<button className="btn btn-small btn-ghost" onClick={() => navigate("projects")}>Vedi tutti</button>} />
      {projects.length === 0 && <p className="widget-note">Nessun progetto attivo. Creane uno per far partire il piano.</p>}
      <ul className="project-bars">
        {projects.slice(0, 5).map((p) => {
          const own = (tasks ?? []).filter((t) => t.projectId === p.id);
          const pct = own.length ? Math.round((own.filter((t) => t.status === "DONE").length / own.length) * 100) : 0;
          return (
            <li key={p.id}>
              <button className="project-bar" onClick={() => navigate("projects", String(p.id))}>
                <span className="project-bar-name">{p.name}</span><span className="muted">{pct}%</span>
                <span className="progress"><span style={{ width: `${pct}%` }} /></span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

const MOVE_TEXT: Record<TaskMove["transition"], string> = {
  START: "avviata", COMPLETE: "completata", STOP: "rimessa in coda", REOPEN: "riaperta",
};
const VIA_TEXT: Record<TaskMove["via"], string> = {
  API: "", RUN: " dal motore AI", HANDOFF: " con un handoff", REVIEW: " in revisione",
};

export function ago(at: string, now = Date.now()): string {
  const s = Math.max(0, Math.round((now - new Date(at).getTime()) / 1000));
  if (s < 60) return "adesso";
  if (s < 3600) return `${Math.round(s / 60)} min fa`;
  if (s < 86400) return `${Math.round(s / 3600)} h fa`;
  const d = Math.round(s / 86400);
  return d === 1 ? "ieri" : `${d} giorni fa`;
}

/** The latest moves of the tasks, as a short timeline. */
export function ActivityFeed({ moves, tasks, limit = 6, action }: {
  moves: TaskMove[] | null; tasks: Task[] | null; limit?: number; action?: ReactNode;
}) {
  const title = (id: number) => tasks?.find((t) => t.id === id)?.title;
  return (
    <section className="card widget" aria-label="Attività recenti">
      <WidgetHead icon="clock" title="Attività recenti" action={action} />
      {moves && moves.length === 0 && <p className="widget-note">Ancora nessuna attività registrata.</p>}
      {!moves && <p className="widget-note">Carico lo storico…</p>}
      <ol className="timeline">
        {(moves ?? []).slice(0, limit).map((m) => (
          <li key={m.id} className={`is-${m.transition.toLowerCase()}`}>
            <span className="timeline-when">{ago(m.at)}</span>
            <span className="timeline-what">
              <strong>Task #{m.taskId} {MOVE_TEXT[m.transition]}</strong>{VIA_TEXT[m.via]}
              {title(m.taskId) && <small>{title(m.taskId)}</small>}
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}

/** How many unfinished tasks each agent holds: who is loaded, who is free. */
export function AgentLoad({ tasks, agents, navigate }: {
  tasks: Task[] | null; agents: Agent[]; navigate: (page: string, detail?: string) => void;
}) {
  const rows = agents.map((a) => {
    const own = (tasks ?? []).filter((t) => t.agentId === a.id && t.status !== "DONE");
    return { agent: a, open: own.length, running: own.filter((t) => t.status === "IN_PROGRESS").length };
  }).filter((r) => r.open > 0).sort((a, b) => b.open - a.open).slice(0, 6);
  const max = Math.max(1, ...rows.map((r) => r.open));
  return (
    <section className="card widget" aria-label="Carico degli agenti">
      <WidgetHead icon="agents" title="Carico degli agenti" hint="Task assegnate non ancora completate"
                  action={<button className="btn btn-small btn-ghost" onClick={() => navigate("office")}>Ufficio 3D</button>} />
      {tasks && rows.length === 0 && <p className="widget-note">Nessuna task assegnata in attesa.</p>}
      <ul className="load-bars">
        {rows.map((r) => (
          <li key={r.agent.id}>
            <span className="load-name">{r.agent.name}</span>
            <span className="load-track"><span style={{ width: `${(r.open / max) * 100}%` }} />
              {r.running > 0 && <i style={{ width: `${(r.running / max) * 100}%` }} />}</span>
            <span className="load-count">{r.open}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
