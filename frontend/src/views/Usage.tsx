import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { UsageWindow } from "../api/types";
import { Modal, ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

const STATUS: Record<UsageWindow["status"], { label: string; tone: string; hint: string }> = {
  MEASURED: { label: "Misurato", tone: "badge badge-ok", hint: "Registrato dal client del fornitore su questa macchina" },
  COUNTED: { label: "Contato", tone: "badge badge-accent", hint: "Token contati dai log locali; il limite non è registrato" },
  RESET_SINCE_OBSERVATION: { label: "Azzerato", tone: "badge", hint: "La finestra si è azzerata dopo l'ultima osservazione" },
  NO_DATA: { label: "Nessun dato", tone: "badge", hint: "Nessuna attività registrata in questa finestra" },
  CONFIGURATION_NEEDED: { label: "Da configurare", tone: "badge badge-warn", hint: "Indica quando si azzera la finestra" },
};
const WINDOW: Record<UsageWindow["windowKind"], string> = {
  ROLLING_5H: "5 ore", DAILY: "Giornaliera", WEEKLY: "Settimanale", MONTHLY: "Mensile",
};
const DAYS = ["", "Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica"];

function countdown(iso: string | null, now: number): string {
  if (!iso) return "—";
  const ms = new Date(iso).getTime() - now;
  if (ms <= 0) return "adesso";
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  return h >= 24 ? `${Math.floor(h / 24)}g ${h % 24}h` : `${h}h ${String(m).padStart(2, "0")}m`;
}

function tokens(n: number | null): string {
  if (n === null) return "—";
  return n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)} M` : n >= 1000 ? `${(n / 1000).toFixed(1)} k` : String(n);
}

/**
 * Consumption of the cloud models and when each window resets. A number is
 * shown only when something measured it: Codex records its own rate limits,
 * Claude Code records tokens, the control plane records its runs.
 */
export function Usage() {
  const api = useApi();
  const [windows, setWindows] = useState<UsageWindow[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [now, setNow] = useState(Date.now());
  const [editing, setEditing] = useState<UsageWindow | null>(null);

  const load = () => api.usage().then(setWindows).catch(setProblem);
  useEffect(() => { load(); }, [api]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    const tick = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(tick);
  }, []);

  const subjects = [...new Set((windows ?? []).map((w) => w.subject))];

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Consumi e quote</h1>
          <p>Uso dei modelli cloud e degli abbonamenti, e quando si azzerano le finestre giornaliere, di 5 ore e settimanali.</p>
        </div>
        <button className="btn" onClick={load}><Icon name="refresh" size={14} /> Aggiorna</button>
      </div>
      <ProblemNote problem={problem} />
      <div className="notice">
        Nessuna API pubblica espone le quote degli abbonamenti Claude o ChatGPT. Qui compaiono solo dati reali:
        i limiti che <strong>Codex</strong> registra nelle sue sessioni locali, i token che <strong>Claude Code</strong>
        registra per messaggio, e le run che AI Company OS ha inviato a provider a consumo.
      </div>

      <div className="grid grid-2">
        {subjects.map((subject) => (
          <div key={subject} className="card">
            <div className="card-head"><h2>{subject}</h2></div>
            {windows!.filter((w) => w.subject === subject).map((w) => (
              <div key={w.key} className="list-item" style={{ display: "grid", gap: 6 }}>
                <div className="row">
                  <strong>{WINDOW[w.windowKind]}</strong>
                  <span className={STATUS[w.status].tone} title={STATUS[w.status].hint}>{STATUS[w.status].label}</span>
                  <span className="spacer" />
                  <span className="muted"><Icon name="clock" size={13} /> reset tra </span>
                  <span className="countdown">{countdown(w.nextResetAt, now)}</span>
                </div>
                {w.usedPercent !== null && (
                  <div className="row">
                    <div className={w.usedPercent > 80 ? "progress warn" : "progress"} style={{ flex: 1 }}>
                      <span style={{ width: `${Math.min(100, w.usedPercent)}%` }} /></div>
                    <strong>{w.usedPercent.toFixed(0)}%</strong>
                  </div>
                )}
                {w.tokens !== null && (
                  <div className="muted" style={{ fontSize: 12.5 }}>
                    {tokens(w.tokens)} token{w.outputTokens !== null ? ` · ${tokens(w.outputTokens)} in uscita` : ""}
                    {w.byModel.length > 0 && <> · {w.byModel.map((m) => `${m.model}: ${tokens(m.tokens ?? null)}`).join(", ")}</>}
                  </div>
                )}
                {w.detail && <div className="muted" style={{ fontSize: 12 }}>{w.detail}</div>}
                <div className="row" style={{ fontSize: 12 }}>
                  {w.nextResetAt && <span className="muted">Prossimo reset: {new Date(w.nextResetAt).toLocaleString("it-IT")}</span>}
                  <span className="spacer" />
                  {(w.source === "CLAUDE_CODE_LOCAL" && w.windowKind === "WEEKLY" || w.source === "MANUAL"
                    || w.source === "ENGINE_RUNS") && (
                    <button className="btn btn-small" onClick={() => setEditing(w)}>Imposta reset</button>
                  )}
                  {w.usageUrl && <a className="btn btn-small" href={w.usageUrl} target="_blank" rel="noreferrer">Pagina ufficiale</a>}
                </div>
              </div>
            ))}
          </div>
        ))}
      </div>
      {editing && <AnchorEditor window={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />}
    </div>
  );
}

function AnchorEditor({ window: w, onClose, onSaved }: { window: UsageWindow; onClose: () => void; onSaved: () => void }) {
  const api = useApi();
  const [weekday, setWeekday] = useState<number | "">(w.resetWeekday ?? "");
  const [time, setTime] = useState(w.resetTime?.slice(0, 5) ?? "");
  const [zone, setZone] = useState(w.resetZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone);
  const [problem, setProblem] = useState<unknown>(null);

  const save = () => api.anchorQuota(w.key, `"${w.version}"`, {
    resetWeekday: weekday === "" ? null : weekday, resetTime: time || null, resetZone: zone || null, limitNote: w.limitNote,
  }).then(onSaved).catch(setProblem);

  return (
    <Modal title={`Reset — ${w.name}`} onClose={onClose}>
      <ProblemNote problem={problem} />
      {w.limitNote && <p className="muted" style={{ margin: 0 }}>{w.limitNote}</p>}
      {w.windowKind === "WEEKLY" && (
        <div className="field"><label>Giorno</label>
          <select value={weekday} onChange={(e) => setWeekday(e.target.value ? Number(e.target.value) : "")}>
            <option value="">—</option>
            {DAYS.slice(1).map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
          </select></div>
      )}
      <div className="field"><label>Ora</label><input type="time" value={time} onChange={(e) => setTime(e.target.value)} /></div>
      <div className="field"><label>Fuso orario</label><input value={zone} onChange={(e) => setZone(e.target.value)} /></div>
      <div className="row"><span className="spacer" /><button className="btn btn-primary" onClick={save}>Salva</button></div>
    </Modal>
  );
}
