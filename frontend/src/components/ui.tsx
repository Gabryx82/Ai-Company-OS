import { useEffect, type ReactNode } from "react";
import { ApiProblem } from "../api/client";
import type { RunStatus, TaskPriority, TaskStatus } from "../api/types";

const STATUS_TONE: Record<TaskStatus | RunStatus, string> = {
  OPEN: "badge",
  IN_PROGRESS: "badge badge-accent",
  DONE: "badge badge-ok",
  QUEUED: "badge",
  RUNNING: "badge badge-accent",
  SUCCEEDED: "badge badge-ok",
  FAILED: "badge badge-danger",
};

const STATUS_LABEL: Record<TaskStatus | RunStatus, string> = {
  OPEN: "Da fare",
  IN_PROGRESS: "In corso",
  DONE: "Completata",
  QUEUED: "In coda",
  RUNNING: "In esecuzione",
  SUCCEEDED: "Riuscita",
  FAILED: "Fallita",
};

export function StatusBadge({ status }: { status: TaskStatus | RunStatus }) {
  return <span className={STATUS_TONE[status]}>{STATUS_LABEL[status]}</span>;
}

export function PriorityBadge({ priority }: { priority: TaskPriority }) {
  const tone = priority === "HIGH" ? "badge badge-danger" : priority === "MEDIUM" ? "badge badge-warn" : "badge";
  return <span className={tone}>{priority.toLowerCase()}</span>;
}

/** Explains a refusal in words, and keeps its stable type visible for whoever debugs. */
export function ProblemNote({ problem, onDismiss }: { problem: unknown; onDismiss?: () => void }) {
  if (!problem) return null;
  const p = problem instanceof ApiProblem ? problem : null;
  const stale = p?.isStale;
  return (
    <div className={stale ? "notice notice-warn" : "notice notice-danger"} role="alert">
      <div className="row">
        <strong>{stale ? "Modificato da qualcun altro" : p?.title ?? "Qualcosa è andato storto"}</strong>
        <span className="spacer" />
        {onDismiss && <button className="btn btn-small" onClick={onDismiss}>Chiudi</button>}
      </div>
      <div>{stale ? "I dati sono stati ricaricati. Controllali e riprova." : p?.detail || String(problem)}</div>
      {p && Object.keys(p.errors).length > 0 && (
        <ul>{Object.entries(p.errors).map(([field, message]) => <li key={field}><code>{field}</code>: {message}</li>)}</ul>
      )}
      {p && <div className="mono muted">{p.slug}</div>}
    </div>
  );
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="overlay center" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title} style={wide ? { width: "min(860px, calc(100% - 32px))" } : undefined}>
        <div className="row"><h2>{title}</h2><span className="spacer" /><button className="btn btn-small" onClick={onClose}>Chiudi</button></div>
        {children}
      </div>
    </div>
  );
}

export function Field({ label, hint, error, children }: { label: string; hint?: string; error?: string; children: ReactNode }) {
  return (
    <div className="field">
      <label>{label}</label>
      {children}
      {hint && <span className="hint">{hint}</span>}
      {error && <span className="error">{error}</span>}
    </div>
  );
}

export function when(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}
