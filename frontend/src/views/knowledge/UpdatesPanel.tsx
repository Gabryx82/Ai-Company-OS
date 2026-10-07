import { useCallback, useEffect, useState } from "react";
import { useApi } from "../../context";
import type { HubApplied, HubApp, HubUpdateItem, HubUpdates, HubUpdateType } from "../../api/types";
import { ProblemNote, when } from "../../components/ui";
import { Icon } from "../../components/icons";

const SECTIONS: { type: HubUpdateType[]; title: string; hint: string }[] = [
  { type: ["MARKETPLACE"], title: "Dai marketplace", hint: "Skill installate da un marketplace con una versione nuova." },
  { type: ["SOURCE"], title: "Dalle app", hint: "Skill importate da un'app e cambiate lì dopo l'importazione." },
  { type: ["COPY", "PULL"], title: "Copie nelle app", hint: "Copie scritte dal Knowledge Hub: da aggiornare, o cambiate nell'app." },
  { type: ["PLUGIN"], title: "Plugin delle app", hint: "Plugin con una versione pubblicata più recente: li aggiorna l'app." },
];

/**
 * Everything that can be brought up to date (PHASE 31): checked on demand
 * against the marketplaces and the applications, applied one item at a time.
 * An item that would overwrite a change waits for an explicit confirmation,
 * and a backup is kept.
 */
export function UpdatesPanel({ apps, refreshKey, onApplied }: {
  apps: HubApp[]; refreshKey: number; onApplied: (message: string) => void;
}) {
  const api = useApi();
  const [updates, setUpdates] = useState<HubUpdates | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [results, setResults] = useState<Record<string, HubApplied>>({});
  const appName = (id: string) => id === "aicos" ? "Libreria" : apps.find((a) => a.id === id)?.name ?? id;

  const load = useCallback(() => api.hubUpdates().then(setUpdates).catch(setProblem), [api]);
  useEffect(() => { load(); }, [load, refreshKey]);

  async function check() {
    setBusy("check");
    setProblem(null);
    try {
      setUpdates(await api.hubCheckUpdates());
      setResults({});
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(null);
    }
  }

  async function apply(ids: string[], overwrite = false) {
    if (overwrite && !window.confirm("Sovrascrivo le modifiche? La versione attuale resta come backup in .aicos\\backups\\skills.")) return;
    setBusy(ids.join(","));
    setProblem(null);
    try {
      const { results: applied } = await api.hubApplyUpdates(ids, overwrite);
      setResults((r) => ({ ...r, ...Object.fromEntries(applied.map((a) => [a.id, a])) }));
      const done = applied.filter((a) => a.outcome === "UPDATED").length;
      if (done > 0) onApplied(`${done} aggiornamento${done === 1 ? "" : "i"} applicat${done === 1 ? "o" : "i"}`);
      await load();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(null);
    }
  }

  async function open(software: string) {
    try {
      await api.launch(software);
    } catch (error) {
      setProblem(error);
    }
  }

  const items = updates?.items ?? [];
  const safe = items.filter((i) => i.applicable && !i.needsOverwrite);

  return (
    <div className="stack">
      <div className="card card-body row">
        <div>
          <strong>Aggiornamenti</strong>
          <div className="muted" style={{ fontSize: 12.5 }}>
            Ultima verifica online: {updates?.checkedAt ? when(updates.checkedAt) : "mai"} · le copie nelle app si controllano a ogni sincronizzazione.
          </div>
        </div>
        <span className="spacer" />
        <button className="btn" onClick={check} disabled={busy !== null}><Icon name="refresh" size={14} /> {busy === "check" ? "Verifico…" : "Verifica ora"}</button>
        <button className="btn btn-primary" disabled={busy !== null || safe.length === 0} onClick={() => apply(safe.map((i) => i.id))}>
          Aggiorna tutto ({safe.length})</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {(updates?.errors ?? []).length > 0 && (
        <div className="notice notice-warn" style={{ fontSize: 12.5 }}>
          <strong>Non verificato:</strong>{(updates?.errors ?? []).map((e) => <div key={e}>{e}</div>)}
        </div>
      )}
      {updates && items.length === 0 && (
        <div className="card card-body"><Icon name="check" size={14} /> Tutto aggiornato{updates.checkedAt ? "" : " (premi «Verifica ora» per chiedere ai marketplace)"}.</div>
      )}
      {SECTIONS.map((section) => {
        const list = items.filter((i) => section.type.includes(i.type));
        if (list.length === 0) return null;
        return (
          <div key={section.title} className="card">
            <div className="card-head"><h2>{section.title}</h2><span className="chip">{list.length}</span>
              <span className="muted" style={{ fontSize: 12 }}>{section.hint}</span></div>
            <div className="card-body stack" style={{ gap: 0 }}>
              {list.map((item) => (
                <UpdateRow key={item.id} item={item} appName={appName(item.app)} result={results[item.id]}
                           busy={busy !== null} onApply={(overwrite) => apply([item.id], overwrite)}
                           onOpen={() => item.software && open(item.software)} />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function UpdateRow({ item, appName, result, busy, onApply, onOpen }: {
  item: HubUpdateItem; appName: string; result?: HubApplied; busy: boolean;
  onApply: (overwrite: boolean) => void; onOpen: () => void;
}) {
  const verb = item.type === "PULL" ? "Riporta nella libreria" : item.type === "COPY" ? "Aggiorna la copia" : "Aggiorna";
  return (
    <div className="hub-update-row">
      <div className="stack" style={{ gap: 2 }}>
        <div className="row" style={{ gap: 6 }}>
          <strong>{item.name}</strong>
          <span className="chip">{appName}</span>
          {item.key && item.app !== "aicos" && <span className="chip">libreria: {item.key}</span>}
          {(item.from || item.to) && <span className="mono" style={{ fontSize: 12 }}>{item.from ?? "?"} → {item.to ?? "nuova"}</span>}
          {item.needsOverwrite && <span className="badge badge-warn">sovrascrive modifiche</span>}
        </div>
        {item.detail && <span className="muted" style={{ fontSize: 12.5 }}>{item.detail}</span>}
        {item.command && <code style={{ fontSize: 12 }}>{item.command}</code>}
        {result && (
          <span className={result.outcome === "UPDATED" ? "badge badge-ok" : result.outcome === "CONFLICT" ? "badge badge-warn" : "badge badge-danger"}
                style={{ justifySelf: "start" }}>{result.message ?? result.outcome}</span>
        )}
      </div>
      <div className="row" style={{ justifyContent: "flex-end" }}>
        {item.applicable && !item.needsOverwrite && <button className="btn btn-small btn-primary" disabled={busy} onClick={() => onApply(false)}>{verb}</button>}
        {item.applicable && item.needsOverwrite && <button className="btn btn-small" disabled={busy} onClick={() => onApply(true)}>{verb} e sovrascrivi</button>}
        {!item.applicable && item.software && <button className="btn btn-small" onClick={onOpen}><Icon name="external" size={12} /> Apri {appName}</button>}
        {item.command && <button className="btn btn-small" onClick={() => navigator.clipboard?.writeText(item.command ?? "")}>Copia comando</button>}
      </div>
    </div>
  );
}
