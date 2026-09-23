import { useState, type FormEvent } from "react";
import { ApiProblem, ControlPlane, type Session } from "../api/client";
import { DEFAULT_BASE_URL } from "../session";
import { Field, ProblemNote } from "../components/ui";

/**
 * The operator's bearer token (ADR-013). Checked against a real protected route
 * before it is kept, so a wrong token is refused here and not on the first page.
 */
export function Login({ onSignIn }: { onSignIn: (session: Session) => void }) {
  const [baseUrl, setBaseUrl] = useState(DEFAULT_BASE_URL);
  const [token, setToken] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    const session = { baseUrl: baseUrl.trim(), token: token.trim() };
    try {
      await new ControlPlane(session).agents();
      onSignIn(session);
    } catch (error) {
      setProblem(error instanceof ApiProblem && error.isUnauthenticated
        ? new ApiProblem(401, { title: "Token refused", detail: "The control plane did not accept this token.",
          type: error.type })
        : error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <form className="card card-body stack" onSubmit={submit}>
        <div>
          <h1>AI Company OS</h1>
          <p className="muted">Sign in to the control plane with the operator token.</p>
        </div>
        <Field label="Control plane URL">
          <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} required />
        </Field>
        <Field label="Operator token" hint="In dev: AICOS_OPERATOR_TOKEN, or the local default dev-operator-token-change-me.">
          <input type="password" value={token} onChange={(e) => setToken(e.target.value)} required autoFocus
                 autoComplete="current-password" />
        </Field>
        <ProblemNote problem={problem} />
        <button className="btn btn-primary" type="submit" disabled={busy || !token.trim()}>
          {busy ? "Checking…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
