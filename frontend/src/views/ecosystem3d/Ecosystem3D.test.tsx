import { afterEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane, type Session } from "../../api/client";
import type { AgentConfiguration } from "../../api/types";
import { ApiContext, SessionContext } from "../../context";
import { fakeFetch } from "../../test/fakeServer";
import type { SceneProps } from "./EcosystemScene";
import { Ecosystem3D, REFRESH_MS } from "./Ecosystem3D";
import { agent, hub, team } from "./fixtures";

// jsdom has no WebGL: the scene is replaced by a list of what it would draw,
// with the same callbacks a click and a double click on a node would call.
const webgl = vi.hoisted(() => ({ available: true }));
vi.mock("./EcosystemScene", () => ({
  default: (props: SceneProps) => (
    <div data-testid="scene">
      {props.visible.nodes.map((n) => (
        <button key={n.id} onClick={() => props.onSelect(n.id)} onDoubleClick={() => props.onActivate(n.id)}>{`nodo ${n.label}`}</button>
      ))}
      <output data-testid="camera">{props.camera ? (props.camera.kind === "focus" ? `focus:${props.camera.id}` : "fit") : ""}</output>
    </div>
  ),
}));
vi.mock("./webgl", () => ({ webglAvailable: () => webgl.available }));

afterEach(() => {
  webgl.available = true;
  vi.useRealTimers();
});

function renderView(routes: Parameters<typeof fakeFetch>[0]) {
  const { fetcher, calls } = fakeFetch(routes);
  const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
    user: { id: 1, username: "admin", displayName: null, role: "ADMIN" } as Session["user"] };
  const navigate = vi.fn();
  render(
    <SessionContext.Provider value={session}>
      <ApiContext.Provider value={new ControlPlane(session, fetcher)}><Ecosystem3D navigate={navigate} /></ApiContext.Provider>
    </SessionContext.Provider>,
  );
  return { calls, navigate };
}

const ROUTES = {
  "GET /api/ecosystem/bindings": () => ({ body: team() }),
  "GET /api/skill-hub/installed": () => ({ body: hub() }),
};

