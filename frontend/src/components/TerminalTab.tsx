import { useEffect, useRef, useState } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { useApi } from "../context";

/**
 * One terminal in the page (ADR-029): a ticket asked over the authenticated
 * API, presented as the socket's first message; the pseudo-terminal's bytes go
 * straight to xterm.js, keystrokes and resizes go back as JSON frames.
 */
export function TerminalTab({ shell, projectId, onExit }: { shell: string; projectId?: number; onExit: (code: number | null) => void }) {
  const api = useApi();
  const host = useRef<HTMLDivElement | null>(null);
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
    let closed = false;
    api.terminalTicket(shell, projectId).then((ticket) => {
      if (closed) return;
      socket = new WebSocket(api.terminalSocketUrl());
      socket.binaryType = "arraybuffer";
      socket.onopen = () => socket!.send(JSON.stringify({ type: "auth", ticket: ticket.ticket, cols: term.cols, rows: term.rows }));
      socket.onmessage = (event) => {
        if (typeof event.data !== "string") {
          term.write(new Uint8Array(event.data as ArrayBuffer));
          return;
        }
        const frame = JSON.parse(event.data) as { type: string; code?: number; directory?: string; message?: string };
        if (frame.type === "ready") setState(`in ${frame.directory}`);
        if (frame.type === "exit") { setState(`terminato (codice ${frame.code})`); onExit(frame.code ?? null); }
        if (frame.type === "error") { setState(frame.message ?? "errore"); term.write(`\r\n${frame.message}\r\n`); }
      };
      socket.onclose = (event) => { if (event.code !== 1000) setState(`chiuso: ${event.reason || event.code}`); };
    }).catch((error) => setState(error instanceof Error ? error.message : String(error)));

    const input = term.onData((data) => socket?.readyState === WebSocket.OPEN && socket.send(JSON.stringify({ type: "input", data })));
    const observer = new ResizeObserver(() => {
      fit.fit();
      if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "resize", cols: term.cols, rows: term.rows }));
    });
    observer.observe(host.current);

    return () => {
      closed = true;
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
