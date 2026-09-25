import { lazy, Suspense, useEffect, useState } from "react";
import { useApi, useIsAdmin } from "../context";
import type { Project, Software, TerminalShell } from "../api/types";
// xterm.js is loaded only when a terminal is opened: it stays out of the console's main bundle.
const TerminalTab = lazy(() => import("../components/TerminalTab").then((m) => ({ default: m.TerminalTab })));
import { ProblemNote } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon } from "../components/icons";
import { AVAILABILITY, useLauncher } from "./SoftwareHub";

/**
 * The terminal tab: PowerShell, Claude Code or OpenCode, in a project folder --
 * inside the page (PHASE 22, ADR-029: a pseudo-terminal streamed over a
 * WebSocket, admins only), or in Windows Terminal, one click away.
 */
export function Terminal() {
  const api = useApi();
  const [clis, setClis] = useState<Software[] | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<number | "">("");
  const [problem, setProblem] = useState<unknown>(null);
  const { launch, outcome } = useLauncher();
  const isAdmin = useIsAdmin();
  const [shells, setShells] = useState<TerminalShell[]>([]);
  const [tabs, setTabs] = useState<{ id: number; shell: string; name: string; projectId?: number }[]>([]);
  const [active, setActive] = useState<number | null>(null);
  const [nextId, setNextId] = useState(1);

  useEffect(() => {
    if (isAdmin) api.terminalShells().then(setShells).catch(() => setShells([]));
  }, [api, isAdmin]);

  function openTab(shell: TerminalShell) {
    const id = nextId;
    setNextId(id + 1);
    setTabs([...tabs, { id, shell: shell.key, name: shell.name, projectId: projectId || undefined }]);
    setActive(id);
  }

  function closeTab(id: number) {
    const rest = tabs.filter((t) => t.id !== id);
    setTabs(rest);
    if (active === id) setActive(rest.length ? rest[rest.length - 1].id : null);
  }

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
      {isAdmin ? (
        <div className="card card-body stack">
          <div className="row">
            <div className="section-title" style={{ margin: 0 }}>Terminale nella console</div>
            <span className="spacer" />
            {shells.map((s) => (
              <button key={s.key} className="btn btn-small" disabled={!s.available} title={s.detail ?? ""} onClick={() => openTab(s)}>
                <Icon name="plus" size={12} /> {s.name}</button>
            ))}
          </div>
          {tabs.length > 0 ? (
            <>
              <div className="tabs">
                {tabs.map((t) => (
                  <span key={t.id} className="row" style={{ gap: 2 }}>
                    <button className="tab" aria-selected={active === t.id} onClick={() => setActive(t.id)}>{t.name} #{t.id}</button>
                    <button className="btn btn-small" aria-label={`Chiudi ${t.name} #${t.id}`} onClick={() => closeTab(t.id)}><Icon name="close" size={11} /></button>
                  </span>
                ))}
              </div>
              {tabs.map((t) => (
                <div key={t.id} style={{ display: active === t.id ? "block" : "none" }}>
                  <Suspense fallback={<div className="muted">Carico il terminale…</div>}>
                    <TerminalTab shell={t.shell} projectId={t.projectId} onExit={() => undefined} />
                  </Suspense>
                </div>
              ))}
            </>
          ) : <div className="muted" style={{ fontSize: 12.5 }}>Apri una shell: parte nella cartella del progetto scelto qui sopra, o nella tua cartella home.</div>}
        </div>
      ) : (
        <div className="notice">Il terminale dentro la console è riservato agli admin: è un processo sulla tua macchina. Puoi aprire il terminale di Windows qui sotto.</div>
      )}
      <div className="section-title">Oppure in Windows Terminal</div>
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

    </div>
  );
}
