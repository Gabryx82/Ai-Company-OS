import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi } from "../context";
import type { DailyItem, Task } from "../api/types";
import { ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const addDays = (d: Date, n: number) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const STATUS_NEXT: Record<string, "TODO" | "DOING" | "DONE"> = { TODO: "DOING", DOING: "DONE", DONE: "TODO" };
const STATUS_LABEL: Record<string, string> = { TODO: "Da fare", DOING: "In corso", DONE: "Fatto" };
const PRIORITY_TONE: Record<string, string> = { HIGH: "badge badge-danger", MEDIUM: "badge badge-warn", LOW: "badge" };

/**
 * Daily Work (directive §18): a small personal ClickUp above the projects.
 * Personal items and references to project tasks side by side -- a reference
 * shows the task's own title and status, it never copies them.
 */
export function DailyWork() {
  const api = useApi();
  const today = new Date();
  const [start, setStart] = useState(today);
  const [items, setItems] = useState<DailyItem[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i));

  const load = useCallback(() => {
    api.daily(iso(days[0]), iso(days[6])).then(setItems).catch(setProblem);
  }, [api, start]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(load, [load]);
  useEffect(() => { api.tasks().then((t) => setTasks(t.filter((x) => x.status !== "DONE"))).catch(() => undefined); }, [api]);

  const guard = (action: Promise<unknown>) => action.then(load).catch(setProblem);

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Daily Work</h1><p>Cosa fare oggi e nei prossimi giorni: attività personali e task dei progetti, insieme.</p></div>
        <div className="row">
          <button className="btn btn-small" onClick={() => setStart(addDays(start, -7))}>←</button>
          <button className="btn btn-small" onClick={() => setStart(new Date())}>Oggi</button>
          <button className="btn btn-small" onClick={() => setStart(addDays(start, 7))}>→</button>
        </div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))" }}>
        {days.map((day, index) => {
          const key = iso(day);
          const own = items.filter((i) => i.day === key);
          const isToday = key === iso(today);
          return (
            <div key={key} className="card" style={isToday ? { borderColor: "var(--accent)" } : undefined}>
              <div className="card-head">
                <h2>{isToday ? "Oggi" : key === iso(addDays(today, 1)) ? "Domani"
                  : day.toLocaleDateString("it-IT", { weekday: "long", day: "numeric", month: "short" })}</h2>
                <span className="muted">{own.filter((i) => i.status === "DONE").length}/{own.length}</span>
                <span className="spacer" />
                {index === 0 && own.some((i) => i.status !== "DONE") && (
                  <button className="btn btn-small" title="Sposta a domani ciò che non è fatto"
                          onClick={() => guard(api.carryOver(key, iso(addDays(day, 1))))}>→ domani</button>
                )}
              </div>
              {own.map((item) => (
                <div key={item.id} className="list-item" style={{ opacity: item.status === "DONE" ? 0.55 : 1 }}>
                  <button className="btn btn-small" title="Cambia stato" onClick={() => guard(api.updateDaily(item, { status: STATUS_NEXT[item.status] }))}>
                    {item.status === "DONE" ? <Icon name="check" size={12} /> : STATUS_LABEL[item.status]}</button>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ textDecoration: item.status === "DONE" ? "line-through" : "none" }}>{item.title}</div>
                    {item.task && <div className="muted" style={{ fontSize: 11.5 }}>
                      {item.task.code ?? `#${item.task.id}`} · {item.task.projectName ?? "senza progetto"} · task {item.task.status}</div>}
                  </div>
                  <span className={PRIORITY_TONE[item.priority]}>{item.priority.toLowerCase()}</span>
                  <button className="btn btn-small" aria-label="Sposta a domani"
                          onClick={() => guard(api.updateDaily(item, { day: iso(addDays(new Date(item.day), 1)) }))}>›</button>
                  <button className="btn btn-small" aria-label="Rimuovi" onClick={() => guard(api.removeDaily(item))}>
                    <Icon name="close" size={11} /></button>
                </div>
              ))}
              <AddItem day={key} tasks={tasks} onAdd={(body) => guard(api.createDaily(body))} />
            </div>
          );
        })}
      </div>
    </div>
  );
}

function AddItem({ day, tasks, onAdd }: { day: string; tasks: Task[]; onAdd: (body: Record<string, unknown>) => void }) {
  const [title, setTitle] = useState("");
  const [taskId, setTaskId] = useState("");
  const [priority, setPriority] = useState("MEDIUM");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!title.trim() && !taskId) return;
    onAdd({ day, title: taskId ? null : title, taskId: taskId ? Number(taskId) : null, priority });
    setTitle(""); setTaskId("");
  }

  return (
    <form className="list-item" onSubmit={submit} style={{ flexWrap: "wrap" }}>
      <input placeholder="Nuova attività…" value={title} onChange={(e) => setTitle(e.target.value)} disabled={!!taskId}
             style={{ flex: "1 1 140px" }} />
      <select value={taskId} onChange={(e) => setTaskId(e.target.value)} style={{ flex: "1 1 120px" }} aria-label="Task di progetto">
        <option value="">o una task…</option>
        {tasks.map((t) => <option key={t.id} value={t.id}>{t.code ?? `#${t.id}`} {t.title}</option>)}
      </select>
      <select value={priority} onChange={(e) => setPriority(e.target.value)} style={{ width: 90 }} aria-label="Priorità">
        <option value="HIGH">alta</option><option value="MEDIUM">media</option><option value="LOW">bassa</option>
      </select>
      <button className="btn btn-small btn-primary" type="submit"><Icon name="plus" size={12} /></button>
    </form>
  );
}
