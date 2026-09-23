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
    await screen.findByText("Design the planner");
    expect(screen.getByRole("button", { name: "Start" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Mark done" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Reopen" })).toBeNull();
  });

  it("acts with the tag it was read at", async () => {
    const calls = renderDrawer({
      "GET /api/tasks/7": () => ({ body: TASK, etag: '"0"' }),
      "POST /api/tasks/7/start": () => ({ body: { ...TASK, status: "IN_PROGRESS" }, etag: '"1"' }),
    });
    fireEvent.click(await screen.findByRole("button", { name: "Start" }));
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
    fireEvent.click(await screen.findByRole("button", { name: "Start" }));
    await screen.findByText("Changed by someone else");
    await screen.findByText("Renamed meanwhile");
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(1);
  });

  it("launches a run with the agent's model unless another is chosen, using the task's tag", async () => {
    const calls = renderDrawer({
      "GET /api/tasks/7": () => ({ body: TASK, etag: '"2"' }),
      "POST /api/tasks/7/runs": () => ({ status: 202, body: { id: 1, status: "QUEUED" } }),
    });
    fireEvent.click(await screen.findByRole("button", { name: "Run with Code Architect" }));
    await waitFor(() => expect(calls.some((c) => c.path === "/api/tasks/7/runs" && c.method === "POST")).toBe(true));
    const launch = calls.find((c) => c.method === "POST")!;
    expect(launch.headers["If-Match"]).toBe('"2"');
    expect(launch.body).toEqual({});
  });

  it("does not offer a run for a task nobody holds", async () => {
    renderDrawer({ "GET /api/tasks/7": () => ({ body: { ...TASK, agentId: null }, etag: '"0"' }) });
    await screen.findByText("Assign an agent first.");
    expect((screen.getByRole("button", { name: /Run with/ }) as HTMLButtonElement).disabled).toBe(true);
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
});
