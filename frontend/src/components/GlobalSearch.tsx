import { useEffect, useRef, useState } from "react";
import type { Agent, Project, Software, Task } from "../api/types";
import { useApi } from "../context";
import { Icon, type IconName } from "./icons";

type Hit = { kind: string; icon: IconName; label: string; page: string; detail?: string };

/**
 * One box for the whole ecosystem: projects, tasks, agents and software,
 * loaded the first time the operator types and filtered in the page.
 */
export function GlobalSearch({ navigate }: { navigate: (page: string, detail?: string) => void }) {
  const api = useApi();
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [index, setIndex] = useState<Hit[] | null>(null);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!query || index) return;
    Promise.all([
      api.projects().catch(() => [] as Project[]),
      api.tasks().catch(() => [] as Task[]),
      api.agents().catch(() => [] as Agent[]),
      api.software().catch(() => [] as Software[]),
    ]).then(([projects, tasks, agents, software]) => setIndex([
      ...projects.map((p): Hit => ({ kind: "Progetto", icon: "projects", label: p.name, page: "projects" })),
      ...tasks.map((t): Hit => ({ kind: "Task", icon: "tasks", label: `#${t.id} ${t.title}`, page: "tasks" })),
      ...agents.map((a): Hit => ({ kind: "Agente", icon: "agents", label: `${a.name} — ${a.role}`, page: "agents" })),
      ...software.map((s): Hit => ({ kind: "Software", icon: "software", label: s.name, page: "software", detail: s.key })),
    ]));
  }, [api, query, index]);

  useEffect(() => {
    const onClick = (event: MouseEvent) => { if (!box.current?.contains(event.target as Node)) setOpen(false); };
    window.addEventListener("mousedown", onClick);
    return () => window.removeEventListener("mousedown", onClick);
  }, []);

  const needle = query.trim().toLowerCase();
  const hits = needle && index ? index.filter((hit) => hit.label.toLowerCase().includes(needle)).slice(0, 12) : [];

  return (
    <div className="search" ref={box}>
      <Icon name="search" size={16} />
      <input placeholder="Cerca in tutto l'ecosistema…" value={query} aria-label="Cerca"
             onChange={(event) => { setQuery(event.target.value); setOpen(true); }}
             onFocus={() => setOpen(true)} />
      {open && needle && (
        <div className="search-results">
          {!index && <div className="list-item muted">Carico…</div>}
          {index && hits.length === 0 && <div className="list-item muted">Nessun risultato</div>}
          {hits.map((hit, i) => (
            <button key={i} onClick={() => { navigate(hit.page, hit.detail); setOpen(false); setQuery(""); }}>
              <Icon name={hit.icon} size={15} /><span>{hit.label}</span><span className="spacer" />
              <span className="chip">{hit.kind}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
