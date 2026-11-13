import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from "react";
import type { Agent, Project, Task } from "../../api/types";
import { useApi } from "../../context";
import { Icon } from "../../components/icons";
import { ProblemNote } from "../../components/ui";
import { TaskDrawer } from "../TaskDrawer";
import { webglAvailable } from "../ecosystem3d/webgl";
import { ActivityFeed, useTaskMoves } from "../dashboard/Widgets";
import { AgentLive } from "./AgentLive";
import type { DeskAgent, DeskState, OfficeView } from "./OfficeScene";

// three.js loads only when the office opens, as for the 3D ecosystem (ADR-037).
const OfficeScene = lazy(() => import("./OfficeScene"));

const ORDER: Record<DeskState, number> = { working: 0, waiting: 1, idle: 2, off: 3 };

/** What each agent is doing now, from the tasks the control plane holds. */
export function deskState(agent: Agent, tasks: Task[]): { state: DeskState; task: Task | null; next: Task[] } {
  const own = tasks.filter((t) => t.agentId === agent.id);
  const running = own.find((t) => t.status === "IN_PROGRESS") ?? null;
  const next = own.filter((t) => t.status === "OPEN");
  if (!agent.active) return { state: "off", task: running, next };
  if (running) return { state: "working", task: running, next };
  return { state: next.length ? "waiting" : "idle", task: null, next };
}

