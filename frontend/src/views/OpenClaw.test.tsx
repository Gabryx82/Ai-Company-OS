import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane, type Session } from "../api/client";
import { ApiContext, SessionContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { OpenClaw } from "./OpenClaw";

const CONTROL_IDLE = { state: "IDLE", detail: null, at: null };

function overview(extra: Record<string, unknown> = {}) {
  return {
    availability: "AVAILABLE", detail: "OpenClaw configurato.", hint: null, gatewayUrl: "http://127.0.0.1:18789",
    agents: [
      { id: "coordinator", name: "Chief of Staff", isDefault: true, madeHere: false, aicosAgentId: null, aicosAgentName: null },
      { id: "aicos-writer", name: "Writer", isDefault: false, madeHere: true, aicosAgentId: 7, aicosAgentName: "Writer" },
    ],
    sessions: [
      { key: "agent:aicos-writer:aicos-task-42", agentId: "aicos-writer", label: null, kind: null, model: "gpt-6-astra",
        provider: "openai", updatedAt: new Date().toISOString(), totalTokens: 1200, taskId: 42, console: false },
    ],
    refreshedAt: new Date().toISOString(), refreshing: false, problem: null, controlUi: CONTROL_IDLE, ...extra,
  };
}

function mount(routes: Parameters<typeof fakeFetch>[0], role: "ADMIN" | "OPERATOR" = "ADMIN") {
  const { fetcher, calls } = fakeFetch({ "GET /api/openclaw/messages": () => ({ body: [] }), ...routes });
  const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
    user: { id: 1, username: "admin", displayName: null, role } as Session["user"] };
  const api = new ControlPlane(session, fetcher);
  const navigate = vi.fn();
  render(<SessionContext.Provider value={session}>
    <ApiContext.Provider value={api}><OpenClaw navigate={navigate} /></ApiContext.Provider>
  </SessionContext.Provider>);
  return { calls, navigate };
}

afterEach(() => vi.restoreAllMocks());

describe("OpenClaw nella console (ADR-045)", () => {
  it("shows the gateway, the agents and the sessions, and says why the Control UI opens outside", async () => {
    const { navigate } = mount({ "GET /api/openclaw": () => ({ body: overview() }) });

    expect(await screen.findByText("Gateway acceso")).toBeTruthy();
    expect(screen.getByText(/non si lascia mettere dentro un'altra pagina/)).toBeTruthy();
    const agents = screen.getByRole("region", { name: "Agenti di OpenClaw" });
    expect(within(agents).getByText("Chief of Staff")).toBeTruthy();
    expect(within(agents).getByText("predefinito")).toBeTruthy();
    expect(within(agents).getByText("da qui")).toBeTruthy();
    // The default agent is the one the conversation opens on.
    expect(screen.getByRole("region", { name: "Conversazione con Chief of Staff" })).toBeTruthy();
    // A session a handoff opened is named by its task.
    expect(screen.getByRole("row", { name: /Task #42/ })).toBeTruthy();

    fireEvent.click(within(agents).getByText("Writer"));
    fireEvent.click(await screen.findByText(/In AI Company OS: Writer/));
    expect(navigate).toHaveBeenCalledWith("agents", "7");
  });

  it("sends a message and shows the answer once OpenClaw has it", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let state = "RUNNING";
    let asks = 0;
    const message = (s: string) => ({ id: "m1", agentId: "coordinator", sessionKey: "agent:coordinator:aicos-console",
      message: "Riassumi il README", state: s, reply: s === "DONE" ? "Il README spiega l'avvio." : null, model: "gpt-6-astra",
      provider: "openai", tokens: s === "DONE" ? 321 : null, detail: null, startedAt: new Date().toISOString(), finishedAt: null,
      sentBy: "admin" });
    const { calls } = mount({
      "GET /api/openclaw": () => ({ body: overview() }),
      "POST /api/openclaw/messages": () => ({ status: 202, body: message("RUNNING") }),
      // Still working at the first ask: the page asks again.
      "GET /api/openclaw/messages/m1": () => { asks += 1; return { body: message(asks < 2 ? "RUNNING" : state) }; },
    });

    fireEvent.change(await screen.findByLabelText("Messaggio per Chief of Staff"), { target: { value: "Riassumi il README" } });
    fireEvent.click(screen.getByRole("button", { name: "Invia" }));

    expect(await screen.findByText("Chief of Staff sta lavorando…")).toBeTruthy();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({ agentId: "coordinator", message: "Riassumi il README" });
    state = "DONE";
    // The clock moves a second at a time until the page has asked twice: once still working, once with the answer.
    await waitFor(async () => {
      await vi.advanceTimersByTimeAsync(1000);
      expect(asks).toBeGreaterThanOrEqual(1);
    });
    expect(screen.getByText("Chief of Staff sta lavorando…")).toBeTruthy();
    await waitFor(async () => {
      await vi.advanceTimersByTimeAsync(1000);
      expect(asks).toBe(2);
    });
    expect(await screen.findByText("Il README spiega l'avvio.")).toBeTruthy();
    expect(screen.getByText("gpt-6-astra · 321 token")).toBeTruthy();
    vi.useRealTimers();
  });

  it("opens the Control UI through OpenClaw and a session in the terminal", async () => {
    const { calls } = mount({
      "GET /api/openclaw": () => ({ body: overview() }),
      "POST /api/openclaw/control-ui": () => ({ status: 202, body: { state: "OPENED", detail: "Aperta nel browser predefinito.", at: null } }),
      "POST /api/openclaw/terminal": () => ({ body: { sessionKey: "agent:aicos-writer:aicos-task-42", command: "wt openclaw tui" } }),
    });

    fireEvent.click(await screen.findByRole("button", { name: /Apri la Control UI/ }));
    expect(await screen.findByText("Aperta nel browser predefinito.")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Riprendi Task #42 nel terminale" }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/openclaw/terminal")).toBe(true));
    expect(calls.find((c) => c.path === "/api/openclaw/terminal")?.body).toEqual({ sessionKey: "agent:aicos-writer:aicos-task-42" });
  });

  it("with the gateway down says what to do, and an administrator can start it", async () => {
    const { calls } = mount({
      "GET /api/openclaw": () => ({ body: overview({ availability: "UNREACHABLE", detail: "Il gateway di OpenClaw è spento.",
        hint: "Avvialo con «openclaw daemon start».", agents: [], sessions: [], refreshedAt: null }) }),
      "POST /api/software/openclaw-gateway/launch": () => ({ body: { command: ["openclaw", "daemon", "start"] } }),
    });

    expect(await screen.findByText("Gateway spento", { selector: "h2" })).toBeTruthy();
    expect(screen.getByText(/openclaw daemon start/)).toBeTruthy();
    expect((screen.getByRole("button", { name: /Apri la Control UI/ }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: /Avvia il gateway/ }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path.startsWith("/api/software/openclaw-gateway"))).toBe(true));
  });

  it("lets an operator read and leaves acting to an administrator", async () => {
    mount({ "GET /api/openclaw": () => ({ body: overview() }) }, "OPERATOR");

    expect(await screen.findByText("Gateway acceso")).toBeTruthy();
    expect((screen.getByRole("button", { name: /Apri la Control UI/ }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByLabelText("Messaggio per Chief of Staff") as HTMLTextAreaElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Riprendi Task #42 nel terminale" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
