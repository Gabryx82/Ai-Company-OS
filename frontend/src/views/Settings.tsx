import { useCallback, useEffect, useState, type FormEvent } from "react";
import { ApiProblem, type Session } from "../api/client";
import type { Role, SecurityEventInfo, UserInfo } from "../api/types";
import { useApi } from "../context";
import { displayName, setDisplayName } from "../preferences";
import { Field, Modal, ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";
import { EcosystemPanel } from "../components/EcosystemPanel";

const EVENT_LABELS: Record<string, string> = {
  LOGIN_SUCCEEDED: "Accesso riuscito", LOGIN_FAILED: "Accesso fallito", ACCOUNT_LOCKED: "Account bloccato",
  LOGIN_THROTTLED: "Troppi tentativi", LOGOUT: "Uscita", PASSWORD_CHANGED: "Password cambiata",
  PASSWORD_RESET: "Password reimpostata", ADMIN_BOOTSTRAPPED: "Admin creato all'avvio", USER_CREATED: "Utente creato",
  USER_UPDATED: "Utente modificato", ACCESS_DENIED: "Accesso negato", DATA_DELETED: "Dati eliminati",
  PROCESS_STARTED: "Processo avviato",
};

/** Preferences of this console, one's own account, and -- for admins -- people and the security log. */
export function Settings({ session, onSessionChanged }: { session: Session; onSessionChanged: (next: Session) => void }) {
  const isAdmin = session.user?.role === "ADMIN";
  // "Account e sicurezza" in the user menu lands here: bring the account card into view.
  useEffect(() => {
    if (window.location.hash.endsWith("/account")) document.getElementById("account")?.scrollIntoView?.({ block: "start" });
  }, []);
  return (
    <div className="stack" style={{ maxWidth: 980 }}>
      <div className="page-head"><div><h1>Impostazioni</h1>
        <p>Il tuo account, l'ecosistema all'avvio e, per gli admin, utenti e registro di sicurezza.</p></div></div>
      {session.user ? (
        <div className="grid grid-2" id="account">
          <ProfileCard session={session} onChanged={onSessionChanged} />
          <PasswordCard session={session} onChanged={onSessionChanged} />
        </div>
      ) : (
        <div className="grid grid-2">
          <LocalNameCard />
          <div className="card card-body"><strong>Token di servizio</strong>
            <p className="muted">Questa console usa un token di servizio, non un account: non c'è una password da cambiare.</p></div>
        </div>
      )}
      <EcosystemPanel manage />
      {isAdmin && <UsersCard self={session.user!} />}
      {isAdmin && <SecurityLogCard />}
    </div>
  );
}

/** A signed-in person's name lives on the account: the top bar, the greeting and the user list all read it. */
function ProfileCard({ session, onChanged }: { session: Session; onChanged: (next: Session) => void }) {
  const api = useApi();
  const user = session.user!;
  const [name, setName] = useState(user.displayName ?? "");
  const [saved, setSaved] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);

  async function save(event: FormEvent) {
    event.preventDefault();
    setProblem(null);
    try {
      const updated = await api.updateProfile(name);
      onChanged({ ...session, user: updated });
      setSaved(true);
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <form className="card card-body stack" onSubmit={save}>
      <div className="section-title">Il mio account</div>
      <div className="row"><Icon name="agents" size={15} /><strong>{user.username}</strong>
        <span className="chip">{user.role === "ADMIN" ? "Admin" : "Operatore"}</span>
        {user.lastLoginAt && <><span className="spacer" /><span className="muted" style={{ fontSize: 12 }}>ultimo accesso {when(user.lastLoginAt)}</span></>}</div>
      <Field label="Nome visualizzato" hint="Compare in alto a destra e nel saluto della dashboard. Vuoto: si usa il nome utente.">
        <input value={name} maxLength={120} onChange={(e) => { setName(e.target.value); setSaved(false); }} autoComplete="name" />
      </Field>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="row"><span className="spacer" />{saved && <span className="badge badge-ok">Salvato</span>}
        <button className="btn btn-primary" type="submit" disabled={name.trim() === (user.displayName ?? "")}>Salva nome</button></div>
    </form>
  );
}

/** Without an account (a service token) the name can only be this browser's preference. */
function LocalNameCard() {
  const [name, setName] = useState(displayName());
  const [saved, setSaved] = useState(false);
  return (
    <div className="card card-body stack">
      <div className="section-title">Preferenze di questa console</div>
      <Field label="Nome visualizzato" hint="Usato nel saluto della dashboard. Resta in questo browser.">
        <input value={name} onChange={(e) => { setName(e.target.value); setSaved(false); }} />
      </Field>
      <div className="row"><span className="spacer" />{saved && <span className="badge badge-ok">Salvato</span>}
        <button className="btn btn-primary" onClick={() => { setDisplayName(name); setSaved(true); }}>Salva nome</button></div>
    </div>
  );
}

const MINIMUM_PASSWORD = 12;
const MAXIMUM_PASSWORD_BYTES = 72;

/** The server's rules (ADR-024 §2), checked as one types, so the button never just sits there greyed out. */
function passwordIssues(current: string, next: string, again: string, username: string): string[] {
  const issues: string[] = [];
  if (!current) issues.push("Scrivi la password attuale.");
  const length = [...next].length;
  if (length < MINIMUM_PASSWORD) {
    issues.push(length === 0 ? `Scrivi la nuova password (almeno ${MINIMUM_PASSWORD} caratteri).`
      : `La nuova password è troppo corta: servono ancora ${MINIMUM_PASSWORD - length} caratteri.`);
  }
  if (new TextEncoder().encode(next).length > MAXIMUM_PASSWORD_BYTES) issues.push(`La nuova password supera i ${MAXIMUM_PASSWORD_BYTES} byte.`);
  if (next && username && next.toLowerCase().includes(username.toLowerCase())) issues.push(`La nuova password non può contenere il nome utente «${username}».`);
  if (next && current && next === current) issues.push("La nuova password deve essere diversa da quella attuale.");
  if (length >= MINIMUM_PASSWORD && !again) issues.push("Ripeti la nuova password.");
  if (again && again !== next) issues.push("Le due password non coincidono.");
  return issues;
}

/** The server's refusals, in words. */
function passwordProblem(error: unknown): unknown {
  if (!(error instanceof ApiProblem)) return error;
  if (error.slug === "current-password-wrong") {
    return new ApiProblem(error.status, { type: error.type, title: "Password attuale errata",
      detail: "La password attuale non è quella giusta. Nulla è cambiato." });
  }
  if (error.slug === "weak-password") {
    const detail = error.detail.includes("at least") ? `La password deve avere almeno ${MINIMUM_PASSWORD} caratteri.`
      : error.detail.includes("at most") ? `La password può avere al massimo ${MAXIMUM_PASSWORD_BYTES} byte.`
      : error.detail.includes("username") ? "La password non può contenere il nome utente."
      : error.detail.includes("differ") ? "La nuova password deve essere diversa da quella attuale."
      : error.detail.includes("easy") ? "La password è troppo facile da indovinare: allungala o rendila meno prevedibile."
      : error.detail;
    return new ApiProblem(error.status, { type: error.type, title: "Password non accettata", detail });
  }
  return error;
}

function PasswordCard({ session, onChanged }: { session: Session; onChanged: (next: Session) => void }) {
  const api = useApi();
  const user = session.user!;
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [tried, setTried] = useState(false);
  const issues = passwordIssues(current, next, again, user.username);
  const showIssues = (tried || Boolean(current || next || again)) && issues.length > 0;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setTried(true);
    if (issues.length > 0 || busy) return;
    setProblem(null);
    setBusy(true);
    try {
      await api.changePassword(current, next);
      setCurrent(""); setNext(""); setAgain(""); setTried(false); setDone(true);
      const me = await api.me();
      if (me.user) onChanged({ ...session, user: me.user });
    } catch (error) {
      setProblem(passwordProblem(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card card-body stack" onSubmit={submit}>
      <div className="row"><div className="section-title">Password</div><span className="spacer" />
        <span className="muted" style={{ fontSize: 12 }}>cambiata il {when(user.passwordChangedAt)}</span></div>
      <Field label="Password attuale"><input type="password" value={current} onChange={(e) => { setCurrent(e.target.value); setDone(false); }} autoComplete="current-password" /></Field>
      <Field label="Nuova password" hint={`Almeno ${MINIMUM_PASSWORD} caratteri, senza il nome utente. Le tue altre sessioni verranno chiuse.`}>
        <input type="password" value={next} onChange={(e) => { setNext(e.target.value); setDone(false); }} autoComplete="new-password" /></Field>
      <Field label="Ripeti la nuova password">
        <input type="password" value={again} onChange={(e) => { setAgain(e.target.value); setDone(false); }} autoComplete="new-password" /></Field>
      {showIssues && (
        <ul className="hint-list" aria-live="polite">{issues.map((issue) => <li key={issue}>{issue}</li>)}</ul>
      )}
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="row"><span className="spacer" />{done && <span className="badge badge-ok">Password cambiata</span>}
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {busy ? "Cambio in corso…" : "Cambia password"}</button></div>
    </form>
  );
}

function UsersCard({ self }: { self: UserInfo }) {
  const api = useApi();
  const [users, setUsers] = useState<UserInfo[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);
  const [resetting, setResetting] = useState<UserInfo | null>(null);
  const load = useCallback(() => api.users().then(setUsers).catch(setProblem), [api]);
  useEffect(() => { load(); }, [load]);

  async function change(user: UserInfo, update: { role?: Role; enabled?: boolean }) {
    setProblem(null);
    try {
      await api.updateUser(user.id, user.version, { displayName: user.displayName, role: update.role ?? user.role,
        enabled: update.enabled ?? user.enabled });
    } catch (error) {
      setProblem(error);
    }
    load();
  }

  return (
    <div className="card card-body stack">
      <div className="row"><div className="section-title" style={{ margin: 0 }}>Utenti</div><span className="spacer" />
        <button className="btn btn-small btn-primary" onClick={() => setCreating(true)}><Icon name="plus" size={13} /> Nuovo utente</button></div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <table className="table">
        <thead><tr><th>Utente</th><th>Ruolo</th><th>Stato</th><th>Ultimo accesso</th><th /></tr></thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.id}>
              <td><strong>{u.username}</strong>{u.displayName && <span className="muted"> · {u.displayName}</span>}
                {u.mustChangePassword && <span className="chip">password iniziale</span>}</td>
              <td>
                <select value={u.role} aria-label={`Ruolo di ${u.username}`} onChange={(e) => change(u, { role: e.target.value as Role })}>
                  <option value="ADMIN">Admin</option><option value="OPERATOR">Operatore</option>
                </select>
              </td>
              <td>{u.enabled ? <span className="badge badge-ok">attivo</span> : <span className="badge">disattivato</span>}</td>
              <td className="muted">{when(u.lastLoginAt)}</td>
              <td className="row" style={{ justifyContent: "flex-end" }}>
                {u.id !== self.id && <button className="btn btn-small" onClick={() => change(u, { enabled: !u.enabled })}>
                  {u.enabled ? "Disattiva" : "Riattiva"}</button>}
                <button className="btn btn-small" onClick={() => setResetting(u)}>Reimposta password</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {creating && <NewUserModal onClose={() => setCreating(false)} onCreated={() => { setCreating(false); load(); }} />}
      {resetting && <ResetModal user={resetting} onClose={() => setResetting(null)} onDone={() => { setResetting(null); load(); }} />}
    </div>
  );
}

function NewUserModal({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const api = useApi();
  const [username, setUsername] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState<Role>("OPERATOR");
  const [password, setPassword] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      await api.createUser({ username, displayName: name || undefined, role, initialPassword: password });
      onCreated();
    } catch (error) {
      setProblem(error);
    }
  }
  return (
    <Modal title="Nuovo utente" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Field label="Nome utente" hint="Minuscole, cifre, . _ -"><input value={username} onChange={(e) => setUsername(e.target.value)} required /></Field>
        <Field label="Nome visualizzato"><input value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Ruolo" hint="L'operatore lavora; l'admin gestisce anche utenti, registro di sicurezza ed eliminazioni.">
          <select value={role} onChange={(e) => setRole(e.target.value as Role)}><option value="OPERATOR">Operatore</option><option value="ADMIN">Admin</option></select></Field>
        <Field label="Password iniziale" hint="Da comunicare alla persona: al primo accesso le verrà chiesto di cambiarla.">
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required /></Field>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit">Crea utente</button></div>
      </form>
    </Modal>
  );
}

function ResetModal({ user, onClose, onDone }: { user: UserInfo; onClose: () => void; onDone: () => void }) {
  const api = useApi();
  const [password, setPassword] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      await api.resetUserPassword(user.id, user.version, password);
      onDone();
    } catch (error) {
      setProblem(error);
    }
  }
  return (
    <Modal title={`Reimposta la password di ${user.username}`} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <p className="muted">Le sessioni aperte di {user.username} verranno chiuse e al prossimo accesso dovrà scegliere una nuova password.</p>
        <Field label="Password temporanea"><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required /></Field>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row"><span className="spacer" /><button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-primary" type="submit">Reimposta</button></div>
      </form>
    </Modal>
  );
}

function SecurityLogCard() {
  const api = useApi();
  const [events, setEvents] = useState<SecurityEventInfo[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const load = useCallback(() => api.securityEvents(100).then(setEvents).catch(setProblem), [api]);
  useEffect(() => { load(); }, [load]);
  return (
    <div className="card card-body stack">
      <div className="row"><div className="section-title" style={{ margin: 0 }}>Registro di sicurezza</div><span className="spacer" />
        <button className="btn btn-small" onClick={load}><Icon name="refresh" size={13} /> Aggiorna</button></div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <table className="table">
        <thead><tr><th>Quando</th><th>Evento</th><th>Chi</th><th>Da</th><th>Dettaglio</th></tr></thead>
        <tbody>
          {events.map((e) => (
            <tr key={e.id}>
              <td className="muted">{when(e.occurredAt)}</td>
              <td><span className={e.type.includes("FAILED") || e.type.includes("DENIED") || e.type.includes("LOCKED") ? "badge badge-danger" : "badge"}>
                {EVENT_LABELS[e.type] ?? e.type}</span></td>
              <td>{e.principal ?? "—"}</td><td className="mono muted">{e.source ?? "—"}</td><td className="muted">{e.detail ?? ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
