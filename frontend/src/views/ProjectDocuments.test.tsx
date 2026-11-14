import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { ProjectDetail } from "./ProjectDetail";

const PROJECT = { id: 5, name: "Shop", description: null, status: "ACTIVE", createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z", projectType: "WEB_APP", stack: null, workspacePath: "C:\\shop", autonomyLevel: "GUIDED",
  planStatus: "NONE" };
const doc = (path: string) => ({ path, size: 2048, modified: "2026-10-01T10:00:00Z" });

function documents(tasks: string[]) {
  return { workspacePath: "C:\\shop", exists: true, masterPrompt: doc("MASTER_PROMPT.md"), masterPromptIsTemplate: false,
    agents: doc("AGENTS.md"), implementationPlan: null, planJson: null, phases: [doc("docs/phases/PHASE_1.md")],
    tasks: tasks.map(doc), handoffs: [doc(".aicos/handoffs/PLAN-claude-code.md")], references: [], referenceFiles: [] };
}

function renderDocuments(tasks: string[]) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/projects/5/plan": () => ({ body: { project: PROJECT, phases: [], runs: [] } }),
    "GET /api/projects/5/documents": () => ({ body: documents(tasks) }),
    "GET /api/projects/5/files": () => ({ body: "# Esito\n\nCRUD fatto, 6 test verdi." }),
    "GET /api/agents": () => ({ body: [] }),
    "GET /api/projects": () => ({ body: [PROJECT] }),
  });
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  render(<ApiContext.Provider value={api}><ProjectDetail projectId={5} types={[]} navigate={() => undefined} /></ApiContext.Provider>);
  return calls;
}

/** The files a section lists, in order. */
function listed(section: string) {
  return within(screen.getByRole("region", { name: section })).queryAllByRole("button").map((b) => b.querySelector(".mono")!.textContent);
}

describe("Documenti — one section for each kind of document (ADR-041 §10, ADR-047)", () => {
  it("lists the outcomes and the diaries apart from the task documents they sit beside", async () => {
    renderDocuments(["tasks/TASK-001.diario.md", "tasks/TASK-001.esito.md", "tasks/TASK-001.md",
      "tasks/TASK-002.md", "tasks/TASK-002.esito.md", "tasks/note.txt"]);
    fireEvent.click(await screen.findByRole("tab", { name: "Documenti" }));
    await screen.findByText("tasks/TASK-001.md");

    expect(screen.getAllByRole("region").map((r) => r.getAttribute("aria-label")))
      .toEqual(["Progetto", "Fasi", "Task", "Esiti", "Diari", "Handoff"]);
    expect(listed("Progetto")).toEqual(["MASTER_PROMPT.md", "AGENTS.md"]);
    expect(listed("Fasi")).toEqual(["docs/phases/PHASE_1.md"]);
    expect(listed("Task")).toEqual(["tasks/TASK-001.md", "tasks/TASK-002.md", "tasks/note.txt"]);
    expect(listed("Esiti")).toEqual(["tasks/TASK-001.esito.md", "tasks/TASK-002.esito.md"]);
    expect(listed("Diari")).toEqual(["tasks/TASK-001.diario.md"]);
    expect(listed("Handoff")).toEqual([".aicos/handoffs/PLAN-claude-code.md"]);
  });

  it("an outcome opens like any document, and can be deleted from its section", async () => {
    const calls = renderDocuments(["tasks/TASK-001.esito.md", "tasks/TASK-001.md"]);
    fireEvent.click(await screen.findByRole("tab", { name: "Documenti" }));
    fireEvent.click(await within(await screen.findByRole("region", { name: "Esiti" })).findByText("tasks/TASK-001.esito.md"));

    expect(await screen.findByText(/CRUD fatto, 6 test verdi\./)).toBeTruthy();
    expect(calls.some((c) => c.path === "/api/projects/5/files?path=" + encodeURIComponent("tasks/TASK-001.esito.md"))).toBe(true);
    expect(screen.getByRole("button", { name: "Elimina tasks/TASK-001.esito.md" })).toBeTruthy();
  });

  it("a project whose agents have written no outcome and kept no diary says so in each section", async () => {
    renderDocuments(["tasks/TASK-001.md"]);
    fireEvent.click(await screen.findByRole("tab", { name: "Documenti" }));
    await screen.findByText("tasks/TASK-001.md");

    expect(listed("Esiti")).toEqual([]);
    expect(within(screen.getByRole("region", { name: "Esiti" })).getByText("—")).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "Diari" })).getByText("—")).toBeTruthy();
  });
});
