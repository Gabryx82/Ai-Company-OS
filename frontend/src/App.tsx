import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiProblem, ControlPlane, type Session } from "./api/client";
import { ApiContext } from "./context";
import { clearSession, loadSession, saveSession } from "./session";
import { Login } from "./views/Login";
import { Dashboard } from "./views/Dashboard";
import { TaskBoard } from "./views/TaskBoard";
import { Agents } from "./views/Agents";
import { Projects } from "./views/Projects";
import { SoftwareHub } from "./views/SoftwareHub";
import { Models } from "./views/Models";
import { Usage } from "./views/Usage";
import { Integrations } from "./views/Integrations";
import { Terminal } from "./views/Terminal";
import { Settings } from "./views/Settings";
import { KnowledgeHub } from "./views/KnowledgeHub";
import { DailyWork } from "./views/DailyWork";
import { SecondBrain } from "./views/SecondBrain";
import { MockupHub } from "./views/MockupHub";
import { GlobalSearch } from "./components/GlobalSearch";
import { Icon, type IconName } from "./components/icons";
import { displayName } from "./preferences";

// The sections of the ecosystem, grouped as the operator works: the work
// itself, the tools that do it, and the resources behind them.
const NAV: { group: string; items: { key: string; label: string; icon: IconName }[] }[] = [
  { group: "Lavoro", items: [
    { key: "dashboard", label: "Dashboard", icon: "dashboard" },
    { key: "projects", label: "Progetti", icon: "projects" },
    { key: "tasks", label: "Task", icon: "tasks" },
    { key: "agents", label: "Agenti", icon: "agents" },
    { key: "daily", label: "Daily Work", icon: "daily" },
    { key: "brain", label: "Second Brain", icon: "brain" },
  ] },
  { group: "Strumenti", items: [
    { key: "software", label: "Software Hub", icon: "software" },
    { key: "terminal", label: "Terminale", icon: "terminal" },
    { key: "integrations", label: "Integrazioni", icon: "integrations" },
  ] },
  { group: "Risorse", items: [
    { key: "knowledge", label: "Knowledge Hub", icon: "knowledge" },
    { key: "mockups", label: "Mockup Hub", icon: "mockups" },
    { key: "models", label: "Modelli LLM", icon: "models" },
    { key: "usage", label: "Consumi", icon: "usage" },
    { key: "settings", label: "Impostazioni", icon: "settings" },
  ] },
];

const PAGES = new Set(NAV.flatMap((group) => group.items.map((item) => item.key)));

export type Navigate = (page: string, detail?: string) => void;

function routeFromHash(): { page: string; detail: string | null } {
  const [page, ...rest] = window.location.hash.replace(/^#\/?/, "").split("/");
  return { page: PAGES.has(page) ? page : "dashboard", detail: rest.length ? decodeURIComponent(rest.join("/")) : null };
}

export function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [route, setRoute] = useState(routeFromHash);
  const [backendUp, setBackendUp] = useState<boolean | null>(null);

  const api = useMemo(() => (session ? new ControlPlane(session) : null), [session]);

  useEffect(() => {
    const onHash = () => setRoute(routeFromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  useEffect(() => {
    if (!api) return;
    let cancelled = false;
    const check = () => api.health().then((up) => !cancelled && setBackendUp(up));
    check();
    const timer = window.setInterval(check, 10_000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [api]);

  const signIn = useCallback((next: Session) => { saveSession(next); setSession(next); }, []);
  const signOut = useCallback(() => { clearSession(); setSession(null); }, []);
  const navigate: Navigate = useCallback((page, detail) => {
    window.location.hash = `/${page}${detail ? `/${encodeURIComponent(detail)}` : ""}`;
  }, []);

  // A 401 anywhere means the token is wrong or was rotated: back to the login.
  useEffect(() => {
    const onRejection = (event: PromiseRejectionEvent) => {
      if (event.reason instanceof ApiProblem && event.reason.isUnauthenticated) signOut();
    };
    window.addEventListener("unhandledrejection", onRejection);
    return () => window.removeEventListener("unhandledrejection", onRejection);
  }, [signOut]);

  if (!session || !api) {
    return <Login onSignIn={signIn} />;
  }

  const name = displayName();
  const { page, detail } = route;

  return (
    <ApiContext.Provider value={api}>
      <div className="app">
        <nav className="sidebar" aria-label="Sections">
          <div className="brand">
            <span className="brand-mark"><Icon name="sparkle" size={18} /></span>
            <span>AI Company OS<small>Ecosistema operativo</small></span>
          </div>
          {NAV.map((group) => (
            <div key={group.group} style={{ display: "contents" }}>
              <div className="nav-group">{group.group}</div>
              {group.items.map((item) => (
                <button key={item.key} className="nav-item" aria-current={page === item.key ? "page" : undefined}
                        onClick={() => navigate(item.key)}>
                  <Icon name={item.icon} size={17} />{item.label}
                </button>
              ))}
            </div>
          ))}
          <div className="sidebar-foot">
            <span>
              Control plane{" "}
              <span className={backendUp === false ? "badge badge-danger" : backendUp ? "badge badge-ok" : "badge"}>
                {backendUp === false ? "down" : backendUp ? "up" : "…"}
              </span>
            </span>
            <span className="mono">{session.baseUrl}</span>
            <button className="btn btn-small" onClick={signOut}><Icon name="logout" size={14} /> Esci</button>
          </div>
        </nav>
        <main className="main">
          <header className="topbar">
            <GlobalSearch navigate={navigate} />
            <span className="spacer" />
            <span className="muted">{new Date().toLocaleDateString("it-IT", { weekday: "long", day: "numeric", month: "long" })}</span>
            <span className="avatar" title={name}>{name.slice(0, 1).toUpperCase()}</span>
          </header>
          <div className="content">
            {page === "dashboard" && <Dashboard navigate={navigate} />}
            {page === "projects" && <Projects selected={detail} navigate={navigate} />}
            {page === "tasks" && <TaskBoard />}
            {page === "agents" && <Agents />}
            {page === "daily" && <DailyWork />}
            {page === "brain" && <SecondBrain navigate={navigate} />}
            {page === "software" && <SoftwareHub selected={detail} navigate={navigate} />}
            {page === "terminal" && <Terminal />}
            {page === "integrations" && <Integrations selected={detail} navigate={navigate} />}
            {page === "knowledge" && <KnowledgeHub />}
            {page === "mockups" && <MockupHub />}
            {page === "models" && <Models />}
            {page === "usage" && <Usage />}
            {page === "settings" && <Settings />}
          </div>
        </main>
      </div>
    </ApiContext.Provider>
  );
}
