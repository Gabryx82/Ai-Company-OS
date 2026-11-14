import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../../api/client";
import type { Agent, Task } from "../../api/types";
import { ApiContext } from "../../context";
import { fakeFetch } from "../../test/fakeServer";
import type { OfficeSceneProps } from "./OfficeScene";
import { Office } from "./Office";

// jsdom has no WebGL: the scene is replaced by its robots as buttons, with the callbacks
// a click and a double click on a robot call, and the last camera request it was given.
vi.mock("./OfficeScene", () => ({
  default: (props: OfficeSceneProps) => (
    <div data-testid="scene">
      {props.agents.map((a) => (
        <button key={a.id} onClick={() => props.onSelect(a.id)} onDoubleClick={() => props.onActivate(a.id)}>{`robot ${a.name}`}</button>
      ))}
      <button onDoubleClick={props.onBackground}>pavimento</button>
      <output data-testid="camera">{!props.camera ? "" : props.camera.kind === "focus" ? `focus:${props.camera.id}`
        : props.camera.kind === "fit" ? (props.camera.view ? `fit:${props.camera.view}` : "fit") : props.camera.kind}</output>
    </div>
  ),
}));
vi.mock("../ecosystem3d/webgl", () => ({ webglAvailable: () => true }));

const agent = (id: number, name: string) => ({ id, name, role: "dev", specialization: "s", active: true, status: "ACTIVE" }) as Agent;
const task = (id: number, status: Task["status"], agentId: number) =>
  ({ id, title: `Task ${id}`, status, priority: "MEDIUM", agentId, projectId: null }) as Task;

function renderOffice() {
  const { fetcher } = fakeFetch({
    "GET /api/agents": () => ({ body: [agent(1, "Ada"), agent(2, "Bruno"), agent(3, "Carla")] }),
    "GET /api/tasks": () => ({ body: [task(10, "IN_PROGRESS", 1), task(11, "OPEN", 2)] }),
    "GET /api/projects": () => ({ body: [] }),
    "GET /api/tasks/10/runs": () => ({ body: [] }),
    "GET /api/tasks/10/handoffs": () => ({ body: [] }),
  });
  render(<ApiContext.Provider value={new ControlPlane({ baseUrl: "http://x", token: "t0123456789abcdef" }, fetcher)}><Office /></ApiContext.Provider>);
}

const camera = () => screen.getByTestId("camera").textContent;

/** The office is ready once its first agent is chosen and the whole room framed. */
async function ready() {
  await screen.findByRole("button", { name: "robot Ada" });
  await waitFor(() => expect(camera()).toBe("fit:front"));
  await within(screen.getByRole("navigation", { name: "Percorso" })).findByRole("button", { name: "Ada" });
}

describe("moving around the 3D office", () => {
  it("frames the whole office when it opens, and goes to an agent picked from the team", async () => {
    renderOffice();
    await ready();
    const team = screen.getByRole("region", { name: "La squadra" });
    fireEvent.click(within(team).getByRole("button", { name: /^Bruno/ }));
    expect(camera()).toBe("focus:2");
    expect(within(team).getByRole("button", { name: /^Bruno/ }).getAttribute("aria-pressed")).toBe("true");
    // A click on a robot chooses it where it stands; a double click goes to it; one on the floor frames everything.
    fireEvent.click(screen.getByRole("button", { name: "robot Carla" }));
    expect(camera()).toBe("focus:2");
    expect(within(team).getByRole("button", { name: /^Carla/ }).getAttribute("aria-pressed")).toBe("true");
    fireEvent.doubleClick(screen.getByRole("button", { name: "robot Carla" }));
    expect(camera()).toBe("focus:3");
    fireEvent.doubleClick(screen.getByRole("button", { name: "pavimento" }));
    expect(camera()).toBe("fit");
  });

  it("moves the camera from the keyboard and from the controls on the map", async () => {
    renderOffice();
    await ready();
    const stage = screen.getByRole("region", { name: /Ufficio 3D/ });
    fireEvent.keyDown(stage, { key: "+" });
    expect(camera()).toBe("zoom");
    fireEvent.keyDown(stage, { key: "ArrowLeft" });
    expect(camera()).toBe("orbit");
    fireEvent.keyDown(stage, { key: "ArrowUp", shiftKey: true });
    expect(camera()).toBe("pan");
    fireEvent.keyDown(stage, { key: "2" });
    expect(camera()).toBe("fit:top");
    fireEvent.keyDown(stage, { key: "f" });
    expect(camera()).toBe("fit");
    fireEvent.click(screen.getByRole("button", { name: "Vista laterale (3)" }));
    expect(camera()).toBe("fit:side");
    fireEvent.click(screen.getByRole("button", { name: "Allontana (−)" }));
    expect(camera()).toBe("zoom");
    const rotate = screen.getByRole("button", { name: "Rotazione automatica (R)" });
    fireEvent.keyDown(stage, { key: "r" });
    expect(rotate.getAttribute("aria-pressed")).toBe("true");
    fireEvent.keyDown(stage, { key: "?" });
    expect(screen.getByRole("dialog", { name: "Scorciatoie da tastiera" })).toBeTruthy();
    fireEvent.keyDown(stage, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Scorciatoie da tastiera" })).toBeNull();
  });

  it("walks the team with N, goes back where it was, and shows the way in the trail", async () => {
    renderOffice();
    await ready();
    const stage = screen.getByRole("region", { name: /Ufficio 3D/ });
    const trail = screen.getByRole("navigation", { name: "Percorso" });
    // The agent at work is chosen first; N goes to the next, in the team's order.
    expect(within(trail).getByRole("button", { name: "Ada" })).toBeTruthy();
    fireEvent.keyDown(stage, { key: "n" });
    expect(camera()).toBe("focus:2");
    fireEvent.keyDown(stage, { key: "n" });
    expect(camera()).toBe("focus:3");
    fireEvent.keyDown(stage, { key: "N", shiftKey: true });
    expect(camera()).toBe("focus:2");
    fireEvent.keyDown(stage, { key: "Enter" });
    expect(camera()).toBe("focus:2");
    fireEvent.keyDown(stage, { key: "Backspace" });
    expect(camera()).toBe("focus:3");
    expect(within(trail).getByRole("button", { name: "Carla" })).toBeTruthy();
    fireEvent.click(within(trail).getByRole("button", { name: "Ufficio" }));
    expect(camera()).toBe("fit");
  });
});
