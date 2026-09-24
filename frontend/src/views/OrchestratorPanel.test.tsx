import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import type { Agent } from "../api/types";
import { fakeFetch } from "../test/fakeServer";
import { TaskDrawer } from "./TaskDrawer";

const AGENT: Agent = {
  id: 1, name: "Backend Bot", role: "Backend Engineer", specialization: "APIs",
  model: null, active: true, status: "ACTIVE", createdAt: null, updatedAt: null,
};
const PLANNED = { id: 9, title: "Creare lo scheletro", description: null, status: "OPEN", priority: "HIGH",
  projectId: 3, agentId: 1, phaseId: 5, code: "TASK-001", documentPath: "tasks/TASK-001.md" };

function decision(overrides: Record<string, unknown> = {}) {
  return {
    taskId: 9, code: "TASK-001", title: "Creare lo scheletro", status: "OPEN", projectId: 3, projectName: "Officina",
    phaseId: 5, phaseNumber: 1, phaseTitle: "Fondamenta", phaseApproved: true, autonomyLevel: "GUIDED",
    autonomyRules: "- …", agent: { agentId: 1, name: "Backend Bot", role: "Backend Engineer", model: null, assigned: true,
      reason: "Assegnato alla task" },
    model: { model: "ollama:qwen3.5:9b", role: "CODER", available: true, reason: "Modello attivo" },
    software: [{ key: "intellij-junie", name: "IntelliJ IDEA + Junie", availability: "INSTALLED", launchable: true,
      executionTarget: false, reason: "Indicato dal piano" }],
    context: [{ path: "tasks/TASK-001.md", exists: true, why: "Il documento della task" }],
    prompt: "Esegui TASK-001 seguendo `tasks/TASK-001.md` e la governance in `AGENTS.md`.",
    targets: [
      { key: "engine", name: "AI Engine", kind: "ENGINE", available: true, detail: "" },
      { key: "claude-code", name: "Claude Code CLI", kind: "CLI", available: true, detail: "" },
      { key: "codex", name: "ChatGPT / Codex", kind: "DESKTOP", available: true, detail: "" },
    ],
    blockers: [],
    ...overrides,
  };
}

function renderPlanned(routes: Parameters<typeof fakeFetch>[0]) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/tasks/9": () => ({ body: PLANNED, etag: '"4"' }),
    "GET /api/tasks/9/runs": () => ({ body: [] }),
    "GET /api/tasks/9/agent-suggestions": () => ({ body: [] }),
    "GET /api/tasks/9/handoffs": () => ({ body: [] }),
    "GET /api/tasks/9/reviews": () => ({ body: [] }),
    "GET /api/engine/models": () => ({ body: { defaultModel: "echo:default", models: [] } }),
    ...routes,
  });
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  render(
    <ApiContext.Provider value={api}>
      <TaskDrawer taskId={9} agents={[AGENT]} projects={[]} onClose={vi.fn()} onChanged={vi.fn()} />
    </ApiContext.Provider>,
  );
  return calls;
}

describe("OrchestratorPanel", () => {
  it("shows the decision with its reasons and the compact prompt", async () => {
    renderPlanned({ "GET /api/tasks/9/orchestration": () => ({ body: decision() }) });

    await screen.findByText("Master Orchestrator", { exact: false });
    expect(screen.getByText("ollama:qwen3.5:9b")).toBeTruthy();
    expect(screen.getByText("Assegnato alla task")).toBeTruthy();
    expect(screen.getByTitle("Indicato dal piano")).toBeTruthy();
    expect(screen.getByText(/Esegui TASK-001 seguendo/)).toBeTruthy();
  });

  it("hands the task to Claude Code with the task's tag", async () => {
    const calls = renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision() }),
      "POST /api/tasks/9/handoffs": () => ({ status: 202, body: {
        handoff: { id: 1, taskId: 9, target: "claude-code", documentPath: ".aicos/handoffs/TASK-001-claude-code.md",
          prompt: "Leggi …", command: "wt …", requestedBy: "operator", createdAt: null },
        task: { ...PLANNED, status: "IN_PROGRESS" }, prompt: "Leggi …", promptToClipboard: false } }),
    });

    fireEvent.click(await screen.findByRole("button", { name: /Claude Code CLI/ }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")).toBe(true));
    const post = calls.find((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")!;
    expect(post.headers["If-Match"]).toBe('"4"');
    expect(post.body).toEqual({ target: "claude-code" });
  });

  it("says what blocks the task and disables the targets it cannot use", async () => {
    renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision({
        phaseApproved: false, blockers: ["La fase 1 non è approvata (Human-in-the-Loop)."],
        targets: [{ key: "claude-code", name: "Claude Code CLI", kind: "CLI", available: false, detail: "" }] }) }),
    });

    expect(await screen.findByText(/non è approvata/)).toBeTruthy();
    expect((screen.getByRole("button", { name: /Claude Code CLI/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("does not appear for a task outside a plan", async () => {
    const calls = renderPlanned({
      "GET /api/tasks/9": () => ({ body: { ...PLANNED, code: null, phaseId: null, documentPath: null }, etag: '"4"' }),
    });

    await screen.findByText("Creare lo scheletro");
    expect(screen.queryByText("Master Orchestrator", { exact: false })).toBeNull();
    expect(calls.some((c) => c.path.endsWith("/orchestration"))).toBe(false);
  });
});