describe("Ecosistema 3D (ADR-037)", () => {
  it("draws the agents and what defines them, from the bindings and the Knowledge Hub", async () => {
    const { calls } = renderView(ROUTES);
    expect(await screen.findByRole("button", { name: "nodo Backend Bot" })).toBeTruthy();
    for (const name of ["Frontend Bot", "Test Bot", "Qwen 3.5 4B", "Ollama", "clean code", "postgres mcp", "IntelliJ IDEA",
      "Skill · Claude Code", "Plugin · Claude Code"]) {
      expect(screen.getByRole("button", { name: `nodo ${name}` })).toBeTruthy();
    }
    // Per-agent text starts hidden; the legend says how many there are.
    expect(screen.queryByRole("button", { name: "nodo System prompt" })).toBeNull();
    const legend = screen.getByRole("group", { name: "Categorie" });
    expect(within(legend).getByRole("button", { name: /Direttive/ }).getAttribute("aria-pressed")).toBe("false");
    expect(within(legend).getByRole("button", { name: /Skill/ }).textContent).toContain("2/3");
    expect(calls.map((c) => c.path)).toEqual(expect.arrayContaining(["/api/ecosystem/bindings", "/api/skill-hub/installed"]));
    expect(calls.every((c) => c.method === "GET")).toBe(true);
    await waitFor(() => expect(screen.getByTestId("camera").textContent).toBe("fit"));
  });

  it("shows a node's properties with the agent's binding chain, and links to the editor that changes them", async () => {
    const { navigate } = renderView(ROUTES);
    fireEvent.click(await screen.findByRole("button", { name: "nodo Backend Bot" }));
    const panel = screen.getByLabelText("Proprietà di Backend Bot");
    expect(within(panel).getByLabelText("Catena agente, modello, provider, execution target")).toBeTruthy();
    expect(within(panel).getByText("Sottoagenti:")).toBeTruthy();
    fireEvent.click(within(panel).getByRole("button", { name: /Apri nella scheda Agenti/ }));
    expect(navigate).toHaveBeenCalledWith("agents", "1");

    fireEvent.click(within(panel).getByRole("button", { name: /clean code/ }));
    const skill = screen.getByLabelText("Proprietà di clean code");
    expect(within(skill).getAllByText("usato da")).toHaveLength(2);
  });

  it("never puts a credential on screen", async () => {
    renderView(ROUTES);
    fireEvent.click(await screen.findByRole("button", { name: "nodo postgres mcp" }));
    expect(screen.getByText(/Ha una configurazione: non è mostrata qui/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Cerca nella mappa"), { target: { value: "chiave" } });
    fireEvent.click(await screen.findByRole("option", { name: /System prompt/ }));
    const panel = await screen.findByLabelText("Proprietà di System prompt");
    expect(panel.querySelector("pre")!.textContent).toBe("Sei un backend engineer. La chiave è ••••••, non mostrarla.");
    expect(document.body.textContent).not.toContain("supersecret");
    expect(document.body.textContent).not.toContain("sk-ant-api03");
  });

  it("finds what is hidden, shows it, selects it and flies the camera to it", async () => {
    renderView(ROUTES);
    await screen.findByRole("button", { name: "nodo Backend Bot" });
    fireEvent.change(screen.getByLabelText("Cerca nella mappa"), { target: { value: "produzione" } });
    const option = await screen.findByRole("option", { name: /Direttiva 2/ });
    expect(option.textContent).toContain("nascosto");
    fireEvent.click(option);
    expect(await screen.findByRole("button", { name: "nodo Direttiva 2" })).toBeTruthy();
    expect(screen.getByTestId("camera").textContent).toBe("focus:directive:1:1");
    expect(screen.getByLabelText("Proprietà di Direttiva 2")).toBeTruthy();
  });

  it("filters by category and by agent, and opens a cluster with a double click", async () => {
    renderView(ROUTES);
    await screen.findByRole("button", { name: "nodo clean code" });
    fireEvent.click(within(screen.getByRole("group", { name: "Categorie" })).getByRole("button", { name: /^Skill/ }));
    expect(screen.queryByRole("button", { name: "nodo clean code" })).toBeNull();
    fireEvent.click(within(screen.getByRole("group", { name: "Categorie" })).getByRole("button", { name: /^Skill/ }));

    fireEvent.click(screen.getByRole("button", { name: /Filtra per agente/ }));
    fireEvent.click(within(screen.getByRole("group", { name: "Agenti mostrati" })).getByRole("button", { name: "Backend Bot" }));
    expect(screen.queryByRole("button", { name: "nodo Backend Bot" })).toBeNull();
    expect(screen.queryByRole("button", { name: "nodo postgres mcp" })).toBeNull();
    expect(screen.getByRole("button", { name: "nodo clean code" })).toBeTruthy();

    expect(screen.queryByRole("button", { name: "nodo pdf" })).toBeNull();
    fireEvent.doubleClick(screen.getByRole("button", { name: "nodo Skill · Claude Code" }));
    expect(screen.getByRole("button", { name: "nodo pdf" })).toBeTruthy();
  });

  it("shows one agent with everything it has, its parent around it, and goes back to the ecosystem", async () => {
    renderView(ROUTES);
    fireEvent.doubleClick(await screen.findByRole("button", { name: "nodo Frontend Bot" }));
    expect(screen.queryByRole("button", { name: "nodo Backend Bot" })).toBeNull();
    expect(screen.getByRole("button", { name: "nodo react" })).toBeTruthy();
    expect((screen.getByLabelText("Agente da mostrare") as HTMLSelectElement).value).toBe("2");

    fireEvent.change(screen.getByLabelText("Agente da mostrare"), { target: { value: "3" } });
    expect(screen.getByRole("button", { name: "nodo Backend Bot" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "nodo postgres mcp" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Intero ecosistema" }));
    expect(screen.getByRole("button", { name: "nodo postgres mcp" })).toBeTruthy();
  });

  it("follows the configuration: a new agent appears and a deleted one goes at the next read", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let agents: AgentConfiguration[] = team();
    renderView({ ...ROUTES, "GET /api/ecosystem/bindings": () => ({ body: agents }) });
    fireEvent.click(await screen.findByRole("button", { name: "nodo Frontend Bot" }));
    agents = [...agents.filter((a) => a.id !== 2), agent(4, "Docs Bot", { role: "Technical Writer" })];
    await act(async () => { await vi.advanceTimersByTimeAsync(REFRESH_MS); });
    expect(await screen.findByRole("button", { name: "nodo Docs Bot" })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("button", { name: "nodo Frontend Bot" })).toBeNull());
    // What was selected and is gone, is let go.
    expect(screen.queryByLabelText("Proprietà di Frontend Bot")).toBeNull();
  });

  it("draws the rest when the Hub cannot be read, and says so", async () => {
    renderView({ ...ROUTES, "GET /api/skill-hub/installed": () => ({ status: 500, problem: true, body: { title: "Boom", status: 500 } }) });
    expect(await screen.findByRole("button", { name: "nodo Backend Bot" })).toBeTruthy();
    expect(await screen.findByText(/non sono leggibili ora/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "nodo Skill · Claude Code" })).toBeNull();
  });

  it("says so when the browser cannot draw 3D, and when there are no agents", async () => {
    webgl.available = false;
    renderView(ROUTES);
    expect(await screen.findByText(/WebGL non disponibile/)).toBeTruthy();
    expect(screen.queryByTestId("scene")).toBeNull();
  });

  it("invites to create an agent when there is none", async () => {
    const { navigate } = renderView({ ...ROUTES, "GET /api/ecosystem/bindings": () => ({ body: [] }) });
    fireEvent.click(await screen.findByRole("button", { name: "Vai agli agenti" }));
    expect(navigate).toHaveBeenCalledWith("agents");
  });
});
