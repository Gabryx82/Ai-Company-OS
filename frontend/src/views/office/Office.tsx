import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { Agent, Project, Task } from "../../api/types";
import { useApi } from "../../context";
import { Icon } from "../../components/icons";
import { ProblemNote } from "../../components/ui";
import { GraphKeys, GraphNav, GraphTrail } from "../../components/GraphNav";
import { TaskDrawer } from "../TaskDrawer";
import { webglAvailable } from "../ecosystem3d/webgl";
import { OfficeJournal, latestSteps, useJournal } from "./AgentJournal";
import { AgentLive } from "./AgentLive";
import type { DeskAgent, DeskState } from "./OfficeScene";
import type { OfficeCameraAsk, OfficeCameraRequest } from "./officeCamera";

// three.js loads only when the office opens, as for the 3D ecosystem (ADR-037).
const OfficeScene = lazy(() => import("./OfficeScene"));

const ORDER: Record<DeskState, number> = { working: 0, waiting: 1, idle: 2, off: 3 };
/** The colour of each state, as on the name tags. */
const STATE_COLOR: Record<DeskState, string> = { working: "#5fb0ff", waiting: "#f2b84b", idle: "#8d96a9", off: "#3a4256" };

/** The keys the office answers to while it has the focus. */
const KEYS: [string, string][] = [
  ["← → ↑ ↓", "Ruota attorno al centro"],
  ["Maiusc + frecce", "Spostati sul pavimento"],
  ["Due dita ← →", "Sposta a destra e a sinistra (touchpad; col mouse Maiusc + rotella)"],
  ["Due dita sullo schermo", "Trascina la vista e ingrandisci"],
  ["+  −", "Avvicina, allontana"],
  ["F  ·  Home", "Inquadra tutto l'ufficio"],
  ["1  2  3", "Vista panoramica, dall'alto, laterale"],
  ["N  ·  Maiusc + N", "Agente successivo, precedente"],
  ["Invio", "Vai all'agente scelto"],
  ["Backspace", "Torna all'agente precedente"],
  ["R", "Rotazione automatica"],
  ["Esc", "Chiudi questo pannello"],
];
const STEP = Math.PI / 12;

