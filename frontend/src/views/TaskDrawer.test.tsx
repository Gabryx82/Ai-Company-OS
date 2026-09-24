import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import type { Agent } from "../api/types";
import { fakeFetch } from "../test/fakeServer";
import { TaskDrawer } from "./TaskDrawer";

const AGENT: Agent = {
  id: 1, name: "Code Architect", role: "Software Engineer", specialization: "Backend architecture",
  model: "ollama:llama3.2:3b", active: true, status: "ACTIVE", createdAt: null, updatedAt: null,
};
const TASK = { id: 7, title: "Design the planner", description: "Three endpoints", status: "OPEN", priority: "HIGH",
  projectId: null, agentId: 1 };

function renderDrawer(routes: Parameters<typeof fakeFetch>[0]) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/tasks/7/runs": () => ({ body: [] }),
    "GET /api/tasks/7/agent-suggestions": () => ({ body: [] }),
    "GET /api/engine/models": () => ({ body: { defaultModel: "echo:default", models: [] } }),
    ...routes,
  });
  const api = new ControlPlane({ baseUrl: "http://localhost:8080", token: "t0123456789abcdef" }, fetcher);
  render(
    <ApiContext.Provider value={api}>
      <TaskDrawer taskId={7} agents={[AGENT]} projects={[]} onClose={vi.fn()} onChanged={vi.fn()} />
    </ApiContext.Provider>,
  );
  return calls;
}

