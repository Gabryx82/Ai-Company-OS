import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { AgentEcosystem } from "./AgentEcosystem";

const TOOLS = [
  { key: "openclaw", name: "OpenClaw", shape: "un agente con il suo spazio di lavoro", availability: "AVAILABLE",
    detail: "OpenClaw configurato.", hint: null, imports: true },
  { key: "open-webui", name: "Open WebUI", shape: "un modello dell'area di lavoro", availability: "NOT_CONFIGURED",
    detail: "Manca la sua chiave API.", hint: "Aggiungi AICOS_OPENWEBUI_API_KEY a local.env.", imports: false },
  { key: "litellm", name: "LiteLLM", shape: "un alias del gateway", availability: "AVAILABLE", detail: "Gli alias si scrivono nel file.",
    hint: null, imports: false },
];

function overview(published: boolean) {
  return {
    tools: TOOLS,
    agents: [
      { agentId: 1, name: "Frontend Developer", role: "Sviluppatore frontend", cells: [
        { tool: "openclaw", state: published ? "IN_SYNC" : "NOT_PUBLISHED", externalId: published ? "aicos-frontend-developer" : null,
          detail: null, publishedAt: published ? "2026-11-10T10:00:00Z" : null },
        { tool: "open-webui", state: "UNAVAILABLE", externalId: null, detail: "Manca la sua chiave API.", publishedAt: null },
        { tool: "litellm", state: published ? "IN_SYNC" : "BEHIND", externalId: "aicos-frontend-developer",
          detail: published ? null : "L'agente è cambiato dopo l'ultima pubblicazione.", publishedAt: "2026-11-09T10:00:00Z" }] },
      { agentId: 2, name: "Architect", role: "Software Architect", cells: [
        { tool: "openclaw", state: "ADOPTED", externalId: "coordinator", detail: "È un agente di OpenClaw.", publishedAt: "2026-11-10T09:00:00Z" },
        { tool: "open-webui", state: "UNAVAILABLE", externalId: null, detail: "Manca la sua chiave API.", publishedAt: null },
        { tool: "litellm", state: "NOT_APPLICABLE", externalId: null, detail: "Il modello dell'agente non è un modello locale di Ollama.",
          publishedAt: null }] },
    ],
    importable: [{ tool: "openclaw", externalId: "researcher", name: "Researcher", description: null }],
    pending: published ? 0 : 2,
  };
}

function mount(routes: Parameters<typeof fakeFetch>[0], isAdmin = true) {
  const { fetcher, calls } = fakeFetch(routes);
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  const changed = vi.fn();
  const open = vi.fn();
  render(<ApiContext.Provider value={api}>
    <AgentEcosystem isAdmin={isAdmin} onAgentsChanged={changed} onOpenAgent={open} /></ApiContext.Provider>);
  return { calls, changed, open };
}

afterEach(() => vi.restoreAllMocks());

