import { useCallback, useEffect, useState } from "react";
import { useApi, useIsAdmin } from "../../context";
import type { HubCopyTarget } from "../../api/types";
import { ProblemNote, when } from "../../components/ui";
import { Icon } from "../../components/icons";
import { COPY_LABEL } from "./hubModel";

/**
 * Where a library skill is installed (PHASE 31): one row per application with a
 * personal skill folder -- install, bring up to date, take out (admin; the folder
 * is kept as a backup) -- and the .zip Claude Desktop uploads.
 */
export function CopiesSection({ skillKey, onChanged }: { skillKey: string; onChanged: (message: string) => void }) {
  const api = useApi();
  const isAdmin = useIsAdmin();
  const [targets, setTargets] = useState<HubCopyTarget[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api.hubCopies(skillKey).then(setTargets).catch(setProblem), [api, skillKey]);
  useEffect(() => { load(); }, [load]);

  async function run(action: () => Promise<string | null>) {
    setBusy(true);
    setProblem(null);
    try {
      const message = await action();
      if (message) onChanged(message);
      await load();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  async function download() {
    await run(async () => {
      const url = await api.hubPackageUrl(skillKey);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${skillKey}.zip`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
      return null;
    });
  }

  return (
    <div className="stack" style={{ gap: 8 }}>
      <div className="section-title">Nelle app</div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {!targets && !problem && <div className="muted">Carico…</div>}
      {(targets ?? []).map((t) => {
        const state = COPY_LABEL[t.status];
        return (
          <div key={t.app} className="row" style={{ fontSize: 12.5, opacity: t.available ? 1 : 0.55 }}>
            <strong style={{ minWidth: 150 }}>{t.appName}</strong>
            <span className={state.tone}>{t.available ? state.label : "app non trovata"}</span>
            {t.deployedAt && <span className="muted">dal {when(t.deployedAt)}</span>}
            <span className="spacer" />
            {t.available && t.status === "NONE" && (
              <button className="btn btn-small" disabled={busy}
                      onClick={() => run(async () => (await api.hubDeploy(skillKey, t.app)).message)}>Installa</button>
            )}
            {t.available && t.status === "OUTDATED" && (
              <button className="btn btn-small btn-primary" disabled={busy}
                      onClick={() => run(async () => (await api.hubDeploy(skillKey, t.app)).message)}>Aggiorna</button>
            )}
            {t.available && (t.status === "FOREIGN" || t.status === "MODIFIED" || t.status === "CONFLICT") && (
              <button className="btn btn-small" disabled={busy} title="La versione nell'app resta come backup"
                      onClick={() => window.confirm(`Sovrascrivo la cartella in ${t.appName}? Quella attuale resta come backup.`)
                        && run(async () => (await api.hubDeploy(skillKey, t.app, true)).message)}>Sovrascrivi</button>
            )}
            {isAdmin && t.available && t.status !== "NONE" && t.status !== "FOREIGN" && (
              <button className="btn btn-small" disabled={busy} aria-label={`Togli da ${t.appName}`} title="La cartella resta come backup"
                      onClick={() => window.confirm(`Tolgo «${skillKey}» da ${t.appName}? La cartella resta come backup.`)
                        && run(async () => (await api.hubRemoveCopy(skillKey, t.app)).message)}>
                <Icon name="trash" size={12} /></button>
            )}
          </div>
        );
      })}
      <div className="row" style={{ fontSize: 12.5 }}>
        <strong style={{ minWidth: 150 }}>Claude Desktop</strong>
        <span className="muted">le skill arrivano dal tuo account</span>
        <span className="spacer" />
        <button className="btn btn-small" disabled={busy} onClick={download}><Icon name="file" size={12} /> Scarica .zip</button>
      </div>
      <div className="muted" style={{ fontSize: 11.5 }}>
        Lo .zip si carica in Claude Desktop da Impostazioni → Capacità → Skill. Le copie nelle app restano sincronizzate:
        se la libreria cambia, Aggiornamenti propone di aggiornarle; se una copia cambia nell'app, di riportarla qui.
      </div>
    </div>
  );
}
