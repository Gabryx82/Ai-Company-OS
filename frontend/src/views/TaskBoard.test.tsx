import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { TaskBoard } from "./TaskBoard";

describe("TaskBoard", () => {
  it("puts every task in the column of its status, with its agent", async () => {
    const { fetcher } = fakeFetch({
      "GET /api/tasks": () => ({ body: [
        { id: 1, title: "Queue me", description: null, status: "OPEN", priority: "LOW", projectId: null, agentId: null },
        { id: 2, title: "Working on it", description: null, status: "IN_PROGRESS", priority: "HIGH", projectId: 5, agentId: 9 },
        { id: 3, title: "Shipped", description: null, status: "DONE", priority: "MEDIUM", projectId: null, agentId: 9 },
      ] }),
      "GET /api/agents": () => ({ body: [{ id: 9, name: "Code Architect", role: "r", specialization: "s", model: null,
        active: true, status: "ACTIVE", createdAt: null, updatedAt: null }] }),
      "GET /api/projects": () => ({ body: [{ id: 5, name: "Company OS", description: null, status: "ACTIVE",
        createdAt: null, updatedAt: null }] }),
    });
    render(
      <ApiContext.Provider value={new ControlPlane({ baseUrl: "http://x", token: "t0123456789abcdef" }, fetcher)}>
        <TaskBoard />
      </ApiContext.Provider>,
    );

    const open = await screen.findByRole("region", { name: "Open" });
    await within(open).findByText("Queue me");
    expect(within(open).getByText("unassigned")).toBeTruthy();

    const working = screen.getByRole("region", { name: "In progress" });
    expect(within(working).getByText("Working on it")).toBeTruthy();
    expect(within(working).getByText("Code Architect")).toBeTruthy();
    expect(within(working).getByText("· Company OS")).toBeTruthy();

    expect(within(screen.getByRole("region", { name: "Done" })).getByText("Shipped")).toBeTruthy();
  });
});
