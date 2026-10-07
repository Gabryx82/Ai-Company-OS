import { afterEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { ControlPlane, type Session } from "../api/client";
import { ApiContext, SessionContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { storedTerminals, storeTerminals } from "../terminalStore";
import { Terminal } from "./Terminal";

// xterm.js needs a real browser; each tab is stood in for by a stub that shows
// what it was asked to do and lets the test play the server: the shell's id
// arriving with "ready", and its end with "exit".
interface Stub { shell: string; attach?: string; onSession?: (s: string) => void; onExit: (code: number | null) => void }
const stubs: Stub[] = [];
vi.mock("../components/TerminalTab", () => ({
  TerminalTab: (props: Stub) => {
    if (!stubs.includes(props)) stubs.push(props);
    return <div>{props.attach ? `attached ${props.attach}` : `new ${props.shell}`}</div>;
  },
}));

const ADMIN = { id: 1, username: "admin", displayName: "Administrator", role: "ADMIN" as const, enabled: true,
  mustChangePassword: false, lastLoginAt: null, passwordChangedAt: "2026-09-25T10:00:00Z", version: 0 };

afterEach(() => {
  stubs.length = 0;
  sessionStorage.clear();
});

function latest(shell: string): Stub {
  return [...stubs].reverse().find((s) => s.shell === shell)!;
}

function renderTerminal(sessions: unknown[] = []) {
  const onRunning = vi.fn();
  const { fetcher, calls } = fakeFetch({
    "GET /api/software": () => ({ body: [] }),
    "GET /api/projects": () => ({ body: [] }),
    "GET /api/terminal/shells": () => ({ body: [
      { key: "powershell", name: "PowerShell", available: true, detail: "powershell.exe" },
      { key: "claude-code", name: "Claude Code", available: true, detail: "claude.exe" },
    ] }),
    "GET /api/terminal/sessions": () => ({ body: sessions }),
    "DELETE /api/terminal/sessions/s-other": () => ({ status: 204 }),
    "DELETE /api/terminal/sessions/s-ps": () => ({ status: 204 }),
  });
  const session: Session = { baseUrl: "http://localhost:8081", token: "aicos_s_abc", user: ADMIN };
  render(
    <SessionContext.Provider value={session}>
      <ApiContext.Provider value={new ControlPlane(session, fetcher)}>
        <Terminal onRunning={onRunning} />
      </ApiContext.Provider>
    </SessionContext.Provider>,
  );
  return { onRunning, calls };
}

describe("the console's terminal (ADR-029 §5)", () => {
  it("counts the shells it shows and remembers them for a reload, until they end", async () => {
    const { onRunning } = renderTerminal();

    fireEvent.click(await screen.findByRole("button", { name: /PowerShell/ }));
    await screen.findByText("new powershell");
    expect(onRunning).toHaveBeenLastCalledWith(1);

    act(() => latest("powershell").onSession!("s-ps"));
    expect(storedTerminals()).toEqual([{ session: "s-ps", shell: "powershell", name: "PowerShell" }]);

    // The shell ends by itself: its tab stays, with its last output, and a reload does not bring it back.
    act(() => latest("powershell").onExit(0));
    expect(onRunning).toHaveBeenLastCalledWith(0);
    expect(storedTerminals()).toEqual([]);
    expect(screen.getByText("new powershell")).toBeTruthy();
  });

  it("after a reload shows again the shells this tab had", async () => {
    storeTerminals([{ session: "s-claude", shell: "claude-code", name: "Claude Code" }]);
    const { onRunning } = renderTerminal([{ id: "s-claude", shell: "claude-code", directory: "C:\\work",
      startedAt: "2026-10-07T18:00:00Z", attached: false, detachedAt: "2026-10-07T18:30:00Z" }]);

    await screen.findByText("attached s-claude");
    expect(onRunning).toHaveBeenLastCalledWith(1);
    // Shown here, so not offered "in background".
    expect(screen.queryByText("In background")).toBeNull();
  });

  it("offers the shells it does not show, to take back or to close", async () => {
    const { calls } = renderTerminal([{ id: "s-other", shell: "claude-code", directory: "C:\\work",
      startedAt: "2026-10-07T18:00:00Z", attached: false, detachedAt: "2026-10-07T18:30:00Z" }]);

    await screen.findByText("In background");
    fireEvent.click(screen.getByRole("button", { name: "Riprendi qui" }));
    await screen.findByText("attached s-other");
    expect(screen.queryByText("In background")).toBeNull();

    // Closing its tab ends the shell on the server.
    fireEvent.click(screen.getByRole("button", { name: /Chiudi Claude Code/ }));
    await vi.waitFor(() => expect(calls.some((c) => c.method === "DELETE" && c.path === "/api/terminal/sessions/s-other")).toBe(true));
  });

  it("closes a shell in background without showing it", async () => {
    const { calls } = renderTerminal([{ id: "s-other", shell: "powershell", directory: "C:\\work",
      startedAt: "2026-10-07T18:00:00Z", attached: true, detachedAt: null }]);

    await screen.findByText(/aperto in un'altra scheda del browser/);
    fireEvent.click(screen.getByRole("button", { name: "Chiudi" }));
    await vi.waitFor(() => expect(calls.some((c) => c.method === "DELETE" && c.path === "/api/terminal/sessions/s-other")).toBe(true));
    expect(stubs).toHaveLength(0);
  });
});
