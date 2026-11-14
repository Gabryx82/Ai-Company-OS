// PHASE 37 (ADR-047): what the agent changed in the working folder since its task was handed
// to it, as a diff view shows it: the files added, modified and deleted with the lines added
// and removed, and the chosen file line by line. Read again every few seconds.
import { useEffect, useState } from "react";
import type { ChangeStatus, FileDiff, TaskChanges } from "../../api/types";
import { useApi } from "../../context";
import { ago } from "../dashboard/Widgets";

export const CHANGES_MS = 3000;

const LETTER: Record<ChangeStatus, string> = { ADDED: "A", MODIFIED: "M", DELETED: "D" };
const STATUS_TEXT: Record<ChangeStatus, string> = { ADDED: "aggiunto", MODIFIED: "modificato", DELETED: "eliminato" };

/** The changes of a task, read now and every few seconds; null until the first answer. */
export function useTaskChanges(taskId: number | null): { changes: TaskChanges | null; problem: unknown } {
  const api = useApi();
  const [changes, setChanges] = useState<TaskChanges | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  useEffect(() => {
    setChanges(null);
    setProblem(null);
    if (taskId === null) return;
    let alive = true;
    let last = "";
    const read = () => api.taskChanges(taskId).then((c) => {
      if (!alive) return;
      setProblem(null);
      const signature = JSON.stringify(c);
      if (signature !== last) { last = signature; setChanges(c); }
    }).catch((e) => { if (alive) setProblem(e); });
    read();
    const timer = window.setInterval(() => { if (!document.hidden) read(); }, CHANGES_MS);
    return () => { alive = false; window.clearInterval(timer); };
  }, [api, taskId]);
  return { changes, problem };
}

function split(path: string): { name: string; folder: string } {
  const at = path.lastIndexOf("/");
  return at < 0 ? { name: path, folder: "" } : { name: path.slice(at + 1), folder: path.slice(0, at) };
}

export function ChangesView({ taskId, changes }: { taskId: number; changes: TaskChanges }) {
  const api = useApi();
  const [picked, setPicked] = useState<string | null>(null);
  const [diff, setDiff] = useState<FileDiff | null>(null);
  const [failed, setFailed] = useState(false);

  // The file chosen, or the one touched last: the view follows the agent unless the operator picked one.
  const shown = changes.files.find((f) => f.path === picked) ?? changes.files[0] ?? null;
  const stamp = shown ? `${shown.path}|${shown.status}|${shown.additions}|${shown.deletions}|${shown.modifiedAt}` : null;
  useEffect(() => {
    if (!shown) { setDiff(null); return; }
    let alive = true;
    setFailed(false);
    api.changedFile(taskId, shown.path).then((d) => { if (alive) setDiff(d); })
      .catch(() => { if (alive) { setDiff(null); setFailed(true); } });
    return () => { alive = false; };
  }, [api, taskId, stamp]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="changes">
      <div className="changes-head">
        <strong>{changes.files.length === 0 ? "Nessuna modifica" : `${changes.files.length} ${changes.files.length === 1 ? "file cambiato" : "file cambiati"}`}</strong>
        {changes.files.length > 0 && <span className="changes-count"><span className="is-add">+{changes.additions}</span>
          <span className="is-del">−{changes.deletions}</span></span>}
        {changes.since && <span className="muted">dalla consegna, {ago(changes.since)}</span>}
        {changes.truncated && <span className="badge badge-warn" title="Oltre 4000 file o 64 MB di testo: il resto non è confrontato">
          cartella grande: confronto parziale</span>}
      </div>
      {changes.files.length === 0 ? (
        <p className="widget-note">L'agente non ha ancora cambiato file nella cartella da quando ha ricevuto la task.</p>
      ) : (
        <div className="changes-body">
          <div className="changes-files" role="listbox" aria-label="File cambiati">
            {picked && <button className="link-button changes-follow" onClick={() => setPicked(null)}>Segui l'ultimo</button>}
            {changes.files.map((f) => {
              const { name, folder } = split(f.path);
              const on = shown?.path === f.path;
              return (
                <button key={f.path} role="option" aria-selected={on} className={`changes-file${on ? " is-on" : ""}`}
                        onClick={() => setPicked(f.path)} title={`${f.path} — ${STATUS_TEXT[f.status]}`}>
                  <span className={`changes-letter is-${f.status.toLowerCase()}`} aria-label={STATUS_TEXT[f.status]}>{LETTER[f.status]}</span>
                  <span className="changes-name">{name}{folder && <small>{folder}</small>}</span>
                  {f.comparable
                    ? <span className="changes-count"><span className="is-add">+{f.additions}</span><span className="is-del">−{f.deletions}</span></span>
                    : <span className="muted changes-count">—</span>}
                </button>
              );
            })}
          </div>
          <div className="diff" aria-label={shown ? `Modifiche a ${shown.path}` : "Modifiche"}>
            {shown && <div className="diff-head"><span className="mono">{shown.path}</span>
              <span className={`changes-letter is-${shown.status.toLowerCase()}`}>{LETTER[shown.status]}</span>
              <span className="spacer" />{shown.modifiedAt && <span className="muted">{ago(shown.modifiedAt)}</span>}</div>}
            {failed && <p className="widget-note diff-note">Il confronto non si può leggere adesso.</p>}
            {diff && !diff.comparable && <p className="widget-note diff-note">{diff.note}</p>}
            {diff && diff.comparable && (
              <table className="diff-table">
                <tbody>
                  {diff.hunks.map((h, i) => (
                    <HunkRows key={i} hunk={h} />
                  ))}
                </tbody>
              </table>
            )}
            {!diff && !failed && shown && <p className="widget-note diff-note">Apro il confronto…</p>}
          </div>
        </div>
      )}
    </div>
  );
}

function HunkRows({ hunk }: { hunk: FileDiff["hunks"][number] }) {
  return (
    <>
      <tr className="diff-hunk"><td colSpan={4}>@@ −{hunk.oldStart},{hunk.oldLines} +{hunk.newStart},{hunk.newLines} @@</td></tr>
      {hunk.lines.map((l, i) => (
        <tr key={i} className={l.kind === "ADDED" ? "is-added" : l.kind === "REMOVED" ? "is-removed" : undefined}>
          <td className="diff-num">{l.oldNumber ?? ""}</td>
          <td className="diff-num">{l.newNumber ?? ""}</td>
          <td className="diff-sign" aria-hidden="true">{l.kind === "ADDED" ? "+" : l.kind === "REMOVED" ? "−" : ""}</td>
          <td className="diff-code"><code>{l.text}</code></td>
        </tr>
      ))}
    </>
  );
}
