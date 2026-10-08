import { Fragment, useEffect, useState } from "react";
import { useApi, useIsAdmin } from "../../context";
import type { HubApp, HubCategory, HubFoundDetail, HubSkill } from "../../api/types";
import { Field, ProblemNote, when } from "../../components/ui";
import { Icon } from "../../components/icons";
import { CategoryPicker } from "./CategoryPicker";
import { COPY_LABEL, KIND_LABEL, kindOf, SCOPE_HINT, SCOPE_LABEL, slug } from "./hubModel";

/**
 * A skill found in an application (PHASE 31): what it is, where it lives, its
 * SKILL.md read-only -- the application owns it -- and what the Hub can do with
 * it: open its folder, bring it into the library, keep a Hub copy in step.
 */
export function FoundSkillDetail({ skill, apps, categories, onClose, onChanged }: {
  skill: HubSkill; apps: HubApp[]; categories?: HubCategory[]; onClose: () => void; onChanged: (message: string) => void;
}) {
  const api = useApi();
  const isAdmin = useIsAdmin();
  const [detail, setDetail] = useState<HubFoundDetail | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [key, setKey] = useState(slug(skill.name));
  const [busy, setBusy] = useState(false);
  const [showFiles, setShowFiles] = useState(false);
  const app = apps.find((a) => a.id === skill.app);
  const location = app?.locations.find((l) => l.id === skill.location);

  useEffect(() => {
    api.hubSkill(skill.id).then(setDetail).catch(setProblem);
  }, [api, skill.id]);

  async function run(action: () => Promise<string>) {
    setProblem(null);
    setBusy(true);
    try {
      onChanged(await action());
      // What the Hub just did changes what this skill is (imported, removed): read it again.
      await api.hubSkill(skill.id).then(setDetail).catch(() => setDetail(null));
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  const current = detail?.skill ?? skill;
  const copy = current.copyStatus ? COPY_LABEL[current.copyStatus] : null;
  const kind = kindOf(current);
  // Plugins and connectors stay their application's: they are shown, never imported or edited from here.
  const importable = kind === "SKILL" || kind === "KNOWLEDGE";
  // PHASE 32 (TD-84): a plugin built into Claude Code has no folder to open.
  const builtin = current.marketplace === "builtin";
  const details = Object.entries(current.details ?? {}).filter(([label]) => label !== "Cartella" || kind !== "PLUGIN");

  return (
    <div className="card side-panel">
      <div className="card-head">
        <span className="chip">{app?.name ?? skill.app}</span>
        {kind !== "SKILL" && <span className="chip">{KIND_LABEL[kind]}</span>}
        <h2>{skill.name}</h2><span className="spacer" />
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi"><Icon name="close" size={14} /></button>
      </div>
      <div className="card-body stack">
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {current.description && <div className="muted" style={{ whiteSpace: "pre-wrap" }}>{current.description}</div>}
        <dl className="hub-facts">
          <dt>Tipologia</dt><dd title={SCOPE_HINT[current.scope]}>{SCOPE_LABEL[current.scope]}{location ? ` · ${location.label}` : ""}</dd>
          <dt>Categoria</dt><dd>{categories && categories.length > 0
            ? <CategoryPicker skill={skill} categories={categories} onChanged={onChanged} /> : current.categoryLabel}</dd>
          {kind === "SKILL" && current.plugin && <><dt>Plugin</dt><dd>{current.plugin}{current.pluginVersion ? ` v${current.pluginVersion}` : ""}{current.marketplace ? ` · ${current.marketplace}` : ""}</dd></>}
          {kind === "SKILL" && current.version && <><dt>Versione</dt><dd>{current.version}</dd></>}
          {details.map(([label, value]) => <Fragment key={label}><dt>{label}</dt><dd>{value}</dd></Fragment>)}
          {current.enabled !== null && !current.details?.Stato && <><dt>Stato nell'app</dt><dd>{
            kind === "CONNECTOR" ? (current.enabled ? "attivo" : "disattivato") : (current.enabled ? "attiva" : "disattivata")}</dd></>}
          {current.modified && <><dt>Modificato</dt><dd>{when(current.modified)}</dd></>}
          {current.path && <><dt>File</dt><dd><code style={{ wordBreak: "break-all" }}>{current.path}</code></dd></>}
          {location && location.readers.length > 0 && <><dt>La leggono</dt><dd>{location.readers.map((r) => apps.find((a) => a.id === r)?.name ?? r).join(", ")}</dd></>}
          {app && app.agents.length > 0 && <><dt>Agenti</dt><dd>{app.agents.map((a) => a.name).join(", ")}</dd></>}
        </dl>

        {copy && (
          <div className="notice" style={{ fontSize: 12.5 }}>
            <span className={copy.tone}>copia del Hub · {copy.label}</span>{" "}
            Copia della skill <strong>{current.libraryKey}</strong> della libreria.
            {current.copyStatus === "OUTDATED" && " La libreria è più recente: aggiornala da Aggiornamenti o dalla skill in libreria."}
            {current.copyStatus === "MODIFIED" && " È stata modificata qui: puoi riportarla nella libreria da Aggiornamenti."}
            {current.copyStatus === "CONFLICT" && " È cambiata sia qui sia nella libreria: scegli quale tenere in Aggiornamenti."}
            {current.copyStatus === "ORPHAN" && " La libreria non ha più questa skill."}
            {isAdmin && current.libraryKey && (
              <div style={{ marginTop: 6 }}>
                <button className="btn btn-small" disabled={busy}
                        onClick={() => window.confirm(`Tolgo questa copia da ${app?.name ?? skill.app}? La cartella resta come backup.`)
                          && run(async () => (await api.hubRemoveCopy(current.libraryKey!, skill.app)).message ?? "Copia tolta")}>
                  <Icon name="trash" size={12} /> Togli dall'app</button>
              </div>
            )}
          </div>
        )}
        {!current.copyStatus && current.libraryKey && (
          <div className="notice" style={{ fontSize: 12.5 }}>
            <Icon name="check" size={12} /> Importata nella libreria come <strong>{current.libraryKey}</strong>: se cambia qui,
            Aggiornamenti te lo dice.
          </div>
        )}

        {kind !== "CONNECTOR" && !builtin && (
          <div className="row">
            <button className="btn" disabled={busy}
                    onClick={() => run(async () => `Aperta in VS Code: ${(await api.hubOpenSkill(skill.id)).folder}`)}>
              <Icon name="external" size={13} /> Apri cartella</button>
          </div>
        )}
        {kind === "CONNECTOR" && (
          <div className="muted" style={{ fontSize: 12.5 }}>
            Un connettore si configura e si autorizza nella sua app. Qui ne vedi nome, tipo e indirizzo: argomenti,
            variabili d'ambiente e token non vengono mai letti.
          </div>
        )}
        {builtin && (
          <div className="muted" style={{ fontSize: 12.5 }}>
            Un plugin interno arriva con Claude Code: non ha una cartella né un manifest, e si attiva o si spegne
            in Claude Code (<code>/plugin</code>).
          </div>
        )}
        {kind === "PLUGIN" && !builtin && (
          <div className="muted" style={{ fontSize: 12.5 }}>
            Il plugin resta dell'app: si installa, si attiva e si aggiorna lì. Le sue skill sono nel gruppo «Skill del
            plugin {current.name}» dell'elenco, e ognuna si può importare nella libreria.
          </div>
        )}

        {importable && !current.copyStatus && !current.libraryKey && (
          <div className="stack" style={{ gap: 6 }}>
            <div className="section-title">Nella libreria di AI Company OS</div>
            <div className="muted" style={{ fontSize: 12.5 }}>
              Copiala nella libreria per associarla agli agenti di AI Company OS, modificarla e installarla anche nelle
              altre app. L'originale resta dov'è; se cambia, te lo segnala Aggiornamenti.
            </div>
            <div className="row">
              <Field label="Chiave nella libreria"><input value={key} onChange={(e) => setKey(e.target.value)} style={{ width: 220 }} /></Field>
              <button className="btn btn-primary" disabled={busy || key.length < 2} style={{ alignSelf: "end" }}
                      onClick={() => run(async () => `Importata nella libreria: ${(await api.hubImportSkill(skill.id, key)).name}`)}>
                <Icon name="plus" size={13} /> Importa nella libreria</button>
            </div>
          </div>
        )}

        {detail && (
          <>
            {(detail.files ?? []).length > 1 && (
              <div>
                <button className="hub-group-head" aria-expanded={showFiles} onClick={() => setShowFiles(!showFiles)}>
                  <span className="hub-caret">{showFiles ? "▾" : "▸"}</span><strong>File della skill</strong>
                  <span className="chip">{(detail.files ?? []).length}</span>
                </button>
                {showFiles && <ul className="mono" style={{ fontSize: 12, margin: "4px 0 0", paddingLeft: 18 }}>
                  {(detail.files ?? []).map((f) => <li key={f.path}>{f.path} <span className="muted">({f.size} B)</span></li>)}
                </ul>}
              </div>
            )}
            <div className="section-title">{kind === "SKILL" ? "SKILL.md" : kind === "KNOWLEDGE" ? "Contenuto" : "Riepilogo"} (sola lettura)</div>
            <pre className="doc" aria-label="Contenuto della skill">{detail.content}{detail.truncated ? "\n…" : ""}</pre>
          </>
        )}
        {!detail && !problem && <div className="muted">Carico il file…</div>}
      </div>
    </div>
  );
}
