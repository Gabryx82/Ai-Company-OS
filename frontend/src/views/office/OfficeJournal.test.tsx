import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../../api/client";
import type { Agent, JournalEntry, Task } from "../../api/types";
import { ApiContext } from "../../context";
import { fakeFetch } from "../../test/fakeServer";
import type { OfficeSceneProps } from "./OfficeScene";
import { Office } from "./Office";
import { latestSteps } from "./AgentJournal";

// jsdom has no WebGL: the scene is its robots, each with the step its tag would show.
vi.mock("./OfficeScene", () => ({
  default: (props: OfficeSceneProps) => (
    <div data-testid="scene">
      {props.agents.map((a) => <span key={a.id} data-testid={`tag-${a.id}`}>{a.name}{a.step ? ` · ${a.step}` : ""}</span>)}
    </div>
  ),
}));
vi.mock("../ecosystem3d/webgl", () => ({ webglAvailable: () => true }));

const agent = (id: number, name: string) => ({ id, name, role: "dev", specialization: "s", active: true, status: "ACTIVE" }) as Agent;
const task = (id: number, status: Task["status"], agentId: number) =>
  ({ id, title: `Task ${id}`, status, priority: "MEDIUM", agentId, projectId: null }) as Task;
const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
const entry = (key: string, agentId: number, kind: JournalEntry["kind"], text: string, at: string, more: Partial<JournalEntry> = {}) =>
  ({ key, at, approximate: false, agentId, taskId: 10, task: "TASK-017", taskTitle: "Dettaglio film", kind, text, actor: null, ...more }) as JournalEntry;

const JOURNAL = [
  entry("diary:10:1", 1, "STEP", "Creato src/routes/MovieDetail.tsx", minutesAgo(1)),
  entry("diary:10:0", 1, "STEP", "Letto tasks/TASK-017.md", minutesAgo(3), { approximate: true }),
  entry("handoff:2", 1, "DELIVERED", "Consegnata a Claude Code (app desktop): la task è in corso", minutesAgo(5), { actor: "gabryx82" }),
  entry("review:4", 2, "ACCEPTED", "Review: accettata, task completata", minutesAgo(90)),
];

function renderOffice(routes: Parameters<typeof fakeFetch>[0] = {}) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/agents": () => ({ body: [agent(1, "Ada"), agent(2, "Bruno")] }),
    "GET /api/tasks": () => ({ body: [task(10, "IN_PROGRESS", 1)] }),
    "GET /api/projects": () => ({ body: [] }),
    "GET /api/tasks/10/runs": () => ({ body: [] }),
    "GET /api/tasks/10/handoffs": () => ({ body: [] }),
    "GET /api/journal": () => ({ body: JOURNAL }),
    ...routes,
  });
  render(<ApiContext.Provider value={new ControlPlane({ baseUrl: "http://x", token: "t0123456789abcdef" }, fetcher)}><Office /></ApiContext.Provider>);
  return calls;
}

