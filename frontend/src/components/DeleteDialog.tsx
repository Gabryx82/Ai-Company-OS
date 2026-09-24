import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import type { DeletionImpact } from "../api/types";
import { Modal, ProblemNote } from "./ui";
import { Icon } from "./icons";

/**
 * The one way the console deletes something for real (ADR-027): what goes
 * with it is shown first, archiving is offered as the reversible alternative,
 * and the name must be typed back -- the server checks the same thing.
 */
export function DeleteDialog({ what, name, loadImpact, onConfirm, onClose, archiveHint, children }: {
  what: string; name: string; loadImpact: () => Promise<DeletionImpact>;
  onConfirm: (confirmation: string) => Promise<void>; onClose: () => void; archiveHint?: string; children?: ReactNode;
}) {
  const [impact, setImpact] = useState<DeletionImpact | null>(null);
  const [typed, setTyped] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  // Once per dialog: a caller that changes what the preview depends on remounts it (key).
  useEffect(() => { loadImpact().then(setImpact).catch(setProblem); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const matches = typed.trim().toLowerCase() === name.trim().toLowerCase();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      await onConfirm(typed);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={`Elimina ${what}`} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <div className="notice notice-danger">
          <strong><Icon name="warning" size={13} /> Eliminazione definitiva.</strong> Non si annulla.
          {archiveHint && <div style={{ marginTop: 4 }}>{archiveHint}</div>}
        </div>
        {children}
        {impact ? (
          <div className="stack" style={{ gap: 4 }}>
            <strong>Cosa viene eliminato con «{impact.name}»</strong>
            <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13 }}>
              {impact.kind === "PROJECT" && <li>{impact.tasks} task · {impact.phases} fasi · {impact.planRuns} generazioni di piano · {impact.resources} risorse adottate</li>}
              <li>{impact.runs} esecuzioni · {impact.handoffs} handoff · {impact.reviews} review</li>
              {impact.notes.map((n) => <li key={n}>{n}</li>)}
            </ul>
            {impact.runInProgress && <div className="notice notice-warn">Un'esecuzione è in corso: attendi che finisca.</div>}
          </div>
        ) : <div className="muted">Calcolo cosa verrebbe eliminato…</div>}
        <label className="field">
          <span className="field-label">Per confermare, scrivi il nome: <strong>{name}</strong></span>
          <input aria-label="Conferma nome" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
        </label>
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        <div className="row">
          <span className="spacer" />
          <button className="btn" type="button" onClick={onClose}>Annulla</button>
          <button className="btn btn-danger" type="submit" disabled={!matches || busy || !impact || impact.runInProgress}>
            {busy ? "Elimino…" : `Elimina definitivamente`}</button>
        </div>
      </form>
    </Modal>
  );
}
