import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { OpenCodeSession } from "../api/types";
import { when } from "../components/ui";

const STATUS: Record<OpenCodeSession["status"], { label: string; tone: string }> = {
  NOT_FOLLOWED: { label: "non seguita", tone: "badge" },
  UNREACHABLE: { label: "OpenCode non risponde", tone: "badge badge-warn" },
  WAITING: { label: "aperto, nessuna sessione", tone: "badge" },
  BUSY: { label: "al lavoro", tone: "badge badge-accent" },
  IDLE: { label: "in attesa", tone: "badge badge-ok" },
  RETRY: { label: "riprova", tone: "badge badge-warn" },
};

/**
 * PHASE 32 (TD-49): the OpenCode session a handoff opened, followed from its local
 * API while the drawer is open -- working or waiting, the files it changed, its
 * last answer. Read-only: what to do next stays in OpenCode and in the review.
 */
export function OpenCodeFollow({ handoffId, every = 5000 }: { handoffId: number; every?: number }) {
  const api = useApi();
  const [session, setSession] = useState<OpenCodeSession | null>(null);

  useEffect(() => {
    let alive = true;
    const read = () => api.handoffSession(handoffId).then((s) => { if (alive) setSession(s); }).catch(() => undefined);
    read();
    const timer = window.setInterval(read, every);
    return () => { alive = false; window.clearInterval(timer); };
  }, [api, handoffId, every]);

  if (!session) return null;
  const status = STATUS[session.status];
  return (
    <div className="card card-body stack" style={{ gap: 6 }} aria-label="Sessione OpenCode">
      <div className="row">
        <strong>OpenCode</strong><span className={status.tone}>{status.label}</span>
        {session.title && <span className="muted" style={{ fontSize: 12 }}>{session.title}</span>}
        <span className="spacer" />
        {session.updatedAt && <span className="muted" style={{ fontSize: 12 }}>{when(session.updatedAt)}</span>}
      </div>
      {session.files > 0 && (
        <div style={{ fontSize: 12.5 }}>{session.files} file cambiati · +{session.additions} −{session.deletions}</div>
      )}
      {session.lastAnswer && (
        <div className="muted" style={{ fontSize: 12.5, whiteSpace: "pre-wrap", maxHeight: 180, overflow: "auto" }}>
          {session.lastAnswer}</div>
      )}
      {session.status === "UNREACHABLE" && (
        <div className="muted" style={{ fontSize: 12 }}>
          Si segue finché la finestra di OpenCode aperta dalla consegna resta aperta.</div>
      )}
    </div>
  );
}
