import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { ProjectDetail } from "./ProjectDetail";

const PROJECT = { id: 5, name: "Shop", description: null, status: "ACTIVE", createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z", projectType: "WEB_APP", stack: null, workspacePath: "C:\\shop", autonomyLevel: "GUIDED",
  planStatus: "NONE", version: 0 };
const RUNNING = { id: 9, projectId: 5, source: "ENGINE", status: "RUNNING", requestedModel: "ollama:qwen3.5:9b",
  servedModel: null, failureDetail: null, phases: null, tasks: null, inputTokens: null, outputTokens: null,
  requestedBy: "operator", createdAt: "2026-10-01T10:00:00Z", finishedAt: null, output: null,
  progress: "Task della fase 2/4: Backend" };

describe("Piano — a generation can be cancelled (TD-43)", () => {
  it("stops a running generation and says it was cancelled, not that it failed", async () => {
    let cancelled = false;
    const ended = { ...RUNNING, status: "FAILED", failureDetail: "cancelled: Annullata da operator", progress: null };
    const { fetcher, calls } = fakeFetch({
      "GET /api/projects/5/plan": () => ({ body: { project: PROJECT, phases: [], runs: [cancelled ? ended : RUNNING] } }),
      "GET /api/agents": () => ({ body: [] }),
      "GET /api/projects": () => ({ body: [PROJECT] }),
      "POST /api/plan-runs/9/cancel": () => { cancelled = true; return { body: ended }; },
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}><ProjectDetail projectId={5} types={[]} navigate={() => undefined} /></ApiContext.Provider>);

    fireEvent.click(await screen.findByRole("tab", { name: "Piano" }));
    expect(await screen.findByText(/Generazione in corso… Task della fase 2\/4/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Annulla/ }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/plan-runs/9/cancel")).toBe(true));
    expect(await screen.findByText("Ultima pianificazione annullata")).toBeTruthy();
    expect(screen.getByText("cancelled: Annullata da operator")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Annulla/ })).toBeNull();
  });
});
