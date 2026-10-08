import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import type { Agent } from "../api/types";
import { fakeFetch } from "../test/fakeServer";
import { TaskDrawer } from "./TaskDrawer";

const AGENT: Agent = {
  id: 1, name: "Backend Bot", role: "Backend Engineer", specialization: "APIs",
  model: null, active: true, status: "ACTIVE", createdAt: "2026-09-23T09:00:00Z", updatedAt: "2026-09-23T09:00:00Z", version: 0,
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
      { key: "engine", name: "AI Engine", kind: "ENGINE", available: true, detail: "", delivery: "ENGINE_RUN", recommended: true },
      { key: "claude-code", name: "Claude Code CLI", kind: "CLI", available: true, detail: "", delivery: "CLI_PROMPT", recommended: false },
      { key: "codex", name: "ChatGPT / Codex", kind: "DESKTOP", available: true, detail: "", delivery: "APP_PASTE", recommended: false },
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
        task: { ...PLANNED, status: "IN_PROGRESS" }, prompt: "Leggi …", fullPrompt: "Sei Backend Bot…", delivery: "CLI_PROMPT",
        folder: "C:/Officina", openUrl: null, written: [".aicos/handoffs/TASK-001-claude-code.md"], promptToClipboard: false } }),
    });

    fireEvent.click(await screen.findByRole("button", { name: /Claude Code CLI/ }));
    // A confirmation says what, to whom, with what and through what, before anything opens.
    expect(await screen.findByText("Cosa")).toBeTruthy();
    expect(calls.some((c) => c.method === "POST")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Prepara e apri" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")).toBe(true));
    const post = calls.find((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")!;
    expect(post.headers["If-Match"]).toBe('"4"');
    expect(post.body).toEqual({ target: "claude-code" });
  });

  it("says what blocks the task and disables the targets it cannot use", async () => {
    renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision({
        phaseApproved: false, blockers: ["La fase 1 non è approvata (Human-in-the-Loop)."],
        targets: [{ key: "claude-code", name: "Claude Code CLI", kind: "CLI", available: false, detail: "",
          delivery: "CLI_PROMPT", recommended: false }] }) }),
    });

    expect(await screen.findByText(/non è approvata/)).toBeTruthy();
    expect((screen.getByRole("button", { name: /Claude Code CLI/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("also serves a task outside a plan: its handoff is prepared without a plan document (PHASE 17)", async () => {
    const calls = renderPlanned({
      "GET /api/tasks/9": () => ({ body: { ...PLANNED, code: null, phaseId: null, documentPath: null }, etag: '"4"' }),
      "GET /api/tasks/9/orchestration": () => ({ body: decision({ code: null, phaseId: null, phaseNumber: null,
        phaseTitle: null, prompt: "Esegui TASK-9 seguendo il pacchetto di handoff in `.aicos/handoffs/` e la governance in `AGENTS.md`." }) }),
    });

    await screen.findByText("Master Orchestrator", { exact: false });
    expect(calls.some((c) => c.path.endsWith("/orchestration"))).toBe(true);
    expect(screen.getByText(/Esegui TASK-9 seguendo il pacchetto/)).toBeTruthy();
    expect((screen.getByRole("button", { name: /Claude Code CLI/ }) as HTMLButtonElement).disabled).toBe(false);
  });
});

/** PHASE 32 (TD-49): a handoff to OpenCode is followed from its local API while the drawer is open. */
describe("OrchestratorPanel — following OpenCode", () => {
  it("shows what the OpenCode session of the last handoff is doing", async () => {
    renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision() }),
      "GET /api/tasks/9/handoffs": () => ({ body: [{ id: 7, taskId: 9, target: "opencode", documentPath: ".aicos/handoffs/TASK-001-opencode.md",
        prompt: "Leggi", command: "opencode --port 4310", requestedBy: "operator", createdAt: "2026-10-08T10:00:00Z", followable: true }] }),
      "GET /api/handoffs/7/session": () => ({ body: { status: "BUSY", sessionId: "ses_task", title: "TASK-001", files: 2,
        additions: 12, deletions: 3, lastAnswer: "Ho aggiunto i test.", updatedAt: "2026-10-08T10:05:00Z" } }),
    });
    const panel = await screen.findByLabelText("Sessione OpenCode");
    expect(panel.textContent).toContain("al lavoro");
    expect(panel.textContent).toContain("2 file cambiati · +12 −3");
    expect(panel.textContent).toContain("Ho aggiunto i test.");
  });

  it("does not follow a handoff that cannot be followed", async () => {
    const calls = renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision() }),
      "GET /api/tasks/9/handoffs": () => ({ body: [{ id: 8, taskId: 9, target: "claude-code", documentPath: "x", prompt: "Leggi",
        command: null, requestedBy: "operator", createdAt: "2026-10-08T10:00:00Z", followable: false }] }),
    });
    await screen.findByText(/Storia: 1 consegne/);
    await waitFor(() => expect(calls.some((c) => c.path.startsWith("/api/handoffs/"))).toBe(false));
    expect(screen.queryByLabelText("Sessione OpenCode")).toBeNull();
  });
});
