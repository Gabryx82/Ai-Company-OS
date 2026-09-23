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
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  DONE: "Done",
  QUEUED: "Queued",
  RUNNING: "Running",
  SUCCEEDED: "Succeeded",
  FAILED: "Failed",
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
        <strong>{stale ? "Changed by someone else" : p?.title ?? "Something went wrong"}</strong>
        <span className="spacer" />
        {onDismiss && <button className="btn btn-small" onClick={onDismiss}>Dismiss</button>}
      </div>
      <div>{stale ? "The data was reloaded. Check it, then try again." : p?.detail || String(problem)}</div>
      {p && Object.keys(p.errors).length > 0 && (
        <ul>{Object.entries(p.errors).map(([field, message]) => <li key={field}><code>{field}</code>: {message}</li>)}</ul>
      )}
      {p && <div className="mono muted">{p.slug}</div>}
    </div>
  );
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="overlay center" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="row"><h2>{title}</h2><span className="spacer" /><button className="btn btn-small" onClick={onClose}>Close</button></div>
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
