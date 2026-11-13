import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane, type Session } from "../api/client";
import type { AgentConfiguration } from "../api/types";
import { ApiContext, SessionContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { Agents } from "./Agents";

const BINDING = {
  model: null, provider: null,
  target: { key: "engine", name: "AI Engine", delivery: null, software: null, availability: null, providers: [],
    contextFile: null, howItWorks: null },
  valid: true, problems: [], warnings: [], summary: "AI Engine",
};

function agent(id: number, name: string, extra: Partial<AgentConfiguration> = {}): AgentConfiguration {
  return {
    id, name, role: "Backend Engineer", specialization: "Java", description: null, capabilities: [], status: "ACTIVE",
    parent: null, children: [], domain: null, systemPrompt: null, responsibilities: null, limits: null, outputFormat: null,
    directives: [], contextPolicy: null, resources: {}, software: [], model: null, executionTarget: "engine", binding: BINDING,
    origin: "TEMPLATE", defaults: null, modified: [], customizedAt: null, customizedBy: null, version: id * 10, ...extra,
  };
}

const PREVIEW = {
  kind: "AGENT", subjects: [{ id: "1", name: "Backend Bot" }], deletes: ["L'agente e la sua configurazione."],
  detaches: ["1 task da fare restano, senza agente: si riassegnano quando vuoi."], keeps: [], files: [], blockers: [], allowed: true,
};

function renderAgents(routes: Parameters<typeof fakeFetch>[0], role: "ADMIN" | "OPERATOR" = "ADMIN") {
  const { fetcher, calls } = fakeFetch(routes);
  const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
    user: { id: 1, username: "admin", displayName: null, role } as Session["user"] };
  const api = new ControlPlane(session, fetcher);
  render(
    <SessionContext.Provider value={session}>
      <ApiContext.Provider value={api}><Agents /></ApiContext.Provider>
    </SessionContext.Provider>,
  );
  return calls;
}

