import { useCallback, useEffect, useState, type FormEvent } from "react";
import type { Session } from "../api/client";
import type { Role, SecurityEventInfo, UserInfo } from "../api/types";
import { useApi } from "../context";
import { displayName, setDisplayName } from "../preferences";
import { Field, Modal, ProblemNote, when } from "../components/ui";
import { Icon } from "../components/icons";

const EVENT_LABELS: Record<string, string> = {
  LOGIN_SUCCEEDED: "Accesso riuscito", LOGIN_FAILED: "Accesso fallito", ACCOUNT_LOCKED: "Account bloccato",
  LOGIN_THROTTLED: "Troppi tentativi", LOGOUT: "Uscita", PASSWORD_CHANGED: "Password cambiata",
  PASSWORD_RESET: "Password reimpostata", ADMIN_BOOTSTRAPPED: "Admin creato all'avvio", USER_CREATED: "Utente creato",
  USER_UPDATED: "Utente modificato", ACCESS_DENIED: "Accesso negato", DATA_DELETED: "Dati eliminati",
  PROCESS_STARTED: "Processo avviato",
};

/** Preferences of this console, one's own account, and -- for admins -- people and the security log. */
export function Settings({ session, onPasswordChanged }: { session: Session; onPasswordChanged: (next: Session) => void }) {
  const isAdmin = session.user?.role === "ADMIN";
  return (
    <div className="stack" style={{ maxWidth: 980 }}>
      <div className="page-head"><div><h1>Impostazioni</h1>
        <p>Preferenze della console, il tuo account e, per gli admin, utenti e registro di sicurezza.</p></div></div>
      <div className="grid grid-2">
        <Preferences />
        {session.user ? <PasswordCard session={session} onChanged={onPasswordChanged} /> : (
          <div className="card card-body"><strong>Token di servizio</strong>
            <p className="muted">Questa console usa un token di servizio, non un account: non c'è una password da cambiare.</p></div>
        )}
      </div>
      {isAdmin && <UsersCard self={session.user!} />}
      {isAdmin && <SecurityLogCard />}
    </div>
  );
}

function Preferences() {
  const [name, setName] = useState(displayName());
  const [saved, setSaved] = useState(false);
  return (
    <div className="card card-body stack">
      <div className="section-title">Preferenze di questa console</div>
      <Field label="Nome visualizzato" hint="Usato nel saluto della dashboard. Resta in questo browser.">
        <input value={name} onChange={(e) => { setName(e.target.value); setSaved(false); }} />
      </Field>
      <div className="row"><span className="spacer" />{saved && <span className="badge badge-ok">Salvato</span>}
        <button className="btn btn-primary" onClick={() => { setDisplayName(name); setSaved(true); }}>Salva</button></div>
    </div>
  );
}

function PasswordCard({ session, onChanged }: { session: Session; onChanged: (next: Session) => void }) {
  const api = useApi();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [done, setDone] = useState(false);
  const mismatch = again.length > 0 && again !== next;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setProblem(null);
    try {
      await api.changePassword(current, next);
      setCurrent(""); setNext(""); setAgain(""); setDone(true);
      const me = await api.me();
      if (me.user) onChanged({ ...session, user: me.user });
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <form className="card card-body stack" onSubmit={submit} id="account">
      <div className="section-title">Il mio account</div>
      <div className="row"><Icon name="agents" size={15} /><strong>{session.user!.username}</strong>
        <span className="chip">{session.user!.role === "ADMIN" ? "Admin" : "Operatore"}</span>
        <span className="spacer" /><span className="muted" style={{ fontSize: 12 }}>password del {when(session.user!.passwordChangedAt)}</span></div>
      <Field label="Password attuale"><input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required /></Field>
      <Field label="Nuova password" hint="Almeno 12 caratteri, al massimo 72 byte, senza il nome utente. Le altre sessioni verranno chiuse.">
        <input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" required /></Field>
      <Field label="Ripeti la nuova password" error={mismatch ? "Le due password non coincidono." : undefined}>
        <input type="password" value={again} onChange={(e) => setAgain(e.target.value)} autoComplete="new-password" required /></Field>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="row"><span className="spacer" />{done && <span className="badge badge-ok">Password cambiata</span>}
        <button className="btn btn-primary" type="submit" disabled={!current || next.length < 12 || mismatch || !again}>Cambia password</button></div>
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
