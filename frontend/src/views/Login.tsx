import { useState, type FormEvent } from "react";
import { ApiProblem, ControlPlane, type Session } from "../api/client";
import { DEFAULT_BASE_URL } from "../session";
import { Field, ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

/**
 * Sign-in with a username and a password (ADR-024). The session token the
 * control plane returns is kept for this tab only (sessionStorage).
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
      <form className="card card-body stack" onSubmit={submit}>
        <div className="row">
          <span className="brand-mark"><Icon name="sparkle" size={18} /></span>
          <div>
            <h1>AI Company OS</h1>
            <p className="muted">Accedi con il tuo account.</p>
          </div>
        </div>
        <Field label="Utente">
          <input value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus autoComplete="username" />
        </Field>
        <Field label="Password" hint="Primo avvio: l'account admin e la sua password sono in %USERPROFILE%\.aicos (vedi docs/RUNNING.md).">
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required
                 autoComplete="current-password" />
        </Field>
        {advanced ? (
          <Field label="Indirizzo del control plane">
            <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} required />
          </Field>
        ) : (
          <button type="button" className="btn btn-small" style={{ justifySelf: "start" }} onClick={() => setAdvanced(true)}>
            Control plane: {baseUrl}
          </button>
        )}
        <ProblemNote problem={problem} />
        <button className="btn btn-primary" type="submit" disabled={busy || !username.trim() || !password}>
          {busy ? "Verifica…" : "Accedi"}
        </button>
      </form>
    </div>
  );
}