describe("Agents — deleting (ADR-035 §2)", () => {
  it("deletes one agent from its editor, under its tag, and the list follows at once", async () => {
    let list = [agent(1, "Backend Bot"), agent(2, "Frontend Bot")];
    const calls = renderAgents({
      "GET /api/ecosystem/bindings": () => ({ body: list }),
      "GET /api/agents/1/configuration": () => ({ body: list[0], etag: '"10"' }),
      "GET /api/agents/1/deletion": () => ({ body: PREVIEW }),
      "DELETE /api/agents/1": () => { list = list.filter((a) => a.id !== 1); return { status: 204 }; },
    });

    fireEvent.click(await screen.findByRole("button", { name: /Backend Bot/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Elimina Backend Bot" }));

    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText(/1 task da fare restano/)).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Disattiva" })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: /Elimina definitivamente/ }));

    expect(await screen.findByText("Eliminato «Backend Bot».")).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("button", { name: /Backend Bot/ })).toBeNull());
    expect(screen.getByRole("button", { name: /Frontend Bot/ })).toBeTruthy();
    const del = calls.find((c) => c.method === "DELETE")!;
    expect(del.path).toBe("/api/agents/1?subagents=DETACH&inProgress=BLOCK");
    expect(del.headers["If-Match"]).toBe('"10"');
  });

  it("cancelling leaves everything as it was", async () => {
    const calls = renderAgents({
      "GET /api/ecosystem/bindings": () => ({ body: [agent(1, "Backend Bot")] }),
      "GET /api/agents/1/configuration": () => ({ body: agent(1, "Backend Bot"), etag: '"10"' }),
      "GET /api/agents/1/deletion": () => ({ body: PREVIEW }),
    });
    fireEvent.click(await screen.findByRole("button", { name: /Backend Bot/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Elimina Backend Bot" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Annulla" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
    expect(screen.getByRole("button", { name: "Elimina Backend Bot" })).toBeTruthy();
  });

  it("a refusal is explained and nothing disappears", async () => {
    renderAgents({
      "GET /api/ecosystem/bindings": () => ({ body: [agent(1, "Backend Bot")] }),
      "GET /api/agents/1/configuration": () => ({ body: agent(1, "Backend Bot"), etag: '"10"' }),
      "GET /api/agents/1/deletion": () => ({ body: PREVIEW }),
      "DELETE /api/agents/1": () => ({ status: 409, problem: true, body: {
        type: "urn:ai-company-os:problem:deletion-blocked", title: "Deletion blocked",
        detail: "«Backend Bot» ha un'esecuzione in corso sulla task #4: attendi che finisca." } }),
    });
    fireEvent.click(await screen.findByRole("button", { name: /Backend Bot/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Elimina Backend Bot" }));
    const dialog = await screen.findByRole("dialog");
    await within(dialog).findByText(/1 task da fare/);
    fireEvent.click(within(dialog).getByRole("button", { name: /Elimina definitivamente/ }));
    expect(await within(dialog).findByText(/attendi che finisca/)).toBeTruthy();
    expect(screen.getAllByRole("button", { name: /Backend Bot/ }).length).toBeGreaterThan(0);
  });

  it("deletes a selection with the version each agent was read at", async () => {
    let list = [agent(1, "Alpha"), agent(2, "Beta"), agent(3, "Gamma")];
    const calls = renderAgents({
      "GET /api/ecosystem/bindings": () => ({ body: list }),
      "GET /api/agents/deletion": () => ({ body: { ...PREVIEW, subjects: [{ id: "1", name: "Alpha" }, { id: "3", name: "Gamma" }] } }),
      "POST /api/agents/bulk-delete": () => { list = list.filter((a) => a.id === 2); return { status: 204 }; },
    });
    fireEvent.click(await screen.findByRole("button", { name: /Seleziona/ }));
    fireEvent.click(screen.getByRole("button", { name: /Alpha/ }));
    fireEvent.click(screen.getByRole("button", { name: /Gamma/ }));
    expect(screen.getByText("2 agenti selezionati")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Elimina selezionati/ }));

    const dialog = await screen.findByRole("dialog");
    await within(dialog).findByText(/1 task da fare/);
    fireEvent.click(within(dialog).getByRole("button", { name: /Elimina definitivamente/ }));

    expect(await screen.findByText("Eliminati 2 agenti.")).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("button", { name: /Alpha/ })).toBeNull());
    const bulk = calls.find((c) => c.method === "POST")!;
    expect(bulk.body).toEqual({ agents: [{ id: 1, version: 10 }, { id: 3, version: 30 }], subagents: "DETACH", inProgress: "BLOCK" });
    expect(calls.find((c) => c.path.startsWith("/api/agents/deletion"))!.path).toContain("ids=1,3");
  });

  it("an operator does not see the delete", async () => {
    renderAgents({
      "GET /api/ecosystem/bindings": () => ({ body: [agent(1, "Backend Bot")] }),
      "GET /api/agents/1/configuration": () => ({ body: agent(1, "Backend Bot"), etag: '"10"' }),
    }, "OPERATOR");
    fireEvent.click(await screen.findByRole("button", { name: /Backend Bot/ }));
    await screen.findByRole("button", { name: "Salva configurazione" });
    expect(screen.queryByRole("button", { name: "Elimina Backend Bot" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Seleziona/ })).toBeNull();
  });

  it("the template install shows what would be created and never what is already there", async () => {
    const calls = renderAgents({
      "GET /api/ecosystem/bindings": () => ({ body: [] }),
      "GET /api/agent-templates": () => ({ body: [{ key: "software-engineer", name: "Software Engineer", role: "Software Engineer",
        specialization: "Code", model: "ollama:qwen3.5:9b", children: [] }] }),
      "GET /api/agent-templates/plan": () => ({ body: [
        { templateKey: "software-engineer", name: "Software Engineer", role: "Software Engineer", parentName: null, outcome: "CREATED" },
        { templateKey: "software-engineer", name: "Backend Engineer", role: "Backend Engineer", parentName: "Software Engineer", outcome: "EXISTING" },
      ] }),
      "POST /api/agent-templates/install-all": () => ({ body: [
        { name: "Software Engineer", agentId: 9, parentId: null, outcome: "CREATED" },
        { name: "Backend Engineer", agentId: 4, parentId: 9, outcome: "EXISTING" }] }),
    });
    fireEvent.click(await screen.findByRole("button", { name: /Da template/ }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText(/agente del set di template: Software Engineer/)).toBeTruthy();
    expect(within(dialog).getByText(/Backend Engineer · presente/)).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Installa (1)" })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Installa tutti i mancanti" }));
    expect(await within(dialog).findByText(/Creati: Software Engineer/)).toBeTruthy();
    expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual(["/api/agent-templates/install-all"]);
  });
  it("an agent deleted meanwhile by someone else leaves the list with a word, not an error", async () => {
    let list = [agent(1, "Backend Bot")];
    renderAgents({
      "GET /api/ecosystem/bindings": () => ({ body: list }),
      "GET /api/agents/1/configuration": () => ({ body: agent(1, "Backend Bot"), etag: '"10"' }),
      "GET /api/agents/1/deletion": () => ({ body: PREVIEW }),
      "DELETE /api/agents/1": () => {
        list = [];
        return { status: 404, problem: true, body: { type: "urn:ai-company-os:problem:agent-not-found",
          title: "Agent not found", detail: "No agent with id 1" } };
      },
    });
    fireEvent.click(await screen.findByRole("button", { name: /Backend Bot/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Elimina Backend Bot" }));
    const dialog = await screen.findByRole("dialog");
    await within(dialog).findByText(/1 task da fare/);
    fireEvent.click(within(dialog).getByRole("button", { name: /Elimina definitivamente/ }));

    expect(await screen.findByText(/«Backend Bot» non c'era più/)).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("button", { name: /Backend Bot/ })).toBeNull());
  });
});

describe("Agents — every field saved from the page is read back (PHASE 36)", () => {
  const TARGETS = [
    { key: "engine", name: "AI Engine", software: null, delivery: "ENGINE_RUN", providers: ["ollama"], contextFile: null,
      howItWorks: "Run nell'engine.", link: null },
    { key: "opencode", name: "OpenCode (CLI)", software: "opencode", delivery: "CLI_PROMPT", providers: ["ollama"],
      contextFile: "AGENTS.md", howItWorks: "Si apre nel terminale.", link: null },
  ];
  const MODELS = [{ key: "ollama:qwen3.5:9b", providerKey: "ollama", displayName: "Qwen 3.5 9B", role: "GENERAL",
    capabilities: [], lifecycle: "CURRENT", version: 0 }];
  const RESOURCES = [
    { key: "p36-skill", kind: "SKILL", name: "Catena Vincoli", description: null, configuration: null },
    { key: "p36-knowledge", kind: "KNOWLEDGE", name: "Catena Glossario", description: null, configuration: null },
    { key: "p36-tool", kind: "TOOL", name: "Catena Profiler", description: null, configuration: "profiler --all" },
    { key: "p36-mcp", kind: "MCP", name: "Catena MCP", description: null, configuration: "npx -y @catena/mcp" },
  ];
  const SOFTWARE = [{ key: "docker-desktop", name: "Docker Desktop", role: "Container", launchKind: "EXECUTABLE",
    availability: "INSTALLED" }];

  /** A server that keeps what it is sent, and answers as the control plane does: lists for line fields. */
  function statefulServer() {
    const boss = agent(2, "Capo Bot", { origin: "USER" });
    let stored = agent(1, "Backend Bot", { origin: "USER" });
    const lines = (text: unknown) => String(text ?? "").split("\n").map((l) => l.trim()).filter(Boolean);
    const link = (kind: string, key: string, on: boolean) => {
      const r = RESOURCES.find((x) => x.key === key)!;
      const now = (stored.resources[kind] ?? []).filter((x) => x.key !== key);
      stored = { ...stored, resources: { ...stored.resources, [kind]: on ? [...now, r] : now } };
      return { status: 204 };
    };
    const routes: Parameters<typeof fakeFetch>[0] = {
      "GET /api/ecosystem/bindings": () => ({ body: [stored, boss] }),
      "GET /api/agents/1/configuration": () => ({ body: stored, etag: `"${stored.version}"` }),
      "PUT /api/agents/1/configuration": (call) => {
        const b = call.body as Record<string, unknown>;
        stored = {
          ...stored, name: String(b.name), role: String(b.role), specialization: String(b.specialization),
          description: (b.description as string | null) ?? null, capabilities: lines(b.capabilities),
          parent: b.parentId === 2 ? { id: 2, name: "Capo Bot", role: "Backend Engineer" } : null,
          domain: (b.domain as string | null) ?? null, systemPrompt: (b.systemPrompt as string | null) ?? null,
          responsibilities: (b.responsibilities as string | null) ?? null, limits: (b.limits as string | null) ?? null,
          outputFormat: (b.outputFormat as string | null) ?? null, directives: lines(b.directives),
          contextPolicy: (b.contextPolicy as string | null) ?? null, model: (b.model as string | null) ?? null,
          executionTarget: String(b.executionTarget), version: stored.version + 1,
        };
        return { body: stored, etag: `"${stored.version}"` };
      },
      "GET /api/execution-targets": () => ({ body: TARGETS }),
      "GET /api/execution-targets/engine/check": () => ({ body: BINDING }),
      "GET /api/execution-targets/opencode/check": () => ({ body: BINDING }),
      "GET /api/catalog/models": () => ({ body: { models: MODELS, uncatalogued: [], engineReachable: true, engineDefault: null } }),
      "GET /api/catalog/providers": () => ({ body: [{ key: "ollama", name: "Ollama", kind: "LOCAL", billing: "FREE", status: "ACTIVE" }] }),
      "GET /api/resources": () => ({ body: RESOURCES }),
      "GET /api/software": () => ({ body: SOFTWARE }),
    };
    for (const r of RESOURCES) {
      routes[`PUT /api/agents/1/resources/${r.key}`] = () => link(r.kind, r.key, true);
    }
    routes["PUT /api/agents/1/software/docker-desktop"] = () => {
      stored = { ...stored, software: [{ key: "docker-desktop", name: "Docker Desktop" }] };
      return { status: 204 };
    };
    return routes;
  }

  const TEXT: [label: string, value: string][] = [
    ["Ruolo", "Database Engineer"], ["Specializzazione", "PostgreSQL"], ["Descrizione", "Disegna lo schema."],
    ["Capability", "migrazioni\nindici"], ["System prompt", "Sei il custode dello schema."],
    ["Responsabilità", "Le migrazioni del progetto."], ["Direttive", "Prima il test\nPoi la migrazione"],
    ["Limiti", "Mai DROP senza conferma."], ["Output atteso", "Una migrazione e il suo test."],
    ["Politica di contesto", "Leggi prima docs/adr."], ["Dominio", "dati"],
  ];
  const TAB_OF: Record<string, string> = {
    Ruolo: "Identità", Specializzazione: "Identità", Descrizione: "Identità", Capability: "Identità",
    "System prompt": "Prompt e direttive", Responsabilità: "Prompt e direttive", Direttive: "Prompt e direttive",
    Limiti: "Prompt e direttive", "Output atteso": "Prompt e direttive", "Politica di contesto": "Prompt e direttive",
    Dominio: "Prompt e direttive",
  };

  it("saves every field of the page in one write, and the page reopened shows them all", async () => {
    const calls = renderAgents(statefulServer());
    fireEvent.click(await screen.findByRole("button", { name: /Backend Bot/ }));
    await screen.findByRole("button", { name: "Salva configurazione" });

    // Identity and prompt.
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Schema Bot" } });
    for (const [label, value] of TEXT) {
      fireEvent.click(screen.getByRole("button", { name: TAB_OF[label] }));
      fireEvent.change(screen.getByLabelText(label), { target: { value } });
    }
    fireEvent.click(screen.getByRole("button", { name: "Identità" }));
    fireEvent.change(screen.getByLabelText("Sottoagente di"), { target: { value: "2" } });
    // Model and execution target.
    fireEvent.click(screen.getByRole("button", { name: "Modello ed esecuzione" }));
    fireEvent.click(await screen.findByRole("button", { name: /OpenCode \(CLI\)/ }));
    fireEvent.change(await screen.findByLabelText("Modello"), { target: { value: "ollama:qwen3.5:9b" } });

    const save = screen.getByRole("button", { name: "Salva configurazione" });
    await waitFor(() => expect((save as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(save);
    expect(await screen.findByText("Salvato")).toBeTruthy();

    const write = calls.find((c) => c.method === "PUT" && c.path === "/api/agents/1/configuration")!;
    expect(write.headers["If-Match"]).toBe('"10"');
    expect(write.body).toEqual({
      name: "Schema Bot", role: "Database Engineer", specialization: "PostgreSQL", description: "Disegna lo schema.",
      capabilities: "migrazioni\nindici", parentId: 2, domain: "dati", systemPrompt: "Sei il custode dello schema.",
      responsibilities: "Le migrazioni del progetto.", limits: "Mai DROP senza conferma.",
      outputFormat: "Una migrazione e il suo test.", directives: "Prima il test\nPoi la migrazione",
      contextPolicy: "Leggi prima docs/adr.", model: "ollama:qwen3.5:9b", executionTarget: "opencode",
    });

    // Harness and software: each box is its own write.
    fireEvent.click(screen.getByRole("button", { name: "Skill e strumenti" }));
    for (const r of RESOURCES) {
      fireEvent.click(await screen.findByRole("checkbox", { name: new RegExp(r.name) }));
      await waitFor(() => expect(calls.some((c) => c.method === "PUT" && c.path === `/api/agents/1/resources/${r.key}`)).toBe(true));
    }
    fireEvent.click(screen.getByRole("button", { name: "Software" }));
    fireEvent.click(await screen.findByRole("checkbox", { name: /Docker Desktop/ }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT" && c.path === "/api/agents/1/software/docker-desktop")).toBe(true));

    // Closed and opened again: everything comes from a new read.
    fireEvent.click(screen.getByRole("button", { name: "Chiudi" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Chiudi" })).toBeNull());
    const reads = calls.filter((c) => c.method === "GET" && c.path === "/api/agents/1/configuration").length;
    fireEvent.click(await screen.findByRole("button", { name: /Schema Bot/ }));
    await screen.findByRole("button", { name: "Salva configurazione" });
    expect(calls.filter((c) => c.method === "GET" && c.path === "/api/agents/1/configuration").length).toBeGreaterThan(reads);

    expect((screen.getByLabelText("Nome") as HTMLInputElement).value).toBe("Schema Bot");
    for (const [label, value] of TEXT) {
      fireEvent.click(screen.getByRole("button", { name: TAB_OF[label] }));
      expect((screen.getByLabelText(label) as HTMLInputElement).value).toBe(value);
    }
    fireEvent.click(screen.getByRole("button", { name: "Identità" }));
    expect((screen.getByLabelText("Sottoagente di") as HTMLSelectElement).value).toBe("2");
    fireEvent.click(screen.getByRole("button", { name: "Modello ed esecuzione" }));
    expect(screen.getByRole("button", { name: /OpenCode \(CLI\)/ }).getAttribute("aria-pressed")).toBe("true");
    expect((screen.getByLabelText("Modello") as HTMLSelectElement).value).toBe("ollama:qwen3.5:9b");
    fireEvent.click(screen.getByRole("button", { name: "Skill e strumenti" }));
    for (const r of RESOURCES) {
      expect((screen.getByRole("checkbox", { name: new RegExp(r.name) }) as HTMLInputElement).checked).toBe(true);
    }
    fireEvent.click(screen.getByRole("button", { name: "Software" }));
    expect((screen.getByRole("checkbox", { name: /Docker Desktop/ }) as HTMLInputElement).checked).toBe(true);
  });
});

describe("Agents — opened from a link (ADR-037)", () => {
  it("#/agents/{id} opens that agent's editor; without it, none", async () => {
    const { fetcher } = fakeFetch({
      "GET /api/ecosystem/bindings": () => ({ body: [agent(1, "Backend Bot"), agent(2, "Frontend Bot")] }),
      "GET /api/agents/2/configuration": () => ({ body: agent(2, "Frontend Bot"), etag: '"20"' }),
    });
    const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
      user: { id: 1, username: "admin", displayName: null, role: "ADMIN" } as Session["user"] };
    const view = render(
      <SessionContext.Provider value={session}>
        <ApiContext.Provider value={new ControlPlane(session, fetcher)}><Agents selected="2" /></ApiContext.Provider>
      </SessionContext.Provider>,
    );
    expect(await screen.findByRole("heading", { name: "Frontend Bot" })).toBeTruthy();
    view.unmount();

    render(
      <SessionContext.Provider value={session}>
        <ApiContext.Provider value={new ControlPlane(session, fetcher)}><Agents selected="non-un-numero" /></ApiContext.Provider>
      </SessionContext.Provider>,
    );
    await screen.findByRole("button", { name: /Backend Bot/ });
    expect(screen.queryByRole("heading", { name: "Frontend Bot" })).toBeNull();
  });
});

describe("Agents — what the library proposes, and the ecosystem (PHASE 37)", () => {
  const RESOURCE = { id: 4, key: "react-typescript", kind: "SKILL", name: "React + TypeScript", description: "Componenti e hook.",
    tags: ["react"], sourceUrl: null, searchUrl: null, configuration: null, origin: "CATALOG", fileBacked: true, filePath: null, version: 0 };

  it("the editor proposes the skills that suit the agent and attaches one on a click", async () => {
    let attached = false;
    const { fetcher, calls } = fakeFetch({
      "GET /api/ecosystem/bindings": () => ({ body: [agent(2, "Frontend Bot")] }),
      "GET /api/agents/2/configuration": () => ({ body: agent(2, "Frontend Bot"), etag: attached ? '"21"' : '"20"' }),
      "GET /api/agents/2/resource-suggestions": () => ({ body: attached ? [] : [RESOURCE] }),
      "PUT /api/agents/2/resources/react-typescript": () => { attached = true; return { status: 204 }; },
    });
    const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
      user: { id: 1, username: "admin", displayName: null, role: "ADMIN" } as Session["user"] };
    render(
      <SessionContext.Provider value={session}>
        <ApiContext.Provider value={new ControlPlane(session, fetcher)}><Agents selected="2" /></ApiContext.Provider>
      </SessionContext.Provider>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "Skill e strumenti" }));
    const proposed = await screen.findByLabelText("Consigliate per questo agente");
    // Proposed, not attached: nothing is written until the operator chooses.
    expect(calls.some((c) => c.method === "PUT")).toBe(false);
    fireEvent.click(within(proposed).getByRole("button", { name: /React \+ TypeScript/ }));

    await waitFor(() => expect(calls.some((c) => c.method === "PUT" && c.path === "/api/agents/2/resources/react-typescript")).toBe(true));
    // Read again after the change: what the agent carries is no longer proposed.
    await waitFor(() => expect(screen.queryByLabelText("Consigliate per questo agente")).toBeNull());
  });

  it("#/agents/ecosystem is the agents in the tools around AI Company OS, one tab away from the list", async () => {
    const { fetcher } = fakeFetch({
      "GET /api/ecosystem/bindings": () => ({ body: [agent(1, "Backend Bot")] }),
      "GET /api/agent-sync": () => ({ body: { tools: [{ key: "openclaw", name: "OpenClaw", shape: "un agente con il suo spazio di lavoro",
        availability: "AVAILABLE", detail: "OpenClaw configurato.", hint: null, imports: true }], agents: [], importable: [], pending: 0 } }),
    });
    const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
      user: { id: 1, username: "admin", displayName: null, role: "ADMIN" } as Session["user"] };
    const visited: (string | undefined)[] = [];
    render(
      <SessionContext.Provider value={session}>
        <ApiContext.Provider value={new ControlPlane(session, fetcher)}>
          <Agents selected="ecosystem" navigate={(_page, detail) => visited.push(detail)} /></ApiContext.Provider>
      </SessionContext.Provider>,
    );

    expect(await screen.findByText("Un solo ecosistema")).toBeTruthy();
    expect(screen.getByRole("tab", { name: /Ecosistema/ }).getAttribute("aria-selected")).toBe("true");
    // The list's own actions belong to the list.
    expect(screen.queryByRole("button", { name: /Nuovo agente/ })).toBeNull();
    fireEvent.click(screen.getByRole("tab", { name: "I tuoi agenti" }));
    expect(visited).toEqual([undefined]);
  });
});