export function Office() {
  const api = useApi();
  const [agents, setAgents] = useState<Agent[] | null>(null);
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [view, setView] = useState<OfficeView>("overview");
  const [openTask, setOpenTask] = useState<number | null>(null);
  const [dark, setDark] = useState(() => document.documentElement.dataset.theme !== "light");
  const webgl = useMemo(() => webglAvailable(), []);

  const load = useCallback(() => {
    Promise.all([api.agents(), api.tasks(), api.projects()])
      .then(([a, t, p]) => { setAgents(a); setTasks(t); setProjects(p); setProblem(null); })
      .catch(setProblem);
  }, [api]);
  useEffect(() => {
    load();
    // The office follows the work: tasks and agents are read again every few seconds while it is in view.
    const timer = window.setInterval(() => { if (!document.hidden) load(); }, 8000);
    return () => window.clearInterval(timer);
  }, [load]);
  useEffect(() => {
    const observer = new MutationObserver(() => setDark(document.documentElement.dataset.theme !== "light"));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => observer.disconnect();
  }, []);

  const states = useMemo(() => {
    const map = new Map<number, ReturnType<typeof deskState>>();
    for (const a of agents ?? []) map.set(a.id, deskState(a, tasks ?? []));
    return map;
  }, [agents, tasks]);
  const ordered = useMemo(() => [...(agents ?? [])].sort((a, b) =>
    ORDER[states.get(a.id)!.state] - ORDER[states.get(b.id)!.state] || a.name.localeCompare(b.name)), [agents, states]);
  const desks: DeskAgent[] = useMemo(() => ordered.map((a) => ({
    id: a.id, name: a.name, role: a.role, state: states.get(a.id)!.state, task: states.get(a.id)!.task?.title ?? null,
  })), [ordered, states]);
  const working = desks.filter((d) => d.state === "working");
  const waiting = desks.filter((d) => d.state === "waiting");
  const enabled = desks.filter((d) => d.state !== "off");
  const moves = useTaskMoves(tasks);

  // The first agent at work is shown at once; the operator's choice wins after that.
  useEffect(() => {
    if (selected === null && desks.length) setSelected((working[0] ?? waiting[0] ?? desks[0]).id);
  }, [desks.length]); // eslint-disable-line react-hooks/exhaustive-deps

  const agent = agents?.find((a) => a.id === selected) ?? null;
  const current = agent ? states.get(agent.id)! : null;
  const project = current?.task?.projectId ? projects.find((p) => p.id === current.task!.projectId) ?? null : null;
  const pct = enabled.length ? Math.round((working.length / enabled.length) * 100) : 0;

  return (
    <div className="stack office">
      <div className="page-head">
        <div><h1>Ufficio 3D</h1>
          <p>La tua squadra di agenti al lavoro, in tempo reale. Clicca un agente per vedere il codice che sta scrivendo o cosa sta facendo.</p></div>
        <div className="tabs" role="tablist" aria-label="Inquadratura">
          <button className="tab" role="tab" aria-selected={view === "overview"} onClick={() => setView("overview")}>
            <Icon name="office" size={14} /> Panoramica</button>
          <button className="tab" role="tab" aria-selected={view === "top"} onClick={() => setView("top")}>
            <Icon name="dashboard" size={14} /> Dall'alto</button>
        </div>
      </div>
      <ProblemNote problem={problem} />

      <div className="office-grid">
        <div className="card office-stage">
          {!webgl && <p className="office-fallback">Questo browser non può disegnare in 3D (WebGL non disponibile). L'elenco a destra mostra gli stessi agenti.</p>}
          {webgl && agents && (
            <Suspense fallback={<p className="office-fallback">Preparo l'ufficio…</p>}>
              <OfficeScene agents={desks} selected={selected} onSelect={(id) => id !== null && setSelected(id)} view={view} dark={dark} />
            </Suspense>
          )}
          {!agents && !problem && <p className="office-fallback">Carico la squadra…</p>}
          {agents && agents.length === 0 && <p className="office-fallback">Nessun agente ancora: crealo nella pagina Agenti.</p>}
          {agents && agents.length > 0 && (
            <div className="office-legend" aria-hidden="true">
              <span><i className="is-working" />al lavoro</span><span><i className="is-waiting" />task da iniziare</span>
              <span><i className="is-idle" />in pausa</span><span><i className="is-off" />disattivato</span>
            </div>
          )}
        </div>

        <aside className="office-rail">
          <section className="card widget" aria-label="Stato dell'ufficio">
            <div className="widget-head"><span className="widget-icon"><Icon name="office" size={16} /></span>
              <div><h2>Stato dell'ufficio</h2><small>{working.length ? "Si lavora" : "Nessuno al lavoro adesso"}</small></div></div>
            <div className="office-gauge">
              <svg viewBox="0 0 120 120" className="donut" role="img" aria-label={`${pct}% degli agenti al lavoro`}>
                <circle cx="60" cy="60" r="46" className="donut-track" />
                {pct > 0 && <circle cx="60" cy="60" r="46" className="donut-arc" style={{ stroke: "var(--accent)" }}
                                    strokeDasharray={`${(pct / 100) * 2 * Math.PI * 46} ${2 * Math.PI * 46}`} />}
                <text x="60" y="58" className="donut-value">{pct}%</text>
                <text x="60" y="75" className="donut-label">al lavoro</text>
              </svg>
              <dl>
                <div><dt>Al lavoro</dt><dd>{working.length}</dd></div>
                <div><dt>Con task da iniziare</dt><dd>{waiting.length}</dd></div>
                <div><dt>Agenti abilitati</dt><dd>{enabled.length}</dd></div>
              </dl>
            </div>
          </section>
          <section className="card widget office-roster" aria-label="La squadra">
            <div className="widget-head"><span className="widget-icon"><Icon name="agents" size={16} /></span>
              <div><h2>La squadra</h2><small>Prima chi lavora</small></div></div>
            <ul>
              {desks.map((d) => (
                <li key={d.id}>
                  <button className={`roster-row${selected === d.id ? " is-on" : ""}`} onClick={() => setSelected(d.id)} aria-pressed={selected === d.id}>
                    <span className={`roster-dot is-${d.state}`} />
                    <span className="roster-name">{d.name}<small>{d.task ?? (d.state === "waiting"
                      ? `${states.get(d.id)!.next.length} task da iniziare` : d.state === "off" ? "disattivato" : "in pausa")}</small></span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        </aside>
      </div>

      {agent && current && (
        <AgentLive agent={agent} state={current.state} task={current.task} project={project} next={current.next}
                   onOpenTask={(t) => setOpenTask(t.id)} />
      )}
      <ActivityFeed moves={moves} tasks={tasks} limit={8} />

      {openTask !== null && agents && (
        <TaskDrawer taskId={openTask} agents={agents} projects={projects} onClose={() => setOpenTask(null)} onChanged={load} />
      )}
    </div>
  );
}
