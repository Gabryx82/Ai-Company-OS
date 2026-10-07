import { useEffect, useRef, useState } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { ApiProblem } from "../api/client";
import { useApi } from "../context";

/** Close codes of the terminal's socket (TerminalSocketHandler). */
const TAKEN_OVER = 4001;
const GONE = 4004;
const RETRIES = 5;

/**
 * One terminal in the page (ADR-029): a ticket asked over the authenticated
 * API, presented as the socket's first message; the pseudo-terminal's bytes go
 * straight to xterm.js, keystrokes and resizes go back as JSON frames.
 *
 * The shell lives on the server, not in this component (§5): unmounting closes
 * the socket and leaves the shell detached, and a socket that drops is attached
 * again. The shell ends when it exits, or when the console closes it on purpose
 * (`closeTerminalSession`). `attach` shows an existing shell instead of starting one.
 */
export function TerminalTab({ shell, projectId, attach, onSession, onExit }: {
  shell: string;
  projectId?: number;
  attach?: string;
  onSession?: (session: string) => void;
  onExit: (code: number | null) => void;
}) {
  const api = useApi();
  const host = useRef<HTMLDivElement | null>(null);
  // Kept across a remount (a hot reload while developing the console): it attaches again, it does not start anew.
  const session = useRef<string | null>(attach ?? null);
  const [state, setState] = useState<string>("connessione…");

  useEffect(() => {
    if (!host.current) return;
    const term = new Terminal({ cursorBlink: true, fontFamily: "Cascadia Code, Consolas, monospace", fontSize: 13,
      theme: { background: "#0b1222", foreground: "#e7ecf7" } });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(host.current);
    fit.fit();

    let socket: WebSocket | null = null;
    let disposed = false;
    let ended = false;
    let retries = 0;

    const end = (message: string, code: number | null) => {
      if (ended) return;
      ended = true;
      setState(message);
      onExit(code);
    };

    const retry = () => {
      retries += 1;
      setState("connessione persa: mi ricollego…");
      window.setTimeout(() => { if (!disposed && !ended) connect(); }, 1000 * retries);
    };

    function connect() {
      const known = session.current;
      (known ? api.terminalAttachTicket(known) : api.terminalTicket(shell, projectId)).then((ticket) => {
        if (disposed) return;
        const ws = new WebSocket(api.terminalSocketUrl());
        socket = ws;
        ws.binaryType = "arraybuffer";
        ws.onopen = () => {
          if (disposed) { ws.close(); return; }
          ws.send(JSON.stringify({ type: "auth", ticket: ticket.ticket, cols: term.cols, rows: term.rows }));
        };
        ws.onmessage = (event) => {
          if (typeof event.data !== "string") {
            term.write(new Uint8Array(event.data as ArrayBuffer));
            return;
          }
          const frame = JSON.parse(event.data) as { type: string; code?: number; directory?: string; message?: string;
            pty?: string; session?: string; attached?: boolean };
          if (frame.type === "ready") {
            retries = 0;
            // What the shell printed comes again right after: start from a clean screen.
            if (frame.attached) term.reset();
            if (frame.session) {
              session.current = frame.session;
              onSession?.(frame.session);
            }
            setState(`in ${frame.directory}`);
            if (frame.pty === "conpty") term.options.windowsPty = { backend: "conpty" };
          }
          if (frame.type === "exit") end(`terminato (codice ${frame.code})`, frame.code ?? null);
          if (frame.type === "error") { setState(frame.message ?? "errore"); term.write(`\r\n${frame.message}\r\n`); }
        };
        ws.onclose = (event) => {
          if (disposed || ended || socket !== ws) return;
          if (event.code === TAKEN_OVER) return end("aperto in un'altra scheda del browser", null);
          if (event.code === 1000 || event.code === GONE || !session.current || retries >= RETRIES) {
            return end(event.code === 1000 || event.code === GONE ? "terminato" : `chiuso: ${event.reason || event.code}`, null);
          }
          retry();
        };
      }).catch((error) => {
        if (disposed) return;
        const gone = error instanceof ApiProblem && error.status === 404;
        if (session.current && !gone && retries < RETRIES) return retry();
        end(gone ? "terminato" : error instanceof Error ? error.message : String(error), null);
      });
    }
    connect();

    const input = term.onData((data) => socket?.readyState === WebSocket.OPEN && socket.send(JSON.stringify({ type: "input", data })));
    const observer = new ResizeObserver(() => {
      fit.fit();
      if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "resize", cols: term.cols, rows: term.rows }));
    });
    observer.observe(host.current);

    return () => {
      disposed = true;
      observer.disconnect();
      input.dispose();
      socket?.close();
      term.dispose();
    };
  }, [api, shell, projectId]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="stack" style={{ gap: 4 }}>
      <div className="muted mono" style={{ fontSize: 11.5 }}>{state}</div>
      <div ref={host} className="terminal-host" />
    </div>
  );
}
