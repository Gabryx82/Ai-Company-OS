import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { Project, Software } from "../api/types";
import { ProblemNote } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon } from "../components/icons";
import { AVAILABILITY, useLauncher } from "./SoftwareHub";

/**
 * The terminal tab: PowerShell, Claude Code or OpenCode, in a project folder,
 * inside Windows Terminal. An integrated terminal in the page (a PTY streamed
 * to the browser) is PHASE 15; this opens the real one, one click away.
 */
export function Terminal() {
  const api = useApi();
  const [clis, setClis] = useState<Software[] | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<number | "">("");
  const [problem, setProblem] = useState<unknown>(null);
  const { launch, outcome } = useLauncher();

  useEffect(() => {
    api.software().then((all) => setClis(all.filter((s) => s.launchKind === "CLI"))).catch(setProblem);
    api.projects().then((p) => setProjects(p.filter((x) => x.status === "ACTIVE"))).catch(() => undefined);
  }, [api]);

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Terminale</h1>
          <p>Scegli la shell o l'agente da riga di comando; si apre in Windows Terminal nella cartella del progetto.</p>
        </div>
        {projects.length > 0 && (
          <select style={{ width: "auto" }} value={projectId} aria-label="Progetto"
                  onChange={(e) => setProjectId(e.target.value ? Number(e.target.value) : "")}>
            <option value="">cartella home</option>
            {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        )}
      </div>
      <ProblemNote problem={problem} />
      <div className="grid grid-4">
        {clis?.map((s) => (
          <div key={s.key} className="card card-body stack" style={{ justifyItems: "start" }}>
            <div className="row"><AppIcon software={s} size={40} /><div><h2>{s.name}</h2>
              <span className="muted" style={{ fontSize: 12 }}>{AVAILABILITY[s.availability].label}</span></div></div>
            <p className="muted" style={{ margin: 0, fontSize: 12.5 }}>{s.purpose}</p>
            <code>{s.cliCommand}</code>
            <button className="btn btn-primary" disabled={!s.launchable} onClick={() => launch(s, projectId || undefined)}>
              <Icon name="terminal" size={14} /> Apri terminale</button>
          </div>
        ))}
      </div>
      {outcome}
      <div className="notice">
        Il terminale integrato nella pagina (PTY nel browser) è pianificato in PHASE 15. Oggi la console apre il
        terminale reale di Windows, già posizionato nella cartella giusta — nessuna emulazione.
      </div>
    </div>
  );
}
