import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { ProjectDetail } from "./ProjectDetail";

const PROJECT = { id: 5, name: "Shop", description: null, status: "ACTIVE", createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z", projectType: "WEB_APP", stack: null, workspacePath: "C:\\shop", autonomyLevel: "GUIDED",
  planStatus: "NONE", version: 3 };

describe("Panoramica — learning and the suggested level (TD-45)", () => {
  it("shows the metrics and applies the suggested level only when the operator asks", async () => {
    let level = "GUIDED";
    const { fetcher, calls } = fakeFetch({
      "GET /api/projects/5/plan": () => ({ body: { project: { ...PROJECT, autonomyLevel: level }, phases: [], runs: [] } }),
      "GET /api/projects/5": () => ({ body: { ...PROJECT, autonomyLevel: level }, etag: '"3.ab"' }),
      "GET /api/agents": () => ({ body: [] }),
      "GET /api/projects": () => ({ body: [PROJECT] }),
      "GET /api/projects/5/learning": () => ({ body: level === "GUIDED"
        ? { level: "GUIDED", reviewedTasks: 5, firstPassAccepted: 5, changesRequested: 0, operatorCompletions: 1,
            piecesOpen: 0, pieces: [], piecesTruncated: false,
            suggestion: { direction: "UP", level: "SUPERVISED", reasons: ["5 delle ultime 5 task accettate alla prima review (100%)."] } }
        : { level: "SUPERVISED", reviewedTasks: 0, firstPassAccepted: 0, changesRequested: 0, operatorCompletions: 1,
            piecesOpen: 0, pieces: [], piecesTruncated: false,
            suggestion: { direction: "STAY", level: "SUPERVISED", reasons: ["Per proporre il livello successivo servono 8 task riviste a questo livello: finora 0."] } } }),
      "PUT /api/projects/5/profile": (call) => { level = (call.body as { autonomyLevel: string }).autonomyLevel;
        return { body: { ...PROJECT, autonomyLevel: level }, etag: '"4.cd"' }; },
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}><ProjectDetail projectId={5} types={[]} navigate={() => undefined} /></ApiContext.Provider>);

    expect(await screen.findByText("Suggerito: passare a SUPERVISED")).toBeTruthy();
    expect(screen.getByText(/5 task riviste · 5 accettate alla prima review/)).toBeTruthy();
    expect(calls.some((c) => c.method === "PUT")).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Passa a SUPERVISED" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT" && c.path === "/api/projects/5/profile")).toBe(true));
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.headers["If-Match"]).toBe('"3.ab"');
    expect((put.body as { autonomyLevel: string }).autonomyLevel).toBe("SUPERVISED");
    expect(await screen.findByText("Nessun cambio suggerito")).toBeTruthy();
  });
});