const reducedMotion = () => typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

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
  const [camera, setCamera] = useState<OfficeCameraRequest | null>(null);
  const [autoRotate, setAutoRotate] = useState(false);
  const [showKeys, setShowKeys] = useState(false);
  const animate = useMemo(() => !reducedMotion(), []);
  const cameraSeq = useRef(0);
  const ask = useCallback((request: OfficeCameraAsk) => setCamera({ ...request, seq: ++cameraSeq.current }), []);
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
  // PHASE 37 (ADR-047): what each agent did, step by step -- its latest moment under its name in the office.
  const { entries: journal, problem: journalProblem } = useJournal(api);
  const steps = useMemo(() => latestSteps(journal), [journal]);
  const agentJournal = useMemo(() => (journal ?? []).filter((e) => e.agentId === selected), [journal, selected]);
  const sceneAgents: DeskAgent[] = useMemo(() => desks.map((d) => ({ ...d, step: steps.get(d.id) ?? null })), [desks, steps]);

  // The first agent at work is shown at once, in the whole office; the operator's choice wins after that.
  const framed = useRef(false);
  useEffect(() => {
    if (selected === null && desks.length) setSelected((working[0] ?? waiting[0] ?? desks[0]).id);
    if (!framed.current && desks.length) { framed.current = true; ask({ kind: "fit", view: "front", instant: true }); }
  }, [desks.length]); // eslint-disable-line react-hooks/exhaustive-deps

  /** Chooses an agent and takes the camera to its desk. */
  const goTo = useCallback((id: number) => { setSelected(id); ask({ kind: "focus", id }); }, [ask]);
  const step = (by: number) => {
    if (!desks.length) return;
    const at = desks.findIndex((d) => d.id === selected);
    const from = at < 0 ? (by > 0 ? -1 : 0) : at;
    goTo(desks[(from + by + desks.length) % desks.length].id);
  };

  // The agents chosen so far, for going back.
  const [trail, setTrail] = useState<number[]>([]);
  const goingBack = useRef(false);
  useEffect(() => {
    if (selected === null) return;
    if (goingBack.current) { goingBack.current = false; return; }
    setTrail((t) => (t[t.length - 1] === selected ? t : [...t.filter((id) => id !== selected), selected].slice(-30)));
  }, [selected]);
  const known = useMemo(() => trail.filter((id) => desks.some((d) => d.id === id)), [trail, desks]);
  const canBack = known.length >= 2 || (known.length === 1 && selected !== known[0]);
  const back = () => {
    if (!canBack) return;
    const top = known[known.length - 1];
    const rest = selected === top ? known.slice(0, -1) : known;
    setTrail(rest);
    goingBack.current = true;
    goTo(rest[rest.length - 1]);
  };

  const onStageKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if ((event.target as HTMLElement).closest("button, input, select, textarea, a")) return;
    const shift = event.shiftKey;
    const done = () => { event.preventDefault(); event.stopPropagation(); };
    switch (event.key) {
      case "ArrowLeft": done(); ask(shift ? { kind: "pan", x: -0.08, y: 0 } : { kind: "orbit", azimuth: -STEP, polar: 0 }); return;
      case "ArrowRight": done(); ask(shift ? { kind: "pan", x: 0.08, y: 0 } : { kind: "orbit", azimuth: STEP, polar: 0 }); return;
      case "ArrowUp": done(); ask(shift ? { kind: "pan", x: 0, y: 0.08 } : { kind: "orbit", azimuth: 0, polar: -STEP * 0.7 }); return;
      case "ArrowDown": done(); ask(shift ? { kind: "pan", x: 0, y: -0.08 } : { kind: "orbit", azimuth: 0, polar: STEP * 0.7 }); return;
      case "+": case "=": done(); ask({ kind: "zoom", factor: 0.78 }); return;
      case "-": case "_": done(); ask({ kind: "zoom", factor: 1.28 }); return;
      case "f": case "F": case "Home": case "0": done(); ask({ kind: "fit" }); return;
      case "1": done(); ask({ kind: "fit", view: "front" }); return;
      case "2": done(); ask({ kind: "fit", view: "top" }); return;
      case "3": done(); ask({ kind: "fit", view: "side" }); return;
      case "n": case "N": done(); step(shift ? -1 : 1); return;
      case "r": case "R": done(); setAutoRotate((on) => !on); return;
      case "Enter": if (selected !== null) { done(); ask({ kind: "focus", id: selected }); } return;
      case "Backspace": done(); back(); return;
      case "Escape": if (showKeys) { done(); setShowKeys(false); } return;
      case "?": done(); setShowKeys((on) => !on); return;
    }
  };

  const agent = agents?.find((a) => a.id === selected) ?? null;
  const current = agent ? states.get(agent.id)! : null;
  const project = current?.task?.projectId ? projects.find((p) => p.id === current.task!.projectId) ?? null : null;
  const pct = enabled.length ? Math.round((working.length / enabled.length) * 100) : 0;

  return (
    <div className="stack office">
      <div className="page-head">
        <div><h1>Ufficio 3D</h1>
          <p>La tua squadra di agenti al lavoro, in tempo reale. Clicca un agente per vedere il codice che sta scrivendo o cosa sta facendo.</p></div>
      </div>
      <ProblemNote problem={problem} />

      <div className="office-grid">
        <div className="card office-stage graph-stage-focus" role="region" tabIndex={0} onKeyDown={onStageKey}
             aria-label={`Ufficio 3D: ${desks.length} agenti`}
             aria-keyshortcuts="ArrowLeft ArrowRight ArrowUp ArrowDown + - F 1 2 3 N Enter Backspace R ?">
          {!webgl && <p className="office-fallback">Questo browser non può disegnare in 3D (WebGL non disponibile). L'elenco a destra mostra gli stessi agenti.</p>}
          {webgl && agents && (
            <Suspense fallback={<p className="office-fallback">Preparo l'ufficio…</p>}>
              <OfficeScene agents={sceneAgents} selected={selected} camera={camera} autoRotate={autoRotate} animate={animate} dark={dark}
                           onSelect={setSelected} onActivate={goTo} onBackground={() => ask({ kind: "fit" })} />
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
          {webgl && agents && agents.length > 0 && (
            <>
              <GraphTrail canBack={canBack} onBack={back} steps={[
                { key: "office", label: "Ufficio", onClick: () => ask({ kind: "fit" }) },
                ...(agent && current ? [{ key: "agent", label: agent.name, color: STATE_COLOR[current.state], current: true,
                  onClick: () => ask({ kind: "focus", id: agent.id }) }] : []),
              ]} />
              <div className="office-hint">
                Trascina per ruotare · tasto destro per spostarti · rotella per lo zoom verso il puntatore · due dita a destra o a sinistra
                per scorrere · doppio clic su un agente per raggiungerlo, sul pavimento per inquadrare tutto · <kbd>?</kbd> per i tasti
              </div>
              <GraphNav label="Navigazione dell'ufficio 3D" groups={[
                [
                  { glyph: "plus", label: "Avvicina (+)", onClick: () => ask({ kind: "zoom", factor: 0.72 }) },
                  { glyph: "minus", label: "Allontana (−)", onClick: () => ask({ kind: "zoom", factor: 1.38 }) },
                  { glyph: "fit", label: "Inquadra tutto (F)", onClick: () => ask({ kind: "fit" }) },
                ],
                [
                  { glyph: "front", label: "Vista panoramica (1)", onClick: () => ask({ kind: "fit", view: "front" }) },
                  { glyph: "top", label: "Vista dall'alto (2)", onClick: () => ask({ kind: "fit", view: "top" }) },
                  { glyph: "side", label: "Vista laterale (3)", onClick: () => ask({ kind: "fit", view: "side" }) },
                ],
                [
                  { glyph: "rotate", label: "Rotazione automatica (R)", pressed: autoRotate, onClick: () => setAutoRotate(!autoRotate) },
                  { glyph: "keys", label: "Scorciatoie da tastiera (?)", pressed: showKeys, onClick: () => setShowKeys(!showKeys) },
                ],
              ]} />
              {showKeys && <GraphKeys keys={KEYS} onClose={() => setShowKeys(false)} />}
            </>
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
                  <button className={`roster-row${selected === d.id ? " is-on" : ""}`} onClick={() => goTo(d.id)} aria-pressed={selected === d.id}
                          title="Scegli l'agente e raggiungi la sua scrivania">
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
                   journal={agentJournal} onOpenTask={(t) => setOpenTask(t.id)} />
      )}
      <OfficeJournal entries={journal} agents={agents ?? []} problem={journalProblem} onAgent={goTo} />

      {openTask !== null && agents && (
        <TaskDrawer taskId={openTask} agents={agents} projects={projects} onClose={() => setOpenTask(null)} onChanged={load} />
      )}
    </div>
  );
}
