import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiProblem, ControlPlane, type Session } from "./api/client";
import { ApiContext } from "./context";
import { clearSession, loadSession, saveSession } from "./session";
import { Login } from "./views/Login";
import { Overview } from "./views/Overview";
import { TaskBoard } from "./views/TaskBoard";
import { Agents } from "./views/Agents";
import { Projects } from "./views/Projects";
import { Engine } from "./views/Engine";

const PAGES = {
  overview: "Overview",
  tasks: "Tasks",
  agents: "Agents",
  projects: "Projects",
  engine: "Models",
} as const;
type Page = keyof typeof PAGES;

function pageFromHash(): Page {
  const hash = window.location.hash.replace(/^#\/?/, "");
  return (hash in PAGES ? hash : "overview") as Page;
}

export function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [page, setPage] = useState<Page>(pageFromHash);
  const [backendUp, setBackendUp] = useState<boolean | null>(null);

  const api = useMemo(() => (session ? new ControlPlane(session) : null), [session]);

  useEffect(() => {
    const onHash = () => setPage(pageFromHash());
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

  return (
    <ApiContext.Provider value={api}>
      <div className="app">
        <nav className="sidebar" aria-label="Sections">
          <div className="brand">AI Company OS<small>Operator console</small></div>
          {(Object.keys(PAGES) as Page[]).map((key) => (
            <button key={key} className="nav-item" aria-current={page === key ? "page" : undefined}
                    onClick={() => { window.location.hash = `/${key}`; }}>
              {PAGES[key]}
            </button>
          ))}
          <div className="sidebar-foot">
            <span>
              Control plane{" "}
              <span className={backendUp === false ? "badge badge-danger" : backendUp ? "badge badge-ok" : "badge"}>
                {backendUp === false ? "down" : backendUp ? "up" : "…"}
              </span>
            </span>
            <span className="mono">{session.baseUrl}</span>
            <button className="btn btn-small" onClick={signOut}>Sign out</button>
          </div>
        </nav>
        <main className="main">
          {page === "overview" && <Overview />}
          {page === "tasks" && <TaskBoard />}
          {page === "agents" && <Agents />}
          {page === "projects" && <Projects />}
          {page === "engine" && <Engine />}
        </main>
      </div>
    </ApiContext.Provider>
  );
}
