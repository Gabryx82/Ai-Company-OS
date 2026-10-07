import { useEffect, useState } from "react";
import { useApi } from "../../context";
import type { HubApp, HubSyncEvent } from "../../api/types";
import { ProblemNote, when } from "../../components/ui";

const CHANGE: Record<HubSyncEvent["change"], { label: string; tone: string }> = {
  ADDED: { label: "installata", tone: "badge badge-ok" },
  REMOVED: { label: "rimossa", tone: "badge badge-danger" },
  MODIFIED: { label: "modificata", tone: "badge badge-accent" },
};

/** What the synchronisations saw change in the applications (PHASE 31): installs, removals, edits, plugin updates. */
export function ActivityPanel({ apps, refreshKey }: { apps: HubApp[]; refreshKey: number }) {
  const api = useApi();
  const [events, setEvents] = useState<HubSyncEvent[] | null>(null);
  const [app, setApp] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const appName = (id: string) => apps.find((a) => a.id === id)?.name ?? id;

  useEffect(() => {
    api.hubEvents(300).then(setEvents).catch(setProblem);
  }, [api, refreshKey]);

  const shown = (events ?? []).filter((e) => !app || e.app === app);
  const present = [...new Set((events ?? []).map((e) => e.app))];

  return (
    <div className="stack">
      <div className="card card-body row">
        <div>
          <strong>Attività</strong>
          <div className="muted" style={{ fontSize: 12.5 }}>
            Ogni sincronizzazione confronta le app con la precedente: qui ciò che è stato installato, rimosso o modificato,
            anche fuori da AI Company OS.
          </div>
        </div>
        <span className="spacer" />
        <select aria-label="Filtra per app" value={app} onChange={(e) => setApp(e.target.value)}>
          <option value="">Tutte le app</option>
          {present.map((a) => <option key={a} value={a}>{appName(a)}</option>)}
        </select>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {events && shown.length === 0 && (
        <div className="card card-body muted">Nessuna modifica registrata. La prima sincronizzazione fotografa lo stato; le successive registrano i cambiamenti.</div>
      )}
      {shown.length > 0 && (
        <div className="card">
          <table className="hub-table">
            <thead><tr><th>Quando</th><th>Cosa</th><th>Skill</th><th>App</th><th>Dettaglio</th></tr></thead>
            <tbody>
              {shown.map((e) => (
                <tr key={e.id}>
                  <td className="muted">{when(e.at)}</td>
                  <td><span className={CHANGE[e.change].tone}>{CHANGE[e.change].label}</span></td>
                  <td><strong>{e.name}</strong><div className="mono muted" style={{ fontSize: 11 }}>{e.path}</div></td>
                  <td>{appName(e.app)}</td>
                  <td className="muted">{e.detail ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
