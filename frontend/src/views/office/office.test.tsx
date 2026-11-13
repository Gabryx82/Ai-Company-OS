import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ControlPlane } from "../../api/client";
import type { Agent, Task } from "../../api/types";
import { ApiContext } from "../../context";
import { fakeFetch } from "../../test/fakeServer";
import { AssistantChat } from "../../components/AssistantChat";
import { changedLines, commentLines } from "./CodeView";
import { deskState } from "./Office";

const agent = (id: number, active = true) => ({ id, name: `Agent ${id}`, role: "r", specialization: "s", active,
  status: active ? "ACTIVE" : "INACTIVE" }) as Agent;
const task = (id: number, status: Task["status"], agentId: number | null) =>
  ({ id, title: `Task ${id}`, status, priority: "MEDIUM", agentId, projectId: null }) as Task;

describe("the office", () => {
  it("puts an agent to work only when it holds a task in progress", () => {
    const tasks = [task(1, "IN_PROGRESS", 1), task(2, "OPEN", 2), task(3, "DONE", 3), task(4, "OPEN", 1)];
    expect(deskState(agent(1), tasks)).toMatchObject({ state: "working", task: { id: 1 } });
    expect(deskState(agent(2), tasks)).toMatchObject({ state: "waiting", task: null });
    expect(deskState(agent(3), tasks)).toMatchObject({ state: "idle", task: null, next: [] });
    expect(deskState(agent(1, false), tasks).state).toBe("off");
  });

  it("sets apart the comments of the common languages", () => {
    const code = ["// why", "const a = 1;", "/* a block", " * of words", " */", "x = 2  # trailing is code", "# python", "<!-- html -->", "-- sql"]
      .join("\n");
    expect(commentLines(code)).toEqual([true, false, true, true, true, false, true, true, true]);
  });

  it("lights up the lines that were not there before", () => {
    expect(changedLines(null, "a\nb")).toEqual(new Set());
    expect(changedLines("a\nb\n", "a\nnew\nb\n")).toEqual(new Set([1]));
    expect(changedLines("a\nb", "a\nb")).toEqual(new Set());
  });
});

describe("the assistant", () => {
  it("sends the question with the conversation so far and shows the answer and its model", async () => {
    sessionStorage.clear();
    const { fetcher, calls } = fakeFetch({
      "POST /api/assistant/ask": () => ({ body: { answer: "Hai 2 progetti attivi.", model: "ollama:qwen3:4b" } }),
    });
    render(
      <ApiContext.Provider value={new ControlPlane({ baseUrl: "http://x", token: "t0123456789abcdef" }, fetcher)}>
        <AssistantChat onClose={() => undefined} onThinking={() => undefined} suggestions={["Quanti progetti ho?"]} />
      </ApiContext.Provider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Quanti progetti ho?" }));
    expect(await screen.findByText("Hai 2 progetti attivi.")).toBeTruthy();
    expect(screen.getByText(/Risponde ollama:qwen3:4b/)).toBeTruthy();
    expect(calls.find((c) => c.path === "/api/assistant/ask")?.body).toEqual({ question: "Quanti progetti ho?", history: [] });
  });

  it("says the server needs updating when it does not know the assistant yet", async () => {
    sessionStorage.clear();
    const { fetcher } = fakeFetch({ "POST /api/assistant/ask": () => ({ status: 404, body: { title: "Not Found", status: 404 } }) });
    render(
      <ApiContext.Provider value={new ControlPlane({ baseUrl: "http://x", token: "t0123456789abcdef" }, fetcher)}>
        <AssistantChat onClose={() => undefined} onThinking={() => undefined} suggestions={["Ciao"]} />
      </ApiContext.Provider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Ciao" }));
    expect(await screen.findByText(/riavvia AI Company OS/)).toBeTruthy();
  });
});
