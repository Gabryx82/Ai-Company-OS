import { useState, type FormEvent } from "react";
import { ApiProblem, ControlPlane, type Session } from "../api/client";
import { DEFAULT_BASE_URL } from "../session";
import { Field, ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";
import { Ribbon } from "../components/Ribbon";

/**
 * Sign-in with a username and a password (ADR-024). Since PHASE 32 (TD-53) the
 * session token is an HttpOnly cookie the control plane sets: this page never
 * holds it, only who signed in and the session's anti-forgery token.
 */
export function Login({ onSignIn }: { onSignIn: (session: Session) => void }) {
  const [baseUrl, setBaseUrl] = useState(DEFAULT_BASE_URL);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [advanced, setAdvanced] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      onSignIn(await ControlPlane.login(baseUrl, username.trim(), password));
    } catch (error) {
      setPassword("");
      setProblem(error instanceof ApiProblem && error.slug === "invalid-credentials"
        ? new ApiProblem(401, { title: "Accesso negato", type: error.type,
          detail: "Utente o password errati, oppure account disattivato o temporaneamente bloccato (5 tentativi errati: 15 minuti)." })
        : error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <Ribbon animate />
      <form className="card card-body stack login-card" onSubmit={submit}>
        <div className="login-brand">
          <span className="brand-mark"><Icon name="sparkle" size={18} /></span>
          <div>
            <h1>Bentornato</h1>
            <p className="muted">Accedi ad AI Company OS per riprendere il lavoro.</p>
          </div>
        </div>
        <Field label="Utente">
          <input value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus autoComplete="username" />
        </Field>
        <Field label="Password" hint="Al primo avvio trovi l'account admin e la sua password nella cartella %USERPROFILE%\.aicos.">
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required
                 autoComplete="current-password" />
        </Field>
        {advanced ? (
          <Field label="Indirizzo del server">
            <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} required />
          </Field>
        ) : (
          <button type="button" className="btn btn-small btn-ghost" style={{ justifySelf: "start" }} onClick={() => setAdvanced(true)}>
            Server: {baseUrl.replace(/^https?:\/\//, "")} · Cambia
          </button>
        )}
        <ProblemNote problem={problem} />
        <button className="btn btn-primary login-submit" type="submit" disabled={busy || !username.trim() || !password}>
          {busy ? "Accesso in corso…" : "Accedi"}
        </button>
      </form>
    </div>
  );
}
