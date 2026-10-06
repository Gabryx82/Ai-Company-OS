import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { ProjectDetail } from "./ProjectDetail";

const PROJECT = { id: 5, name: "Shop", description: null, status: "ACTIVE", createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z", projectType: "WEB_APP", stack: null, workspacePath: "C:\\shop", autonomyLevel: "GUIDED",
  planStatus: "NONE" };
const doc = (path: string) => ({ path, size: 2048, modified: "2026-10-01T10:00:00Z" });

function documents(references: string[], others: string[]) {
  return { workspacePath: "C:\\shop", exists: true, masterPrompt: null, masterPromptIsTemplate: false, agents: null,
    implementationPlan: null, planJson: null, phases: [], tasks: [], handoffs: [], references: references.map(doc),
    referenceFiles: others.map(doc) };
}

function renderProject(routes: Parameters<typeof fakeFetch>[0]) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/projects/5/plan": () => ({ body: { project: PROJECT, phases: [], runs: [] } }),
    "GET /api/agents": () => ({ body: [] }),
    "GET /api/projects": () => ({ body: [PROJECT] }),
    ...routes,
  });
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  render(<ApiContext.Provider value={api}><ProjectDetail projectId={5} types={[]} navigate={() => undefined} /></ApiContext.Provider>);
  return calls;
}

describe("Reference — deleting from the console (ADR-035 §5)", () => {
  it("removes one image with its trash button, and the tile leaves at once", async () => {
    let images = ["references/images/hero.png", "references/mockups/home.png"];
    const calls = renderProject({
      "GET /api/projects/5/documents": () => ({ body: documents(images, []) }),
      "DELETE /api/projects/5/references": (call) => {
        images = images.filter((p) => !call.path.includes(encodeURIComponent(p)));
        return { status: 204 };
      },
    });
    fireEvent.click(await screen.findByRole("tab", { name: "Reference" }));
    fireEvent.click(await screen.findByRole("button", { name: "Elimina images/hero.png" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("references/images/hero.png")).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: /Elimina$/ }));

    expect(await screen.findByText("Eliminato images/hero.png.")).toBeTruthy();
    await waitFor(() => expect(screen.queryByText("images/hero.png")).toBeNull());
    expect(screen.getByText("mockups/home.png")).toBeTruthy();
    expect(calls.find((c) => c.method === "DELETE")!.path)
      .toBe("/api/projects/5/references?path=" + encodeURIComponent("references/images/hero.png"));
  });

  it("deletes a selection of images and other files in one request", async () => {
    let images = ["references/images/a.png", "references/images/b.png"];
    let others = ["references/notes.pdf"];
    const calls = renderProject({
      "GET /api/projects/5/documents": () => ({ body: documents(images, others) }),
      "POST /api/projects/5/references/bulk-delete": () => { images = ["references/images/b.png"]; others = []; return { status: 204 }; },
    });
    fireEvent.click(await screen.findByRole("tab", { name: "Reference" }));
    expect(await screen.findByText("notes.pdf")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Seleziona/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleziona images/a.png" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleziona notes.pdf" }));
    fireEvent.click(screen.getByRole("button", { name: /Elimina selezionati/ }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /Elimina$/ }));

    expect(await screen.findByText("Eliminati 2 file.")).toBeTruthy();
    await waitFor(() => expect(screen.queryByText("notes.pdf")).toBeNull());
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({ paths: ["references/images/a.png", "references/notes.pdf"] });
  });

  it("a failed delete says why, and the file stays listed", async () => {
    renderProject({
      "GET /api/projects/5/documents": () => ({ body: documents(["references/images/locked.png"], []) }),
      "DELETE /api/projects/5/references": () => ({ status: 409, problem: true, body: {
        type: "urn:ai-company-os:problem:deletion-blocked", title: "Deletion blocked",
        detail: "'locked.png' cannot be removed: it may be open in another program. Close it and try again." } }),
    });
    fireEvent.click(await screen.findByRole("tab", { name: "Reference" }));
    fireEvent.click(await screen.findByRole("button", { name: "Elimina images/locked.png" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: /Elimina$/ }));
    expect(await within(dialog).findByText(/open in another program/)).toBeTruthy();
    expect(screen.getAllByText("images/locked.png").length).toBeGreaterThan(0);
  });
});
