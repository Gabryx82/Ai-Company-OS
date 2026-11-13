import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import { ApiProblem } from "../api/client";
import type { AssistantTurn } from "../api/types";
import { useApi } from "../context";
import { Icon } from "./icons";

type Message = AssistantTurn & { model?: string; failed?: boolean };

const KEY = "aicos.assistant";

function stored(): Message[] {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Message[]) : [];
  } catch {
    return [];
  }
}

/** The little Markdown local models write in a short answer: **bold** and `code`; the rest stays text. */
export function inline(text: string): ReactNode[] {
  return text.split(/(\*\*[^*\n]+\*\*|`[^`\n]+`)/).map((part, i) => {
    if (part.startsWith("**") && part.endsWith("**") && part.length > 4) return <strong key={i}>{part.slice(2, -2)}</strong>;
    if (part.startsWith("`") && part.endsWith("`") && part.length > 2) return <code key={i}>{part.slice(1, -1)}</code>;
    return part;
  });
}

/**
 * Quick questions about the ecosystem, answered by a local model from the control plane's
 * own state. The conversation lives in this tab only; the last turns go back with each question.
 */
export function AssistantChat({ onClose, onThinking, suggestions }: {
  onClose: () => void; onThinking: (thinking: boolean) => void; suggestions: string[];
}) {
  const api = useApi();
  const [messages, setMessages] = useState<Message[]>(stored);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const list = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    input.current?.focus();
    const onKey = (event: globalThis.KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => { window.removeEventListener("keydown", onKey); alive.current = false; onThinking(false); };
  }, [onClose, onThinking]);
  useEffect(() => {
    try { sessionStorage.setItem(KEY, JSON.stringify(messages.slice(-30))); } catch { /* kept for this view only */ }
    list.current?.scrollTo?.({ top: list.current.scrollHeight, behavior: "smooth" });
  }, [messages, busy]);

  async function ask(question: string) {
    const text = question.trim();
    if (!text || busy) return;
    const history = messages.filter((m) => !m.failed).slice(-10).map(({ speaker, text: t }) => ({ speaker, text: t }));
    setMessages((m) => [...m, { speaker: "USER", text }]);
    setDraft("");
    setBusy(true);
    onThinking(true);
    try {
      const answer = await api.askAssistant(text, history);
      if (alive.current) setMessages((m) => [...m, { speaker: "ASSISTANT", text: answer.answer, model: answer.model }]);
    } catch (error) {
      const detail = error instanceof ApiProblem ? error.detail || error.title : "Il server non risponde.";
      const missing = error instanceof ApiProblem && error.status === 404;
      if (alive.current) setMessages((m) => [...m, { speaker: "ASSISTANT", failed: true,
        text: missing ? "L'assistente richiede la versione aggiornata del server: riavvia AI Company OS e riprova." : detail }]);
    } finally {
      if (alive.current) { setBusy(false); onThinking(false); }
    }
  }

  const submit = (event: FormEvent) => { event.preventDefault(); void ask(draft); };
  const onKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void ask(draft); }
  };
  const lastModel = [...messages].reverse().find((m) => m.model)?.model;

  return (
    <section className="assistant-chat" role="dialog" aria-label="Assistente">
      <header className="assistant-head">
        <div>
          <strong>Assistente</strong>
          <small>{lastModel ? `Risponde ${lastModel}, sul tuo computer` : "Domande veloci sul tuo ecosistema"}</small>
        </div>
        <span className="spacer" />
        {messages.length > 0 && (
          <button className="btn btn-small btn-ghost" onClick={() => setMessages([])} disabled={busy}>Nuova chat</button>
        )}
        <button className="icon-button" onClick={onClose} aria-label="Chiudi l'assistente"><Icon name="close" size={16} /></button>
      </header>
      <div className="assistant-log" ref={list} aria-live="polite">
        {messages.length === 0 && (
          <div className="assistant-empty">
            <p>Chiedimi dello stato di progetti, task e agenti. Rispondo con i dati della console.</p>
            <div className="assistant-suggestions">
              {suggestions.map((s) => <button key={s} className="chip-button" onClick={() => void ask(s)}>{s}</button>)}
            </div>
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`assistant-bubble ${m.speaker === "USER" ? "is-you" : "is-assistant"}${m.failed ? " is-failed" : ""}`}>
            {m.speaker === "ASSISTANT" ? inline(m.text) : m.text}
          </div>
        ))}
        {busy && <div className="assistant-bubble is-assistant is-thinking" aria-label="Sto pensando"><i /><i /><i /></div>}
      </div>
      <form className="assistant-compose" onSubmit={submit}>
        <textarea ref={input} rows={1} value={draft} maxLength={2000} placeholder="Scrivi una domanda…"
                  onChange={(e) => setDraft(e.target.value)} onKeyDown={onKey} aria-label="La tua domanda" />
        <button className="btn btn-primary" type="submit" disabled={busy || !draft.trim()} aria-label="Invia">
          <Icon name="arrow" size={15} />
        </button>
      </form>
    </section>
  );
}