describe("TaskDrawer", () => {
  it("offers only the edges that leave the current state (ADR-014)", async () => {
    renderDrawer({ "GET /api/tasks/7": () => ({ body: TASK, etag: '"0"' }) });
    await screen.findByRole("heading", { name: "Design the planner" });
    expect(screen.getByRole("button", { name: "Avvia" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Completa" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Riapri" })).toBeNull();
  });

  it("acts with the tag it was read at", async () => {
    const calls = renderDrawer({
      "GET /api/tasks/7": () => ({ body: TASK, etag: '"0"' }),
      "POST /api/tasks/7/start": () => ({ body: { ...TASK, status: "IN_PROGRESS" }, etag: '"1"' }),
    });
    fireEvent.click(await screen.findByRole("button", { name: "Avvia" }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST")).toBe(true));
    const start = calls.find((c) => c.method === "POST")!;
    expect(start.path).toBe("/api/tasks/7/start");
    expect(start.headers["If-Match"]).toBe('"0"');
  });

  it("says so, and re-reads, when somebody else wrote first -- it does not retry with the new tag", async () => {
    let reads = 0;
    const calls = renderDrawer({
      "GET /api/tasks/7": () => ({ body: { ...TASK, title: reads++ === 0 ? "Design the planner" : "Renamed meanwhile" },
        etag: reads === 1 ? '"0"' : '"5"' }),
      "POST /api/tasks/7/start": () => ({
        status: 412, problem: true,
        body: { type: "urn:ai-company-os:problem:precondition-failed", title: "Precondition failed", status: 412 },
      }),
    });
    fireEvent.click(await screen.findByRole("button", { name: "Avvia" }));
    await screen.findByText("Modificato da qualcun altro");
    await screen.findByRole("heading", { name: "Renamed meanwhile" });
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(1);
  });

  it("launches a run with the agent's model unless another is chosen, using the task's tag", async () => {
    const calls = renderDrawer({
      "GET /api/tasks/7": () => ({ body: TASK, etag: '"2"' }),
      "POST /api/tasks/7/runs": () => ({ status: 202, body: { id: 1, status: "QUEUED" } }),
    });
    fireEvent.click(await screen.findByRole("button", { name: "Esegui con Code Architect" }));
    await waitFor(() => expect(calls.some((c) => c.path === "/api/tasks/7/runs" && c.method === "POST")).toBe(true));
    const launch = calls.find((c) => c.method === "POST")!;
    expect(launch.headers["If-Match"]).toBe('"2"');
    expect(launch.body).toEqual({});
  });

  it("does not offer a run for a task nobody holds", async () => {
    renderDrawer({ "GET /api/tasks/7": () => ({ body: { ...TASK, agentId: null }, etag: '"0"' }) });
    await screen.findByText("Assegna prima un agente.");
    expect((screen.getByRole("button", { name: /Esegui con/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("shows a finished run's output and a failed run's type", async () => {
    renderDrawer({
      "GET /api/tasks/7": () => ({ body: { ...TASK, status: "IN_PROGRESS" }, etag: '"3"' }),
      "GET /api/tasks/7/runs": () => ({ body: [
        { id: 2, taskId: 7, agentId: 1, status: "FAILED", failureType: "urn:ai-company-os:engine:problem:unknown-model",
          failureDetail: "Ollama has no model 'nope'", systemPrompt: "s", userPrompt: "u", correlationId: "run-2",
          requestedBy: "operator", createdAt: "2026-09-23T10:00:00Z" },
        { id: 1, taskId: 7, agentId: 1, status: "SUCCEEDED", output: "The plan: three endpoints", finishReason: "stop",
          servedModel: "ollama:llama3.2:3b", inputTokens: 10, outputTokens: 20, latencyMs: 1500,
          systemPrompt: "s", userPrompt: "u", correlationId: "run-1", requestedBy: "operator", createdAt: "2026-09-23T09:00:00Z" },
      ] }),
    });
    await screen.findByText("The plan: three endpoints");
    expect(screen.getByText("urn:ai-company-os:engine:problem:unknown-model")).toBeTruthy();
  });

  it("assigns through a card that says what, to whom, with which model and through what (directive §1)", async () => {
    const binding = (id: number, name: string, role: string, model: string, provider: string, target: string) => ({
      id, name, role, specialization: "", description: `${name} descritto`, capabilities: [], status: "ACTIVE", parent: null,
      children: [], domain: null, systemPrompt: null, responsibilities: null, limits: null, outputFormat: null,
      directives: [], contextPolicy: null, resources: {}, software: [], model, executionTarget: target === "AI Engine" ? "engine" : "claude-code",
      origin: "USER", defaults: null, modified: [], customizedAt: null, customizedBy: null, version: 0,
      binding: { model: { key: model, displayName: model, role: null, lifecycle: "ACTIVE", replacedBy: null, catalogued: true, engineRunnable: true },
        provider: { key: provider, name: provider, kind: null, billing: "FREE", status: "ENABLED" },
        target: { key: "t", name: target, delivery: "ENGINE_RUN", software: null, availability: null, providers: [], contextFile: null, howItWorks: null },
        valid: true, problems: [], warnings: [], summary: "" },
    });
    const calls = renderDrawer({
      "GET /api/tasks/7": () => ({ body: { ...TASK, agentId: null }, etag: '"3"' }),
      "GET /api/ecosystem/bindings": () => ({ body: [
        binding(1, "Code Architect", "Software Engineer", "Qwen 3.5 9B", "Ollama (locale)", "AI Engine"),
        binding(2, "Claude Code Engineer", "Software Engineer", "Claude (abbonamento)", "Abbonamento Claude", "Claude Code (CLI)"),
      ] }),
      "PUT /api/tasks/7/agent": () => ({ body: { ...TASK, agentId: 2 }, etag: '"4"' }),
    });

    fireEvent.click(await screen.findByRole("button", { name: "Assegna agente" }));
    fireEvent.click(await screen.findByRole("button", { name: /Claude Code Engineer/ }));
    const sentence = await screen.findByText(/Stai assegnando/);
    expect(sentence.textContent).toContain("Design the planner");
    expect(sentence.textContent).toContain("Claude (abbonamento)");
    expect(sentence.textContent).toContain("Claude Code (CLI)");

    fireEvent.click(screen.getByRole("button", { name: "Assegna a Claude Code Engineer" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT" && c.path === "/api/tasks/7/agent")).toBe(true));
    const put = calls.find((c) => c.method === "PUT" && c.path === "/api/tasks/7/agent")!;
    expect(put.headers["If-Match"]).toBe('"3"');
    expect(put.body).toEqual({ agentId: 2 });
  });
});