describe("Agenti — ecosistema (ADR-043)", () => {
  it("says how each agent stands in each tool and what a tool lacks, and writes nothing by itself", async () => {
    const { calls, open } = mount({ "GET /api/agent-sync": () => ({ body: overview(false) }) });

    expect(await screen.findByText("Un solo ecosistema")).toBeTruthy();
    // Each tool: what an agent is there, whether it can be written, and what to do when it cannot.
    expect(screen.getByText("Qui diventa un alias del gateway.")).toBeTruthy();
    expect(screen.getByText("da configurare")).toBeTruthy();
    expect(screen.getByText("Aggiungi AICOS_OPENWEBUI_API_KEY a local.env.", { exact: false })).toBeTruthy();
    // The matrix: one row per agent, one state per tool, and why where it is not plain.
    const frontend = screen.getByRole("row", { name: /Frontend Developer/ });
    expect(within(frontend).getByText("da pubblicare")).toBeTruthy();
    expect(within(frontend).getByText("da aggiornare")).toBeTruthy();
    expect(within(frontend).getByText("L'agente è cambiato dopo l'ultima pubblicazione.")).toBeTruthy();
    const architect = screen.getByRole("row", { name: /Architect/ });
    expect(within(architect).getByText("è suo")).toBeTruthy();
    expect(within(architect).getByText("non adatto")).toBeTruthy();
    expect(within(architect).queryByRole("button", { name: "Sincronizza" })).toBeNull();
    expect(screen.getByRole("button", { name: /Sincronizza tutto \(2\)/ })).toBeTruthy();
    fireEvent.click(within(frontend).getByRole("button", { name: "Frontend Developer" }));
    expect(open).toHaveBeenCalledWith(1);
    expect(calls.every((c) => c.method === "GET")).toBe(true);
  });

  it("brings every tool up to date at once and shows what each one answered", async () => {
    let published = false;
    const { calls } = mount({
      "GET /api/agent-sync": () => ({ body: overview(published) }),
      "POST /api/agent-sync/publish": () => { published = true;
        return { body: { written: 2, failed: 0, results: [
          { agentId: 1, agent: "Frontend Developer", tool: "openclaw", outcome: "PUBLISHED", externalId: "aicos-frontend-developer",
            detail: "Pubblicato in OpenClaw.", notes: ["Il modello resta quello che OpenClaw ha per i suoi agenti."] },
          { agentId: 1, agent: "Frontend Developer", tool: "litellm", outcome: "UPDATED", externalId: "aicos-frontend-developer",
            detail: "Aggiornato in LiteLLM.", notes: ["Il gateway legge il file all'avvio: riavvialo."] },
          { agentId: 2, agent: "Architect", tool: "openclaw", outcome: "SKIPPED", externalId: "coordinator",
            detail: "È un agente di OpenClaw: resta com'è.", notes: [] }] } }; },
    });

    fireEvent.click(await screen.findByRole("button", { name: /Sincronizza tutto \(2\)/ }));

    expect(await screen.findByText("Esito: 2 scritti")).toBeTruthy();
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({});
    expect(screen.getByText("Il gateway legge il file all'avvio: riavvialo.")).toBeTruthy();
    expect(screen.getByText(/resta com'è/)).toBeTruthy();
    // The state is read again from the tools, not assumed.
    expect(await screen.findByRole("button", { name: "Tutto allineato" })).toBeTruthy();
    expect(calls.filter((c) => c.path === "/api/agent-sync").length).toBe(2);
  });

  it("publishes one agent, takes one out after asking, and brings in an agent a tool already had", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const { calls, changed } = mount({
      "GET /api/agent-sync": () => ({ body: overview(false) }),
      "POST /api/agent-sync/publish": () => ({ body: { written: 1, failed: 0, results: [] } }),
      "DELETE /api/agent-sync/litellm/agents/1": () => ({ status: 204 }),
      "POST /api/agent-sync/import": () => ({ body: [{ agentId: 9, name: "Researcher", tool: "openclaw", externalId: "researcher" }] }),
    });

    const frontend = await screen.findByRole("row", { name: /Frontend Developer/ });
    fireEvent.click(within(frontend).getByRole("button", { name: "Sincronizza" }));
    await waitFor(() => expect(calls.some((c) => c.path === "/api/agent-sync/publish")).toBe(true));
    expect(calls.find((c) => c.path === "/api/agent-sync/publish")!.body).toEqual({ agentIds: [1] });

    fireEvent.click(await screen.findByRole("button", { name: "Togli Frontend Developer da LiteLLM" }));
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("viene rimosso"));
    await waitFor(() => expect(calls.some((c) => c.method === "DELETE")).toBe(true));
    // An agent the tool had before is unlinked, and the question says nothing is removed there.
    fireEvent.click(await screen.findByRole("button", { name: "Scollega Architect da OpenClaw" }));
    expect(confirm).toHaveBeenLastCalledWith(expect.stringContaining("resta tutto com'è"));

    fireEvent.click(await screen.findByRole("button", { name: "Porta qui" }));
    await waitFor(() => expect(changed).toHaveBeenCalled());
    expect(calls.find((c) => c.path === "/api/agent-sync/import")!.body).toEqual({ tool: "openclaw", externalIds: ["researcher"] });
    expect(await screen.findByText(/«Researcher» ora è anche un agente di AI Company OS/)).toBeTruthy();
  });

  it("an operator who is not an administrator sees everything and can write nothing", async () => {
    const { calls } = mount({ "GET /api/agent-sync": () => ({ body: overview(false) }) }, false);

    expect(await screen.findByText(/per scrivere negli strumenti serve un amministratore/)).toBeTruthy();
    expect((screen.getByRole("button", { name: /Sincronizza tutto/ }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Porta qui" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByRole("button", { name: /Togli Frontend Developer/ })).toBeNull();
    expect(calls.every((c) => c.method === "GET")).toBe(true);
  });

  it("a tool that cannot be asked is said, not hidden", async () => {
    mount({ "GET /api/agent-sync": () => ({ status: 502, problem: true,
      body: { type: "urn:ai-company-os:problem:sync-refused", title: "The tool refused", status: 502, detail: "OpenClaw non ha elencato i suoi agenti" } }) });

    expect(await screen.findByText(/OpenClaw non ha elencato i suoi agenti/)).toBeTruthy();
  });

  it("shows the agents at once while OpenClaw is being read, and fills it in when it is (ADR-046)", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let asks = 0;
    const reading = () => {
      const base = overview(false);
      return { ...base,
        tools: base.tools.map((t) => t.key === "openclaw" ? { ...t, refreshing: true, checkedAt: null } : { ...t, refreshing: false }),
        agents: base.agents.map((a) => ({ ...a, cells: a.cells.map((c) => c.tool === "openclaw"
          ? { ...c, state: "READING", detail: "Leggo gli agenti di OpenClaw: i suoi comandi impiegano fino a un minuto." } : c) })),
        importable: [] };
    };
    const read = () => {
      const base = overview(false);
      return { ...base, tools: base.tools.map((t) => ({ ...t, refreshing: false, checkedAt: "2026-11-12T09:30:00Z" })) };
    };
    mount({ "GET /api/agent-sync": () => { asks += 1; return { body: asks === 1 ? reading() : read() }; } });

    // The agents are there at once, the tool being read says so, and LiteLLM is not held up by it.
    const frontend = await screen.findByRole("row", { name: /Frontend Developer/ });
    expect(within(frontend).getByText("lettura…")).toBeTruthy();
    expect(within(frontend).getByText("da aggiornare")).toBeTruthy();
    expect(screen.getByText("Sto leggendo i suoi agenti…")).toBeTruthy();

    await waitFor(async () => {
      await vi.advanceTimersByTimeAsync(1000);
      expect(asks).toBe(2);
    });
    expect(await within(screen.getByRole("row", { name: /Frontend Developer/ })).findByText("da pubblicare")).toBeTruthy();
    expect(screen.queryByText("lettura…")).toBeNull();
    vi.useRealTimers();
  });
});
