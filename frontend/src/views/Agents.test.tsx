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
    expect(await within(dialog).findByText(/Mancano/)).toBeTruthy();
    expect(within(dialog).getByText(/Backend Engineer · presente/)).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Installa (1)" })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Installa tutti i mancanti" }));
    expect(await within(dialog).findByText(/Creati: Software Engineer/)).toBeTruthy();
    expect(calls.filter((c) => c.method === "POST").map((c) => c.path)).toEqual(["/api/agent-templates/install-all"]);
  });
});