describe("the agents' journal in the 3D office", () => {
  it("shows each step of the chosen agent, its latest under its name, and everyone's in the office's diary", async () => {
    renderOffice();
    const panel = await screen.findByRole("region", { name: "Cosa sta facendo Ada" });
    // The diary is the first thing shown: newest first, the agent's own steps said as such.
    const diary = await within(panel).findByRole("list", { name: "Diario" });
    const items = within(diary).getAllByRole("listitem");
    expect(items.map((li) => li.querySelector(".journal-text")?.textContent)).toEqual([
      "Creato src/routes/MovieDetail.tsx", "Letto tasks/TASK-017.md", "Consegnata a Claude Code (app desktop): la task è in corso"]);
    expect(items[0].textContent).toContain("dal diario dell'agente");
    expect(items[1].querySelector("time")?.textContent).toMatch(/^≈ /);
    expect(items[2].textContent).toContain("gabryx82");

    // The tag says the latest step; an old moment says nothing.
    await waitFor(() => expect(screen.getByTestId("tag-1").textContent).toBe("Ada · Creato src/routes/MovieDetail.tsx"));
    expect(screen.getByTestId("tag-2").textContent).toBe("Bruno");

    // The office's diary has every agent, and a name goes to that agent.
    const office = screen.getByRole("region", { name: "Diario dell'ufficio" });
    expect(within(office).getAllByRole("listitem")).toHaveLength(4);
    fireEvent.click(within(office).getAllByRole("button", { name: "Bruno" })[0]);
    expect(await screen.findByRole("region", { name: "Cosa sta facendo Bruno" })).toBeTruthy();
  });

  it("shows what the agent changed since the handoff, file by file and line by line", async () => {
    const calls = renderOffice({
      "GET /api/tasks/10/changes": () => ({ body: { tracked: true, since: minutesAgo(5), truncated: false, additions: 3, deletions: 1, files: [
        { path: "src/App.java", status: "MODIFIED", additions: 2, deletions: 1, comparable: true, modifiedAt: minutesAgo(1) },
        { path: "src/Contatti.java", status: "ADDED", additions: 1, deletions: 0, comparable: true, modifiedAt: minutesAgo(2) },
      ] } }),
      "GET /api/tasks/10/changes/file": (call) => ({ body: call.path.includes("Contatti")
        ? { path: "src/Contatti.java", status: "ADDED", additions: 1, deletions: 0, comparable: true, note: null, hunks: [
          { oldStart: 0, oldLines: 0, newStart: 1, newLines: 1, lines: [{ kind: "ADDED", oldNumber: null, newNumber: 1, text: "class Contatti {}" }] }] }
        : { path: "src/App.java", status: "MODIFIED", additions: 2, deletions: 1, comparable: true, note: null, hunks: [
          { oldStart: 1, oldLines: 3, newStart: 1, newLines: 4, lines: [
            { kind: "CONTEXT", oldNumber: 1, newNumber: 1, text: "class App {" },
            { kind: "REMOVED", oldNumber: 2, newNumber: null, text: "    int a = 1;" },
            { kind: "ADDED", oldNumber: null, newNumber: 2, text: "    int a = 2;" },
            { kind: "ADDED", oldNumber: null, newNumber: 3, text: "    int b = 3;" },
            { kind: "CONTEXT", oldNumber: 3, newNumber: 4, text: "}" }] }] } }),
    });
    const panel = await screen.findByRole("region", { name: "Cosa sta facendo Ada" });
    fireEvent.click(within(panel).getByRole("tab", { name: "Modifiche al codice" }));

    const files = await within(panel).findByRole("listbox", { name: "File cambiati" });
    expect(within(panel).getByText("2 file cambiati")).toBeTruthy();
    const rows = within(files).getAllByRole("option");
    expect(rows.map((r) => r.textContent)).toEqual(["MApp.javasrc+2−1", "AContatti.javasrc+1−0"]);
    expect(rows[0].getAttribute("aria-selected")).toBe("true");

    const diff = await within(panel).findByLabelText("Modifiche a src/App.java");
    await waitFor(() => expect(diff.querySelectorAll("tr.is-added")).toHaveLength(2));
    expect(diff.querySelector("tr.is-removed")?.textContent).toContain("int a = 1;");
    expect(diff.querySelector(".diff-hunk")?.textContent).toBe("@@ −1,3 +1,4 @@");

    fireEvent.click(rows[1]);
    await within(panel).findByLabelText("Modifiche a src/Contatti.java");
    await waitFor(() => expect(calls.some((c) => c.path === "/api/tasks/10/changes/file?path=src%2FContatti.java")).toBe(true));
  });

  it("falls back to the files touched lately when the handoff took no copy to compare with", async () => {
    renderOffice({ "GET /api/tasks/10/changes": () => ({ body: { tracked: false, since: null, truncated: false, additions: 0, deletions: 0, files: [] } }) });
    const panel = await screen.findByRole("region", { name: "Cosa sta facendo Ada" });
    fireEvent.click(within(panel).getByRole("tab", { name: "Modifiche al codice" }));
    expect(await within(panel).findByText(/Le modifiche riga per riga si vedono dalla prossima consegna/)).toBeTruthy();
  });

  it("keeps on a tag only a moment of the last half hour", () => {
    const steps = latestSteps([entry("a", 1, "STEP", "vecchio", minutesAgo(45)), entry("b", 2, "STEP", "nuovo", minutesAgo(2))]);
    expect([...steps]).toEqual([[2, "nuovo"]]);
  });
});
