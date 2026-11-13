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
    prompt: "Esegui TASK-001 partendo dal suo pacchetto di consegna, il più recente `.aicos/handoffs/TASK-001-*.md` "
      + "(ruolo, skill, knowledge e regole); se non c'è, chiedi all'operatore di consegnare la task da AI Company OS prima di cominciare.",
    targets: [
      { key: "engine", name: "AI Engine", kind: "ENGINE", available: true, detail: "", delivery: "ENGINE_RUN", recommended: true },
      { key: "claude-code", name: "Claude Code CLI", kind: "CLI", available: true, detail: "", delivery: "CLI_PROMPT", recommended: false },
      { key: "codex", name: "ChatGPT / Codex", kind: "DESKTOP", available: true, detail: "", delivery: "APP_PASTE", recommended: false },
      { key: "claude-desktop", name: "Claude Code (app desktop)", kind: "DESKTOP", available: true, detail: "",
        delivery: "APP_LINK", recommended: false },
    ],
    blockers: [],
    ...overrides,
  };
}

/** PHASE 36: a readiness check, ready unless items say otherwise. */
function readiness(target: string, items: Record<string, unknown>[] = []) {
  const names: Record<string, string> = { "claude-code": "Claude Code CLI", "claude-desktop": "Claude Code (app desktop)",
    codex: "ChatGPT / Codex", engine: "AI Engine" };
  return { taskId: 9, target, targetName: names[target] ?? target, delivery: "CLI_PROMPT", agentId: 1, agentName: "Backend Bot",
    ready: !items.some((i) => i.status === "BLOCKING"),
    items: [{ area: "BINDING", key: target, name: names[target] ?? target, status: "OK", detail: "Si apre.", action: null }, ...items] };
}

