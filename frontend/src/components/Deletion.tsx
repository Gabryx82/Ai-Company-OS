import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import type { DeletionPreview } from "../api/types";
import { Modal, ProblemNote } from "./ui";
import { Icon } from "./icons";

/*
 * PHASE 30 (ADR-035 §3): one way to remove things across the console.
 *
 *  - <DeleteButton> is the trash icon, always the same, always at the end of the
 *    row, card or header it belongs to;
 *  - <ConfirmDeletion> is the dialog: what is deleted, what is detached and stays,
 *    what is kept, which files leave the disk -- read from the server's preview,
 *    the same rules the delete applies -- then Annulla / Elimina. A reversible
 *    alternative (Archivia, Disattiva) sits next to it when there is one, and a
 *    typed name is asked only for the heavy deletes;
 *  - <SelectionBar> and useSelection() are the multi-select of a list.
 *
 * The projects' and tasks' dialog of ADR-027 (DeleteDialog) keeps its own shape.
 */

export function DeleteButton({ label, onClick, disabled, title }: {
  label: string; onClick: () => void; disabled?: boolean; title?: string;
}) {
  return (
    <button type="button" className="btn btn-small btn-danger btn-icon" aria-label={label} title={title ?? label}
            onClick={(event) => { event.stopPropagation(); onClick(); }} disabled={disabled}>
      <Icon name="trash" size={14} />
    </button>
  );
}

/** The lines of a preview, grouped by what happens to them. */
export function DeletionSummary({ preview }: { preview: DeletionPreview }) {
  const group = (title: string, lines: string[], className?: string) => lines.length > 0 && (
    <div className={className}>
      <div className="section-title">{title}</div>
      <ul className="deletion-lines">{lines.map((line) => <li key={line}>{line}</li>)}</ul>
    </div>
  );
  return (
    <div className="stack" style={{ gap: 8 }}>
      {group("Viene eliminato", preview.deletes)}
      {group("Viene scollegato e resta", preview.detaches)}
      {group("Resta", preview.keeps)}
      {preview.files.length > 0 && (
        <div>
          <div className="section-title">File rimossi dal disco</div>
          <ul className="deletion-lines mono">{preview.files.map((f) => <li key={f}>{f}</li>)}</ul>
        </div>
      )}
      {preview.blockers.length > 0 && (
        <div className="notice notice-danger" role="alert">
          <strong><Icon name="warning" size={13} /> Non si può eliminare adesso</strong>
          <ul className="deletion-lines">{preview.blockers.map((b) => <li key={b}>{b}</li>)}</ul>
        </div>
      )}
    </div>
  );
}

/**
 * The confirmation dialog. `loadPreview` is called once per mount: a caller
 * whose options change what the preview says remounts it (key) or passes a new
 * function and lets the effect run again.
 */
export function ConfirmDeletion({ title, subject, intro, loadPreview, preview: given, onConfirm, onClose, confirmLabel,
                                  typedName, alternative, children }: {
  title: string;
  /** The name of what is deleted, as the user knows it. */
  subject: string;
  intro?: ReactNode;
  loadPreview?: () => Promise<DeletionPreview>;
  /** A preview computed by the caller, for the small deletes that need no round trip. */
  preview?: DeletionPreview;
  onConfirm: () => Promise<void>;
  onClose: () => void;
  confirmLabel?: string;
  /** Asks to type this name before deleting: for the deletes that take a lot with them. */
  typedName?: string;
  /** The reversible gesture, when there is one (Archivia, Disattiva). */
  alternative?: { label: string; hint: string; onClick: () => Promise<void> };
  /** Options that change what is deleted (sub-agents, work in progress). */
  children?: ReactNode;
}) {
  const [preview, setPreview] = useState<DeletionPreview | null>(given ?? null);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [typed, setTyped] = useState("");

  useEffect(() => {
    if (!loadPreview) return;
    let cancelled = false;
    setPreview(null);
    loadPreview().then((p) => { if (!cancelled) setPreview(p); }).catch((e) => { if (!cancelled) setProblem(e); });
    return () => { cancelled = true; };
  }, [loadPreview]);

  const matches = !typedName || typed.trim().toLowerCase() === typedName.trim().toLowerCase();
  const ready = !!preview && preview.allowed && matches && !busy;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!ready) return;
    setBusy(true);
    setProblem(null);
    try {
      await onConfirm();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  async function runAlternative() {
    if (!alternative) return;
    setBusy(true);
    setProblem(null);
    try {
      await alternative.onClick();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={title} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <div>{intro ?? <>Stai per eliminare definitivamente <strong>«{subject}»</strong>. Non si annulla.</>}</div>
        {alternative && (
          <div className="notice">
            <div className="row">
              <span style={{ flex: 1 }}>{alternative.hint}</span>
              <button type="button" className="btn btn-small" onClick={runAlternative} disabled={busy}>{alternative.label}</button>
            </div>
          </div>
        )}
        {children}
        {preview ? <DeletionSummary preview={preview} />
          : !problem && <div className="muted">Calcolo cosa verrebbe eliminato…</div>}
        {typedName && (
          <label className="field">
            <span className="field-label">Per confermare, scrivi il nome: <strong>{typedName}</strong></span>
            <input aria-label="Conferma nome" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
          </label>
        )}
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row">
          <span className="spacer" />
          <button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-danger btn-danger-solid" type="submit" disabled={!ready}>
            <Icon name="trash" size={13} /> {busy ? "Elimino…" : confirmLabel ?? "Elimina"}</button>
        </div>
      </form>
    </Modal>
  );
}

/** A preview for a delete whose effects the caller already knows (a few files, a link). */
export function simplePreview(kind: string, deletes: string[], files: string[] = [], keeps: string[] = []): DeletionPreview {
  return { kind, subjects: [], deletes, detaches: [], keeps, files, blockers: [], allowed: true };
}

/** The multi-select of a list: which keys are selected, and the gestures on them. */
export function useSelection<K>() {
  const [selected, setSelected] = useState<Set<K>>(new Set());
  const toggle = useCallback((key: K) => setSelected((current) => {
    const next = new Set(current);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  }), []);
  const clear = useCallback(() => setSelected(new Set()), []);
  const setAll = useCallback((keys: K[]) => setSelected(new Set(keys)), []);
  return { selected, toggle, clear, setAll, has: (key: K) => selected.has(key), size: selected.size };
}

/** What a list shows while items are selected: how many, and what can be done with them. */
export function SelectionBar({ count, noun, onDelete, onClear, onSelectAll, canDelete = true }: {
  count: number; noun: [string, string]; onDelete: () => void; onClear: () => void; onSelectAll?: () => void;
  canDelete?: boolean;
}) {
  return (
    <div className="selection-bar" role="toolbar" aria-label="Selezione">
      <strong>{count} {count === 1 ? noun[0] : noun[1]} selezionat{count === 1 ? "o" : "i"}</strong>
      {onSelectAll && <button type="button" className="btn btn-small" onClick={onSelectAll}>Seleziona tutto</button>}
      <button type="button" className="btn btn-small" onClick={onClear}>Annulla selezione</button>
      <span className="spacer" />
      {canDelete && (
        <button type="button" className="btn btn-small btn-danger btn-danger-solid" onClick={onDelete} disabled={count === 0}>
          <Icon name="trash" size={13} /> Elimina selezionati
        </button>
      )}
    </div>
  );
}
