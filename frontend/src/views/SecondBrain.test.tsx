import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { ControlPlane, type Session } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { SecondBrain } from "./SecondBrain";

// jsdom draws no canvas: these tests drive the navigation around the graph --
// the search, the panel's links, the trail -- which is plain DOM.
const GRAPH = {
  nodes: [
    { id: "project:1", type: "project", label: "AI Company OS", status: null, meta: {} },
    { id: "agent:1", type: "agent", label: "Backend Bot", status: "ACTIVE", meta: { ruolo: "Backend" } },
    { id: "task:7", type: "task", label: "Scrivi le API", status: "TODO", meta: {} },
    { id: "model:qwen", type: "model", label: "Qwen 3.5", status: null, meta: {} },
    { id: "provider:ollama", type: "provider", label: "Ollama", status: null, meta: {} },
  ],
  edges: [
    { source: "project:1", target: "task:7", kind: "contains" },
    { source: "task:7", target: "agent:1", kind: "assigned-to" },
    { source: "agent:1", target: "model:qwen", kind: "uses-model" },
    { source: "model:qwen", target: "provider:ollama", kind: "served-by" },
  ],
};

function renderBrain() {
  const { fetcher } = fakeFetch({ "GET /api/graph": () => ({ body: GRAPH }) });
  const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
    user: { id: 1, username: "admin", displayName: null, role: "ADMIN" } as Session["user"] };
  const navigate = vi.fn();
  render(<ApiContext.Provider value={new ControlPlane(session, fetcher)}><SecondBrain navigate={navigate} /></ApiContext.Provider>);
  return { navigate };
}

describe("Second Brain", () => {
  it("finds a node, shows its links grouped by kind, and follows them", async () => {
    const { navigate } = renderBrain();
    fireEvent.change(screen.getByLabelText("Cerca un nodo"), { target: { value: "back" } });
    fireEvent.click(await screen.findByRole("option", { name: /Backend Bot/ }));
    const panel = screen.getByLabelText("Dettagli di Backend Bot");
    expect(within(panel).getByText("assegnata a")).toBeTruthy();
    expect(within(panel).getByText("usa il modello")).toBeTruthy();

    fireEvent.click(within(panel).getByRole("button", { name: /Scrivi le API/ }));
    expect(screen.getByLabelText("Dettagli di Scrivi le API")).toBeTruthy();

    fireEvent.click(within(screen.getByLabelText("Dettagli di Scrivi le API")).getByRole("button", { name: "Apri" }));
    expect(navigate).toHaveBeenCalledWith("tasks");
  });

  it("goes back along the trail with Backspace and the arrow, and lets go with Esc", async () => {
    renderBrain();
    fireEvent.change(screen.getByLabelText("Cerca un nodo"), { target: { value: "qwen" } });
    fireEvent.keyDown(await screen.findByLabelText("Cerca un nodo"), { key: "Enter" });
    fireEvent.click(within(screen.getByLabelText("Dettagli di Qwen 3.5")).getByRole("button", { name: /Backend Bot/ }));
    const trail = screen.getByRole("navigation", { name: "Percorso" });
    expect(within(trail).getByRole("button", { name: "Backend Bot" }).getAttribute("aria-current")).toBe("location");

    const stage = screen.getByRole("region", { name: /Grafo/ });
    fireEvent.keyDown(stage, { key: "Backspace" });
    expect(screen.getByLabelText("Dettagli di Qwen 3.5")).toBeTruthy();
    fireEvent.keyDown(stage, { key: "Escape" });
    expect(screen.queryByLabelText("Dettagli di Qwen 3.5")).toBeNull();
    fireEvent.click(within(trail).getByRole("button", { name: "Indietro" }));
    expect(screen.getByLabelText("Dettagli di Qwen 3.5")).toBeTruthy();
  });

  it("shows a hidden type when the search goes to one of its nodes", async () => {
    renderBrain();
    const provider = await screen.findByRole("checkbox", { name: /Provider/ });
    expect((provider as HTMLInputElement).checked).toBe(false);
    fireEvent.change(screen.getByLabelText("Cerca un nodo"), { target: { value: "olla" } });
    const option = await screen.findByRole("option", { name: /Ollama/ });
    expect(option.textContent).toContain("nascosto");
    fireEvent.click(option);
    expect((screen.getByRole("checkbox", { name: /Provider/ }) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByLabelText("Dettagli di Ollama")).toBeTruthy();
  });

  it("narrows the graph to the chosen node's neighbours, and back", async () => {
    renderBrain();
    fireEvent.change(screen.getByLabelText("Cerca un nodo"), { target: { value: "AI Company" } });
    fireEvent.click(await screen.findByRole("option", { name: /AI Company OS/ }));
    const stage = screen.getByRole("region", { name: /Grafo/ });
    expect(stage.getAttribute("aria-label")).toBe("Grafo: 4 nodi e 3 collegamenti");
    fireEvent.keyDown(stage, { key: "v" });
    // The project, its task, and the task's agent: two steps away, the model is not.
    expect(stage.getAttribute("aria-label")).toBe("Grafo: 3 nodi e 2 collegamenti");
    expect(screen.getByRole("button", { name: "Mostra tutto" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Mostra tutto" }));
    expect(stage.getAttribute("aria-label")).toBe("Grafo: 4 nodi e 3 collegamenti");
  });
});
