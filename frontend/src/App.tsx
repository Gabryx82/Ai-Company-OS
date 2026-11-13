import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiProblem, ControlPlane, type Session } from "./api/client";
import { ApiContext, SessionContext } from "./context";
import { clearSession, DEFAULT_BASE_URL, loadSession, saveSession } from "./session";
import { clearStoredTerminals, storedTerminals } from "./terminalStore";
import { Login } from "./views/Login";
import { Dashboard } from "./views/Dashboard";
import { TaskBoard } from "./views/TaskBoard";
import { Agents } from "./views/Agents";
import { Projects } from "./views/Projects";
import { SoftwareHub } from "./views/SoftwareHub";
import { Models } from "./views/Models";
import { Usage } from "./views/Usage";
import { Integrations, KeptEmbeds } from "./views/Integrations";
import { Terminal } from "./views/Terminal";
import { Settings } from "./views/Settings";
import { KnowledgeHub } from "./views/KnowledgeHub";
import { DailyWork } from "./views/DailyWork";
import { SecondBrain } from "./views/SecondBrain";
import { MockupHub } from "./views/MockupHub";
import { Learn } from "./views/Learn";
import { OpenClaw } from "./views/OpenClaw";
import { GlobalSearch } from "./components/GlobalSearch";
import { FileExplorer, FileViewer, type OpenedFile } from "./components/FileExplorer";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { Icon, type IconName } from "./components/icons";
import { displayName } from "./preferences";
import { Ribbon } from "./components/Ribbon";
import { saveTheme, storedTheme, type Theme } from "./theme";

// ADR-037: three.js and the 3D view load only when its tab is opened.
const Ecosystem3D = lazy(() => import("./views/ecosystem3d/Ecosystem3D").then((m) => ({ default: m.Ecosystem3D })));
// The agents' office: its scene loads three.js only when the page opens.
const Office = lazy(() => import("./views/office/Office").then((m) => ({ default: m.Office })));

const FILES_OPEN_KEY = "aicos.files.open";

type NavItem = { key: string; label: string; icon: IconName };

// The sections, grouped by what the person is doing: the day, the projects,
// the agents that do the work, the tools they use, and what they know.
const NAV: { group: string | null; items: NavItem[] }[] = [
  { group: null, items: [
    { key: "dashboard", label: "Dashboard", icon: "dashboard" },
    { key: "daily", label: "Agenda", icon: "daily" },
  ] },
  { group: "Lavoro", items: [
    { key: "projects", label: "Progetti", icon: "projects" },
    { key: "tasks", label: "Task", icon: "tasks" },
    // PHASE 37 (ADR-044): the lessons the agents leave, and the review of what they taught.
    { key: "learn", label: "Impara", icon: "learn" },
  ] },
  { group: "Squadra", items: [
    { key: "agents", label: "Agenti", icon: "agents" },
    { key: "office", label: "Ufficio 3D", icon: "office" },
    { key: "ecosystem3d", label: "Ecosistema 3D", icon: "network" },
    { key: "models", label: "Modelli", icon: "models" },
    { key: "openclaw", label: "OpenClaw", icon: "claw" },
  ] },
  { group: "Strumenti", items: [
    { key: "software", label: "Software Hub", icon: "software" },
    { key: "terminal", label: "Terminale", icon: "terminal" },
    { key: "chat", label: "Chat", icon: "chat" },
    { key: "integrations", label: "Integrazioni", icon: "integrations" },
  ] },
  { group: "Conoscenza", items: [
    { key: "knowledge", label: "Knowledge Hub", icon: "knowledge" },
    { key: "mockups", label: "Mockup Hub", icon: "mockups" },
    { key: "brain", label: "Second Brain", icon: "brain" },
  ] },
];

// At the foot of the sidebar, apart from the work: what it costs and how it is set up.
const FOOT: NavItem[] = [
  { key: "usage", label: "Consumi", icon: "usage" },
  { key: "settings", label: "Impostazioni", icon: "settings" },
];

const ALL_ITEMS = [...NAV.flatMap((group) => group.items), ...FOOT];
const PAGES = new Set(ALL_ITEMS.map((item) => item.key));

export type Navigate = (page: string, detail?: string) => void;

