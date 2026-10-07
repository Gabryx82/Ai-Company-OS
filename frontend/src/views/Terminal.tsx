import { lazy, Suspense, useCallback, useEffect, useState } from "react";
import { useApi, useIsAdmin } from "../context";
import type { Project, Software, TerminalSession, TerminalShell } from "../api/types";
// xterm.js is loaded only when a terminal is opened: it stays out of the console's main bundle.
const TerminalTab = lazy(() => import("../components/TerminalTab").then((m) => ({ default: m.TerminalTab })));
import { ProblemNote, when } from "../components/ui";
import { AppIcon } from "../components/AppIcon";
import { Icon } from "../components/icons";
import { AVAILABILITY, useLauncher } from "./SoftwareHub";
import { storedTerminals, storeTerminals } from "../terminalStore";

interface Tab {
  id: number;
  shell: string;
  name: string;
  projectId?: number;
  /** The shell on the server, once it is known; set from the start when the tab shows an existing one. */
  session?: string;
  attach?: string;
}

/**
 * The terminal tab: PowerShell, Claude Code or OpenCode, in a project folder --
 * inside the page (PHASE 22, ADR-029: a pseudo-terminal streamed over a
 * WebSocket, admins only), or in Windows Terminal, one click away.
 *
 * The shells live on the server (§5). The console keeps this view mounted once
 * visited (hidden on the other pages), so they stay shown while the operator
 * works elsewhere; after a reload the tabs come back and attach again; the
 * shells this browser tab does not show (left by a closed tab, or shown in
 * another) are listed "in background", to take back or to close. A shell ends
 * when it exits or when its tab is closed here. `onRunning` tells the
 * navigation how many this tab shows.
 */
export function Terminal({ visible = true, onRunning }: { visible?: boolean; onRunning?: (count: number) => void }) {
  const api = useApi();
  const [clis, setClis] = useState<Software[] | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<number | "">("");
  const [problem, setProblem] = useState<unknown>(null);
  const { launch, outcome } = useLauncher();
  const isAdmin = useIsAdmin();
  const [shells, setShells] = useState<TerminalShell[]>([]);
  const [tabs, setTabs] = useState<Tab[]>(() => storedTerminals().map((t, i) => (
    { id: i + 1, shell: t.shell, name: t.name, session: t.session, attach: t.session })));
  const [active, setActive] = useState<number | null>(() => (tabs.length ? tabs[tabs.length - 1].id : null));
  const [nextId, setNextId] = useState(() => tabs.length + 1);
  const [ended, setEnded] = useState<ReadonlySet<number>>(new Set());
  const [background, setBackground] = useState<TerminalSession[]>([]);
  const running = tabs.filter((t) => !ended.has(t.id)).length;

  useEffect(() => {
    if (isAdmin) api.terminalShells().then(setShells).catch(() => setShells([]));
  }, [api, isAdmin]);

  useEffect(() => { onRunning?.(running); }, [running, onRunning]);

  // What this browser tab shows, for the next reload.
  useEffect(() => {
    storeTerminals(tabs.filter((t) => t.session && !ended.has(t.id))
      .map((t) => ({ session: t.session!, shell: t.shell, name: t.name })));
  }, [tabs, ended]);

  const shown = new Set(tabs.filter((t) => !ended.has(t.id)).map((t) => t.session));
  const refreshBackground = useCallback(() => {
    if (isAdmin) api.terminalSessions().then(setBackground).catch(() => setBackground([]));
  }, [api, isAdmin]);
  useEffect(() => { if (visible) refreshBackground(); }, [visible, refreshBackground]);

  function nameOf(shell: string) {
    return shells.find((s) => s.key === shell)?.name ?? shell;
  }

  function markEnded(id: number) {
    setEnded((current) => (current.has(id) ? current : new Set(current).add(id)));
  }

  function setSession(id: number, session: string) {
    setTabs((current) => current.map((t) => (t.id === id && t.session !== session ? { ...t, session } : t)));
  }

  function addTab(tab: Omit<Tab, "id">) {
    const id = nextId;
    setNextId(id + 1);
    setTabs((current) => [...current, { id, ...tab }]);
    setActive(id);
  }

  function openTab(shell: TerminalShell) {
    addTab({ shell: shell.key, name: shell.name, projectId: projectId || undefined });
  }

  function takeBack(session: TerminalSession) {
    addTab({ shell: session.shell, name: nameOf(session.shell), session: session.id, attach: session.id });
    setBackground((current) => current.filter((s) => s.id !== session.id));
  }

  /** Closing the tab ends the shell; one that already ended just goes away. */
  function closeTab(id: number) {
    const tab = tabs.find((t) => t.id === id);
    if (tab?.session && !ended.has(id)) api.closeTerminalSession(tab.session).catch(() => undefined);
    const rest = tabs.filter((t) => t.id !== id);
    setTabs(rest);
    if (active === id) setActive(rest.length ? rest[rest.length - 1].id : null);
  }

  function closeBackground(session: TerminalSession) {
    api.closeTerminalSession(session.id).catch(setProblem).finally(refreshBackground);
  }

  useEffect(() => {
    api.software().then((all) => setClis(all.filter((s) => s.launchKind === "CLI"))).catch(setProblem);
  }, [api]);

  // Read again on every return to the page: a project created meanwhile is offered.
  useEffect(() => {
    if (visible) api.projects().then((p) => setProjects(p.filter((x) => x.status === "ACTIVE"))).catch(() => undefined);
  }, [api, visible]);

  const others = background.filter((s) => !shown.has(s.id));

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
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
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
          {others.length > 0 && (
            <div className="notice stack" style={{ gap: 6 }}>
              <strong>In background</strong>
              {others.map((s) => (
                <div key={s.id} className="row" style={{ fontSize: 12.5 }}>
                  <span><strong>{nameOf(s.shell)}</strong> <span className="mono muted">{s.directory}</span></span>
                  <span className="muted">
                    avviato {when(s.startedAt)} · {s.attached ? "aperto in un'altra scheda del browser" : `staccato ${when(s.detachedAt)}`}
                  </span>
                  <span className="spacer" />
                  <button className="btn btn-small" onClick={() => takeBack(s)}>Riprendi qui</button>
                  <button className="btn btn-small btn-danger" onClick={() => closeBackground(s)}>Chiudi</button>
                </div>
              ))}
            </div>
          )}
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
                    <TerminalTab shell={t.shell} projectId={t.projectId} attach={t.attach}
                                 onSession={(session) => setSession(t.id, session)} onExit={() => markEnded(t.id)} />
                  </Suspense>
                </div>
              ))}
            </>
          ) : <div className="muted" style={{ fontSize: 12.5 }}>Apri una shell: parte nella cartella del progetto scelto qui sopra, o nella tua cartella home. Resta attiva mentre usi le altre schede e anche se ricarichi la pagina.</div>}
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