function renderPlanned(routes: Parameters<typeof fakeFetch>[0]) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/tasks/9/readiness": (call) => ({ body: readiness(new URLSearchParams(call.path.split("?")[1] ?? "").get("target") ?? "engine") }),
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
    // PHASE 36: the prompt shown leads to the package; the way in is still the delivery.
    expect(screen.getByText(/Esegui TASK-001 partendo dal suo pacchetto di consegna/)).toBeTruthy();
    expect(screen.getByText("Prompt da incollare a mano")).toBeTruthy();
    expect(screen.getByText(/se la task non è ancora stata consegnata, l'agente chiede di/)).toBeTruthy();
  });

  it("hands the task to Claude Code in the desktop app: the prompt in its composer and in the clipboard (PHASE 36)", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    const calls = renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision() }),
      "POST /api/tasks/9/handoffs": () => ({ status: 202, body: {
        handoff: { id: 2, taskId: 9, target: "claude-desktop", documentPath: ".aicos/handoffs/TASK-001-claude-desktop.md",
          prompt: "Leggi …", command: "explorer.exe claude://code/new?…", requestedBy: "operator", createdAt: null },
        task: { ...PLANNED, status: "IN_PROGRESS" }, prompt: "Leggi .aicos/handoffs/TASK-001-claude-desktop.md ed esegui la task che descrive.",
        fullPrompt: "Sei Backend Bot…", delivery: "APP_LINK", folder: "C:/Officina", openUrl: null,
        written: [".aicos/handoffs/TASK-001-claude-desktop.md"], promptToClipboard: true } }),
    });

    const card = await screen.findByRole("button", { name: /Claude Code \(app desktop\)/ });
    expect(card.textContent).toContain("con il prompt già scritto");
    fireEvent.click(card);
    const go = await screen.findByRole("button", { name: "Prepara e apri" });
    await waitFor(() => expect((go as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(go);

    expect(await screen.findByText(/Aperto nell'app, con il prompt già scritto: conferma la cartella e invia/)).toBeTruthy();
    expect(await screen.findByText(/Il prompt è anche negli appunti/)).toBeTruthy();
    const post = calls.find((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")!;
    expect(post.body).toEqual({ target: "claude-desktop" });
    expect(writeText).toHaveBeenCalledWith("Leggi .aicos/handoffs/TASK-001-claude-desktop.md ed esegui la task che descrive.");
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
    // PHASE 36: the check for that target comes first; the button waits for it.
    expect(await screen.findByText(/Pronta per Claude Code CLI/)).toBeTruthy();
    expect(calls.some((c) => c.path === "/api/tasks/9/readiness?target=claude-code")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Prepara e apri" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")).toBe(true));
    const post = calls.find((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")!;
    expect(post.headers["If-Match"]).toBe('"4"');
    expect(post.body).toEqual({ target: "claude-code" });
  });

  it("asks how the operator wants to work on it: the project's usual way, or told otherwise (PHASE 37, ADR-044)", async () => {
    const calls = renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision() }),
      "POST /api/tasks/9/handoffs": () => ({ status: 202, body: {
        handoff: { id: 1, taskId: 9, target: "claude-code", documentPath: ".aicos/handoffs/TASK-001-claude-code.md",
          prompt: "Leggi …", command: "wt …", requestedBy: "operator", createdAt: null },
        task: { ...PLANNED, status: "IN_PROGRESS" }, prompt: "Leggi …", fullPrompt: "Sei Backend Bot…", delivery: "CLI_PROMPT",
        folder: "C:/Officina", openUrl: null, written: [], promptToClipboard: false } }),
    });

    fireEvent.click(await screen.findByRole("button", { name: /Claude Code CLI/ }));
    // A project where the operator learns: the lesson is the usual way, and it is said.
    const teach = await screen.findByRole("radio", { name: /Imparo/ }) as HTMLInputElement;
    expect(teach.checked).toBe(true);
    expect(screen.getByText("il solito per questo progetto")).toBeTruthy();
    expect(screen.getByText(/nei file lo porti tu, un passo alla volta/)).toBeTruthy();
    // Today the operator cannot follow: the agent works alone and leaves the lesson.
    fireEvent.click(screen.getByRole("radio", { name: /Non posso seguire ora/ }));
    expect(await screen.findByText(/Pronta per Claude Code CLI/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Prepara e apri" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")).toBe(true));
    expect(calls.find((c) => c.method === "POST" && c.path === "/api/tasks/9/handoffs")!.body)
      .toEqual({ target: "claude-code", learning: "RECAP" });
  });

  it("a blocking item of the check stops the delivery and says why (PHASE 36)", async () => {
    const calls = renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision() }),
      "GET /api/tasks/9/readiness": () => ({ body: readiness("claude-code", [
        { area: "BINDING", key: "claude-code-app", name: "Claude Code CLI", status: "BLOCKING",
          detail: "Claude Code CLI non si può aprire: 'claude' is not installed.", action: null },
        { area: "SKILL", key: "sql-modeling", name: "SQL modeling", status: "WARNING",
          detail: "Nessun file nella libreria: all'agente arriva solo la descrizione.", action: null }]) }),
    });
    fireEvent.click(await screen.findByRole("button", { name: /Claude Code CLI/ }));
    expect(await screen.findByText(/Claude Code CLI non si può aprire/, { selector: "div" })).toBeTruthy();
    expect(screen.getAllByText("bloccante").length).toBeGreaterThan(0);
    expect(screen.getAllByText("avviso").length).toBeGreaterThan(0);
    expect((screen.getByRole("button", { name: "Prepara e apri" }) as HTMLButtonElement).disabled).toBe(true);
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });

  it("a skill missing from the tool's folder is copied from the check, which is read again (PHASE 36)", async () => {
    let copied = false;
    const missing = { area: "SKILL", key: "sql-modeling", name: "SQL modeling", status: "WARNING",
      detail: "Non è nella cartella di Claude Code: il pacchetto dà il file della libreria.",
      action: { type: "HUB_COPY", key: "sql-modeling", app: "claude-code" } };
    const synced = { ...missing, status: "OK", detail: "Copia allineata in Claude Code.", action: null };
    const calls = renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision({ binding: { target: { key: "claude-code" } } }) }),
      "GET /api/tasks/9/readiness": () => ({ body: readiness("claude-code", [copied ? synced : missing]) }),
      "POST /api/skill-hub/library/sql-modeling/copies/claude-code": () => {
        copied = true;
        return { body: { app: "claude-code", outcome: "CREATED", path: "C:/x", backup: null, message: "Copiata" } };
      },
    });
    // The panel checks the agent's own target by itself.
    expect(await screen.findByText(/Verifica prima della consegna — pronta per Claude Code CLI/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Copia nello strumento" }));
    expect(await screen.findByText(/Copia allineata in Claude Code/)).toBeTruthy();
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({ replace: false });
    // Another target, another check.
    fireEvent.change(screen.getByLabelText("Strumento da verificare"), { target: { value: "codex" } });
    await waitFor(() => expect(calls.some((c) => c.path === "/api/tasks/9/readiness?target=codex")).toBe(true));
  });

  it("with the launcher off the delivery is prepared and the panel says the tool was not opened (PHASE 36)", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    renderPlanned({
      "GET /api/tasks/9/orchestration": () => ({ body: decision() }),
      "POST /api/tasks/9/handoffs": () => ({ status: 202, body: {
        handoff: { id: 3, taskId: 9, target: "claude-code", documentPath: ".aicos/handoffs/TASK-001-claude-code.md",
          prompt: "Leggi …", command: "wt -d C:/Officina claude …", requestedBy: "operator", createdAt: null },
        task: { ...PLANNED, status: "IN_PROGRESS" }, prompt: "Leggi .aicos/handoffs/TASK-001-claude-code.md ed esegui la task che descrive.",
        fullPrompt: "Sei Backend Bot…", delivery: "CLI_PROMPT", folder: "C:/Officina", openUrl: null,
        written: [".aicos/handoffs/TASK-001-claude-code.md"], promptToClipboard: true,
        notOpened: "The launcher is switched off on this control plane (aicos.launcher.enabled=false)" } }),
    });
    fireEvent.click(await screen.findByRole("button", { name: /Claude Code CLI/ }));
    const go = await screen.findByRole("button", { name: "Prepara e apri" });
    await waitFor(() => expect((go as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(go);
    expect(await screen.findByText(/Lo strumento non è stato aperto: The launcher is switched off/)).toBeTruthy();
    expect(screen.queryByText(/Aperto nel terminale/)).toBeNull();
    expect(writeText).toHaveBeenCalledWith("Leggi .aicos/handoffs/TASK-001-claude-code.md ed esegui la task che descrive.");
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
