import { useCallback, useEffect, useState } from "react";
import { useApi, useDisplayName } from "../context";
import type { Agent, DailyItem, LearningOverview, ModelList, Project, Software, Task } from "../api/types";
import { PriorityBadge, ProblemNote, StatusBadge } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon, type IconName } from "../components/icons";
import { useLauncher } from "./SoftwareHub";
import { lessonRoute } from "./Learn";
import { Ribbon } from "../components/Ribbon";
import { AssistantOrb } from "../components/AssistantOrb";
import { AssistantChat } from "../components/AssistantChat";
import { ActivityFeed, AgentLoad, ProgressDonut, ProjectBars, useTaskMoves, WeekBars } from "./dashboard/Widgets";

const QUICK = ["claude-code", "codex", "openclaw", "antigravity-ide", "vscode-continue", "intellij-junie", "chatgpt-classic",
  "postman", "mysql-workbench", "open-webui", "omniverse-3d"];

function greeting(): string {
  const hour = new Date().getHours();
  return hour < 13 ? "Buongiorno" : hour < 18 ? "Buon pomeriggio" : "Buonasera";
}

export function Dashboard({ navigate }: { navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const name = useDisplayName();
  const [data, setData] = useState<{ tasks: Task[]; agents: Agent[]; projects: Project[] } | null>(null);
  const [software, setSoftware] = useState<Software[] | null>(null);
  const [models, setModels] = useState<ModelList | null>(null);
  const [engineDown, setEngineDown] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [today, setToday] = useState<DailyItem[]>([]);
  // PHASE 37 (ADR-044): what there is to learn today; the page does without it if it cannot be read.
  const [learning, setLearning] = useState<LearningOverview | null>(null);
  const { launch, outcome } = useLauncher();
  const [chatOpen, setChatOpen] = useState(false);
  const [thinking, setThinking] = useState(false);
  const closeChat = useCallback(() => setChatOpen(false), []);
  const moves = useTaskMoves(data?.tasks ?? null);

  useEffect(() => {
    Promise.all([api.tasks(), api.agents(), api.projects()])
      .then(([tasks, agents, projects]) => setData({ tasks, agents, projects }))
      .catch(setProblem);
    api.software().then(setSoftware).catch(() => setSoftware([]));
    api.engineModels().then(setModels).catch(() => setEngineDown(true));
    const d = new Date();
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    api.daily(key, key).then(setToday).catch(() => setToday([]));
    api.learningOverview().then(setLearning).catch(() => setLearning(null));
  }, [api]);

  const active = data?.projects.filter((p) => p.status === "ACTIVE") ?? [];
  const inProgress = data?.tasks.filter((t) => t.status === "IN_PROGRESS") ?? [];
  const open = data?.tasks.filter((t) => t.status === "OPEN") ?? [];
  const usable = software?.filter((s) => s.availability === "INSTALLED" || s.availability === "RUNNING"
    || s.availability === "WEB") ?? [];
  const quick = QUICK.map((key) => software?.find((s) => s.key === key)).filter((s): s is Software => !!s);

  // What to do now, in the order that keeps things moving: what is due, what is half done, what is waiting.
  const next: { icon: IconName; text: string; action: string; go: () => void }[] = [];
  if (learning && learning.cardsDue > 0) {
    next.push({ icon: "cards", action: "Ripassa", go: () => navigate("learn", "review"),
      text: learning.cardsDue === 1 ? "Una carta da ripassare: due minuti, e resta." : `${learning.cardsDue} carte da ripassare: pochi minuti, e restano.` });
  }
  const lesson = learning?.projects.flatMap((p) => p.lessons.filter((l) => !l.completed).map((l) => ({ p, l })))[0];
  if (lesson) {
    next.push({ icon: "learn", action: lesson.l.done > 0 ? "Continua" : "Inizia",
      go: () => navigate("learn", lessonRoute(lesson.p.projectId, lesson.l.path)),
      text: `Lezione «${lesson.l.title}» — ${lesson.l.done} di ${lesson.l.items} fatti` });
  }
  if (inProgress[0]) {
    next.push({ icon: "tasks", action: "Apri la board", go: () => navigate("tasks"), text: `In corso: #${inProgress[0].id} ${inProgress[0].title}` });
  } else if (open[0]) {
    next.push({ icon: "tasks", action: "Apri la board", go: () => navigate("tasks"), text: `Da cominciare: #${open[0].id} ${open[0].title}` });
  }
  if (data && active.length === 0) {
    next.push({ icon: "projects", action: "Nuovo progetto", go: () => navigate("projects"), text: "Nessun progetto attivo: tutto parte da un progetto e dal suo master prompt." });
  }


  const metrics: { label: string; icon: IconName; value: string; page: string }[] = [
    { label: "Progetti attivi", icon: "projects", value: data ? String(active.length) : "…", page: "projects" },
    { label: "Task in corso", icon: "tasks", value: data ? String(inProgress.length) : "…", page: "tasks" },
    { label: "Agenti abilitati", icon: "agents", value: data ? String(data.agents.filter((a) => a.active).length) : "…", page: "agents" },
    { label: "Software pronti", icon: "software", value: software ? String(usable.length) : "…", page: "software" },
    { label: "Modelli disponibili", icon: "models",
      value: models ? String(models.models.filter((m) => m.available).length) : engineDown ? "—" : "…", page: "models" },
  ];
  const status = !data ? "Carico lo stato dei tuoi progetti…"
    : inProgress.length + open.length === 0 ? "Nessuna task in attesa: è il momento giusto per avviare qualcosa di nuovo."
    : "Ecco il resoconto del tuo lavoro.";
  const chips: { icon: IconName; text: string; page: string }[] = data ? [
    { icon: "projects", text: `${active.length} ${active.length === 1 ? "progetto attivo" : "progetti attivi"}`, page: "projects" },
    { icon: "tasks", text: `${inProgress.length} in corso · ${open.length} da fare`, page: "tasks" },
    { icon: "agents", text: `${data.agents.filter((a) => a.active).length} agenti`, page: "agents" },
  ] : [];
  const suggestions = [
    "Cosa c'è da fare adesso?",
    "Quali task sono in corso?",
    active[0] ? `Come sta andando ${active[0].name}?` : "Come creo il primo progetto?",
  ];

  return (
    <div className="stack" style={{ gap: 18 }}>
      <section className="card welcome" aria-label="Benvenuto">
        <Ribbon animate />
        <div className="welcome-text">
          <h1>{greeting()}, {name}</h1>
          <p>{status}</p>
          {chips.length > 0 && (
            <div className="welcome-chips">
              {chips.map((c) => (
                <button key={c.text} className="chip-button" onClick={() => navigate(c.page)}><Icon name={c.icon} size={14} />{c.text}</button>
              ))}
            </div>
          )}
          <div className="row">
            <button className="btn btn-primary" onClick={() => navigate("projects")}><Icon name="plus" size={14} /> Nuovo progetto</button>
            <button className="btn" onClick={() => navigate("tasks")}>Apri le task</button>
          </div>
        </div>
        <div className="welcome-orb">
          <button className="orb-button" onClick={() => setChatOpen((o) => !o)} aria-expanded={chatOpen}
                  aria-label={chatOpen ? "Chiudi l'assistente" : "Apri l'assistente"}>
            <AssistantOrb energy={thinking ? 1 : chatOpen ? 0.35 : 0} />
          </button>
          <span className="orb-caption">{thinking ? "Sto pensando…" : chatOpen ? "Ti ascolto" : "Chiedimi qualcosa"}</span>
          {chatOpen && <AssistantChat onClose={closeChat} onThinking={setThinking} suggestions={suggestions} />}
        </div>
      </section>

      <div className="widget-row">
        <ProgressDonut tasks={data?.tasks ?? null} />
        <WeekBars moves={moves} />
        <ProjectBars projects={active} tasks={data?.tasks ?? null} navigate={navigate} />
      </div>

      <ProblemNote problem={problem} />
      {engineDown && (
        <div className="notice notice-warn">
          <strong>Il motore AI locale non risponde.</strong> Le esecuzioni non partono finché non lo avvii, ad esempio con{" "}
          <code>.\scripts\start-dev.ps1</code>.
        </div>
      )}

      <nav className="card metrics" aria-label="In sintesi">
        {metrics.map((m) => (
          <button key={m.label} className="metric" onClick={() => navigate(m.page)}>
            <span><Icon name={m.icon} size={15} />{m.label}</span>
            <strong>{m.value}</strong>
          </button>
        ))}
      </nav>

      {next.length > 0 && (
        <section className="card" aria-label="Adesso">
          <div className="card-head"><h2>Adesso</h2><span className="spacer" />
            {learning && learning.streakDays > 0 && (
              <span className="muted" style={{ fontSize: 12.5 }}>{learning.streakDays} {learning.streakDays === 1 ? "giorno" : "giorni"} di fila che impari</span>
            )}</div>
          {next.slice(0, 3).map((step) => (
            <div key={step.text} className="list-item">
              <Icon name={step.icon} size={16} />
              <span style={{ flex: 1, minWidth: 0 }}>{step.text}</span>
              <button className="btn btn-small" onClick={step.go}>{step.action}</button>
            </div>
          ))}
        </section>
      )}

      <div className="dash-grid">
        <div className="stack" style={{ gap: 18 }}>
        <section className="card" aria-label="Task da seguire">
          <div className="card-head"><h2>Task da seguire</h2>
            <span className="muted" style={{ fontSize: 12.5 }}>prima quelle in corso, poi quelle da fare</span><span className="spacer" />
            <button className="btn btn-small btn-ghost" onClick={() => navigate("tasks")}>Apri la board</button></div>
          {[...inProgress, ...open].length === 0 && <div className="empty-line">Nessuna task in coda.</div>}
          {[...inProgress, ...open].slice(0, 7).map((task) => (
            <div key={task.id} className="list-item">
              <span style={{ flex: 1, minWidth: 0 }}>#{task.id} {task.title}</span>
              {task.status === "IN_PROGRESS" && <StatusBadge status={task.status} />}
              <PriorityBadge priority={task.priority} />
            </div>
          ))}
        </section>
        <section className="card" aria-label="Avvio rapido">
          <div className="card-head"><h2>Avvio rapido</h2><span className="spacer" />
            <button className="btn btn-small btn-ghost" onClick={() => navigate("software")}>Tutti i software</button></div>
          {software && quick.length === 0 && <div className="empty-line">Nessuno strumento rilevato su questo computer.</div>}
          <div className="card-body quick">
            {quick.map((s) => (
              <button key={s.key} title={`${s.name} — ${s.role}`} onClick={() => s.launchKind === "LOCAL_SERVICE" && s.embeddable
                ? navigate("integrations", s.key) : launch(s)}>
                <AppIcon software={s} size={40} /><span className="quick-label">{s.name.replace(/ \(.*\)/, "")}</span>
              </button>
            ))}
          </div>
          {outcome}
        </section>
        </div>
        <div className="stack" style={{ gap: 18 }}>
        <ActivityFeed moves={moves} tasks={data?.tasks ?? null} />
        <AgentLoad tasks={data?.tasks ?? null} agents={data?.agents ?? []} navigate={navigate} />
        <section className="card" aria-label="Agenda di oggi">
          <div className="card-head"><h2>Oggi</h2>
            {today.length > 0 && <span className="muted">{today.filter((i) => i.status === "DONE").length} di {today.length} fatti</span>}
            <span className="spacer" /><button className="btn btn-small btn-ghost" onClick={() => navigate("daily")}>Apri l'agenda</button></div>
          {today.length === 0 && <div className="empty-line">Niente in agenda per oggi.</div>}
          {today.map((item) => (
            <div key={item.id} className="list-item" style={{ opacity: item.status === "DONE" ? 0.55 : 1 }}>
              <Icon name={item.status === "DONE" ? "check" : "clock"} size={15} />
              <span style={{ flex: 1 }}>{item.title}</span>
              {item.task && <span className="chip">{item.task.projectName ?? item.task.code}</span>}
            </div>
          ))}
        </section>
        </div>
      </div>
    </div>
  );
}