function routeFromHash(): { page: string; detail: string | null } {
  const [page, ...rest] = window.location.hash.replace(/^#\/?/, "").split("/");
  return { page: PAGES.has(page) ? page : "dashboard", detail: rest.length ? decodeURIComponent(rest.join("/")) : null };
}

export function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  // TD-53: a tab with nothing stored asks whether the browser already holds a session cookie
  // for the control plane (another tab signed in): if so, it is signed in too.
  const [restoring, setRestoring] = useState(() => session === null);
  const [route, setRoute] = useState(routeFromHash);
  const [backendUp, setBackendUp] = useState<boolean | null>(null);
  // The terminal view stays mounted once visited, so its shells stay shown while
  // the operator works elsewhere; after a reload it mounts at once when this tab
  // had shells, which attach again (ADR-029 §5).
  const [terminalKept, setTerminalKept] = useState(() => storedTerminals().length > 0);
  const [terminalsRunning, setTerminalsRunning] = useState(0);
  useEffect(() => { if (route.page === "terminal") setTerminalKept(true); }, [route.page]);
  // ADR-046: an application embedded in the console (Open WebUI in Chat LLM, 3D Omniverse) stays loaded once
  // opened, hidden while the operator is elsewhere -- it used to start again at every visit.
  const [embedsKept, setEmbedsKept] = useState<string[]>([]);
  // Before any early return below: a hook is called on every render, or React loses count of them.
  const embedShown = route.page === "chat" ? "open-webui"
    : route.page === "integrations" && route.detail ? route.detail : null;
  useEffect(() => {
    if (embedShown) setEmbedsKept((kept) => kept.includes(embedShown) ? kept : [...kept, embedShown]);
  }, [embedShown]);
  // PHASE 33 (ADR-039): the file explorer on the left, opened with the button in the top bar;
  // a file opens where the page was, and going to another page closes it.
  const [filesOpen, setFilesOpen] = useState(() => {
    try { return localStorage.getItem(FILES_OPEN_KEY) === "1"; } catch { return false; }
  });
  const [opened, setOpened] = useState<OpenedFile | null>(null);
  useEffect(() => { setOpened(null); }, [route.page, route.detail]);
  const toggleFiles = useCallback(() => {
    setFilesOpen((open) => {
      try { localStorage.setItem(FILES_OPEN_KEY, open ? "0" : "1"); } catch { /* the panel still toggles */ }
      return !open;
    });
  }, []);

  // A refreshed anti-forgery token (another tab signed in) is kept for the next reload.
  const api = useMemo(() => (session ? new ControlPlane(session, undefined, saveSession) : null), [session]);

  useEffect(() => {
    if (!restoring) return;
    let cancelled = false;
    ControlPlane.restore(DEFAULT_BASE_URL)
      .then((found) => { if (found && !cancelled) { saveSession(found); setSession(found); } })
      .finally(() => { if (!cancelled) setRestoring(false); });
    return () => { cancelled = true; };
  }, [restoring]);

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
  const signOut = useCallback(() => { clearSession(); clearStoredTerminals(); setSession(null); }, []);
  // Signing out also ends the session on the server (ADR-024) and expires its cookie (TD-53).
  // The shells this tab shows end with it; others stay listed for the next sign-in.
  const logout = useCallback(() => {
    const closing = storedTerminals().map((t) => api?.closeTerminalSession(t.session).catch(() => undefined));
    Promise.all(closing).then(() => api?.logout()).catch(() => undefined).finally(signOut);
  }, [api, signOut]);
  const [menuOpen, setMenuOpen] = useState(false);
  const [theme, setTheme] = useState<Theme>(storedTheme);
  // At phone width the sidebar is a sheet opened from the top bar; it closes on every move.
  const [navOpen, setNavOpen] = useState(false);
  const sidebar = useRef<HTMLElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  useEffect(() => { setNavOpen(false); }, [route.page, route.detail]);
  useEffect(() => {
    if (!navOpen) return;
    sidebar.current?.querySelector<HTMLElement>("button")?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { setNavOpen(false); menuButton.current?.focus(); return; }
      if (event.key !== "Tab" || !sidebar.current) return;
      // Focus stays inside the open sheet.
      const items = [...sidebar.current.querySelectorAll<HTMLElement>("button, [href], [tabindex]:not([tabindex='-1'])")];
      const first = items[0], last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [navOpen]);

  // Who is signed in, fresh from the server: role and "must change password" can change.
  useEffect(() => {
    if (!api || !session) return;
    api.me().then((me) => {
      if (me.user && JSON.stringify(me.user) !== JSON.stringify(session.user)) {
        const next = { ...session, user: me.user };
        saveSession(next);
        setSession(next);
      }
    }).catch(() => undefined);
  }, [api]); // eslint-disable-line react-hooks/exhaustive-deps
  const navigate: Navigate = useCallback((page, detail) => {
    window.location.hash = `/${page}${detail ? `/${encodeURIComponent(detail)}` : ""}`;
  }, []);

  // A 401 anywhere means the session ended (signed out, expired, revoked): back to the login.
  useEffect(() => {
    const onRejection = (event: PromiseRejectionEvent) => {
      if (event.reason instanceof ApiProblem && event.reason.isUnauthenticated) signOut();
    };
    window.addEventListener("unhandledrejection", onRejection);
    return () => window.removeEventListener("unhandledrejection", onRejection);
  }, [signOut]);

  if (!session && restoring) {
    return <div className="login"><p className="muted">Verifico la sessione…</p></div>;
  }

  if (!session || !api) {
    return <Login onSignIn={signIn} />;
  }

  const user = session.user;
  const name = user?.displayName || user?.username || displayName();
  const { page, detail } = route;
  const embeds = embedShown && !embedsKept.includes(embedShown) ? [...embedsKept, embedShown] : embedsKept;
  const projectInView = page === "projects" && detail && /^\d+$/.test(detail) ? Number(detail) : null;
  const pageLabel = ALL_ITEMS.find((item) => item.key === page)?.label ?? page;
  const navButton = (item: NavItem) => (
    <button key={item.key} className="nav-item" aria-current={page === item.key ? "page" : undefined}
            onClick={() => navigate(item.key)}>
      <Icon name={item.icon} size={17} />{item.label}
      {item.key === "terminal" && terminalsRunning > 0 && (
        <span className="nav-count" title="Terminali aperti in background">{terminalsRunning}</span>
      )}
    </button>
  );

  return (
    <ApiContext.Provider value={api}>
      <SessionContext.Provider value={session}>
      <div className={`app${filesOpen ? " files-open" : ""}${navOpen ? " nav-open" : ""}`}>
        {navOpen && <div className="nav-scrim" onClick={() => setNavOpen(false)} aria-hidden="true" />}
        <nav className="sidebar" aria-label="Sections" id="sections" ref={sidebar}>
          <div className="brand">
            <span className="brand-mark"><Icon name="sparkle" size={16} /></span>
            <span>AI Company OS</span>
          </div>
          {NAV.map((group) => (
            <div key={group.group ?? "home"} style={{ display: "contents" }}>
              {group.group && <div className="nav-group">{group.group}</div>}
              {group.items.map(navButton)}
            </div>
          ))}
          <div className="sidebar-foot">
            {FOOT.map(navButton)}
            <div className="system-card" role="status">
              <Ribbon />
              <strong>
                <span className={`dot ${backendUp === false ? "dot-danger" : backendUp ? "dot-ok" : "dot-off"}`} />
                {backendUp === false ? "Server non raggiungibile" : backendUp ? "Tutto operativo" : "Verifico il server…"}
              </strong>
              <span className="mono" title={session.baseUrl}>{session.baseUrl.replace(/^https?:\/\//, "")}</span>
            </div>
          </div>
        </nav>
        {filesOpen && (
          <ErrorBoundary label="File">
            <FileExplorer projectHint={projectInView} opened={opened} onOpen={setOpened} onClose={toggleFiles}
                          onOpenedChange={setOpened} />
          </ErrorBoundary>
        )}
        <main className="main">
          <header className="topbar">
            <button className="icon-button menu-button" ref={menuButton} onClick={() => setNavOpen(true)}
                    aria-label="Apri il menu" aria-expanded={navOpen} aria-controls="sections">
              <Icon name="menu" size={18} />
            </button>
            <button className="btn files-toggle" aria-pressed={filesOpen} onClick={toggleFiles}
                    title={filesOpen ? "Chiudi il pannello dei file" : "Apri i file dei progetti"}>
              <Icon name="files" size={15} /> File
            </button>
            <GlobalSearch navigate={navigate} />
            <span className="spacer" />
            <span className="muted topbar-date">{new Date().toLocaleDateString("it-IT", { weekday: "long", day: "numeric", month: "long" })}</span>
            <button className="icon-button" onClick={() => { const next = theme === "dark" ? "light" : "dark"; saveTheme(next); setTheme(next); }}
                    aria-label={theme === "dark" ? "Passa al tema chiaro" : "Passa al tema scuro"}
                    title={theme === "dark" ? "Tema chiaro" : "Tema scuro"}>
              <Icon name={theme === "dark" ? "sun" : "moon"} size={17} />
            </button>
            <div className="user-menu">
              <button className="user-chip" onClick={() => setMenuOpen((open) => !open)} aria-expanded={menuOpen}
                      aria-label="Account">
                <span className="avatar">{name.slice(0, 1).toUpperCase()}</span>
                <span className="user-chip-text"><strong>{name}</strong>
                  <small>{user ? (user.role === "ADMIN" ? "Admin" : "Operatore") : "Token di servizio"}</small></span>
              </button>
              {menuOpen && (
                <div className="menu" role="menu" onMouseLeave={() => setMenuOpen(false)}>
                  <button role="menuitem" onClick={() => { setMenuOpen(false); navigate("settings", "account"); }}>
                    <Icon name="settings" size={14} /> Account e sicurezza</button>
                  <button role="menuitem" onClick={logout}><Icon name="logout" size={14} /> Esci</button>
                </div>
              )}
            </div>
          </header>
          {user?.mustChangePassword && (
            <div className="notice notice-warn" style={{ margin: "12px 24px 0" }}>
              <Icon name="warning" size={14} /> Stai usando una password iniziale generata.{" "}
              <button className="btn btn-small" onClick={() => navigate("settings", "account")}>Cambiala ora</button>
            </div>
          )}
          {opened && (
            <ErrorBoundary label="File">
              <FileViewer file={opened} onClose={() => setOpened(null)} />
            </ErrorBoundary>
          )}
          <div className="content" hidden={opened !== null}>
            <ErrorBoundary key={page} label={pageLabel}>
            {page === "dashboard" && <Dashboard navigate={navigate} />}
            {page === "projects" && <Projects selected={detail} navigate={navigate} />}
            {page === "tasks" && <TaskBoard />}
            {page === "learn" && <Learn selected={detail} navigate={navigate} />}
            {page === "agents" && <Agents selected={detail} navigate={navigate} />}
            {page === "ecosystem3d" && (
              <Suspense fallback={<p className="muted">Carico la vista 3D…</p>}><Ecosystem3D navigate={navigate} /></Suspense>
            )}
            {page === "office" && (
              <Suspense fallback={<p className="muted">Apro l'ufficio…</p>}><Office /></Suspense>
            )}
            {page === "daily" && <DailyWork />}
            {page === "brain" && <SecondBrain navigate={navigate} />}
            {page === "software" && <SoftwareHub selected={detail} navigate={navigate} />}
            {page === "integrations" && !detail && <Integrations navigate={navigate} />}
            {page === "openclaw" && <OpenClaw navigate={navigate} />}
            {page === "knowledge" && <KnowledgeHub />}
            {page === "mockups" && <MockupHub />}
            {page === "models" && <Models />}
            {page === "usage" && <Usage />}
            {page === "settings" && <Settings session={session} onSessionChanged={(next) => { saveSession(next); setSession(next); }} />}
            </ErrorBoundary>
            {/* Open WebUI inside the console, as the ChatLLM tab of 3D Omniverse does: kept loaded (ADR-046). */}
            {embeds.length > 0 && (
              <div hidden={embedShown === null}>
                <ErrorBoundary label="Integrazioni">
                  <KeptEmbeds keys={embeds} current={embedShown} navigate={navigate} />
                </ErrorBoundary>
              </div>
            )}
            {terminalKept && (
              <div hidden={page !== "terminal"}>
                <ErrorBoundary label="Terminale">
                  <Terminal visible={page === "terminal"} onRunning={setTerminalsRunning} />
                </ErrorBoundary>
              </div>
            )}
          </div>
        </main>
      </div>
      </SessionContext.Provider>
    </ApiContext.Provider>
  );
}
