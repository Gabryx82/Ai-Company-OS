import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { KeptEmbeds } from "./Integrations";

const SOFTWARE = [
  { key: "open-webui", name: "Open WebUI", category: "AI_LOCAL", role: "Chat con i modelli locali", launchKind: "LOCAL_SERVICE",
    embeddable: true, url: "http://localhost:3000", availability: "RUNNING", launchable: true, iconUrl: "data:," },
  { key: "omniverse-3d", name: "3D Omniverse", category: "AI_LOCAL", role: "Studio 3D", launchKind: "LOCAL_SERVICE",
    embeddable: true, url: "http://localhost:8800", availability: "RUNNING", launchable: true, iconUrl: "data:," },
  { key: "github", name: "GitHub", category: "VCS", role: "Repository", launchKind: "WEB", embeddable: false,
    url: "https://github.com", availability: "WEB", launchable: false, iconUrl: "data:," },
];

function setUp() {
  const { fetcher, calls } = fakeFetch({ "GET /api/software": () => ({ body: SOFTWARE }) });
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  const navigate = vi.fn();
  const view = (keys: string[], current: string | null) => (
    <ApiContext.Provider value={api}><KeptEmbeds keys={keys} current={current} navigate={navigate} /></ApiContext.Provider>
  );
  return { calls, navigate, view };
}

afterEach(() => vi.restoreAllMocks());

describe("Applicazioni incorporate, tenute caricate (ADR-046)", () => {
  it("keeps an application loaded while the operator is elsewhere and shows it again as it was", async () => {
    const { view } = setUp();
    const { rerender } = render(view(["open-webui"], "open-webui"));

    const frame = await screen.findByTitle("Open WebUI");
    expect(frame.getAttribute("src")).toBe("http://localhost:3000");

    // Elsewhere: hidden, not removed -- the iframe's page stays as it was.
    rerender(view(["open-webui"], null));
    expect(screen.getByTitle("Open WebUI")).toBe(frame);
    expect(frame.closest("[hidden]")).not.toBeNull();

    // Back: the same frame, not a new one that loads Open WebUI again.
    rerender(view(["open-webui"], "open-webui"));
    expect(await screen.findByTitle("Open WebUI")).toBe(frame);
    expect(frame.closest("[hidden]")).toBeNull();
  });

  it("shows the one asked for, keeps the others hidden, and reloads only when asked", async () => {
    const { view } = setUp();
    const { rerender } = render(view(["open-webui"], "open-webui"));
    const webui = await screen.findByTitle("Open WebUI");

    rerender(view(["open-webui", "omniverse-3d"], "omniverse-3d"));
    const omniverse = await screen.findByTitle("3D Omniverse");
    expect(omniverse.closest("[hidden]")).toBeNull();
    expect(screen.getByTitle("Open WebUI")).toBe(webui);
    expect(webui.closest("[hidden]")).not.toBeNull();

    fireEvent.click(screen.getAllByRole("button", { name: /Ricarica/ }).find((b) => b.closest("[hidden]") === null)!);
    expect(screen.getByTitle("3D Omniverse")).not.toBe(omniverse);
    expect(screen.getByTitle("Open WebUI")).toBe(webui);
  });

  it("says so when what was asked is not an application that opens inside the console", async () => {
    const { view, navigate } = setUp();
    render(view(["github"], "github"));

    expect(await screen.findByText(/non è un'applicazione che si apre dentro la console/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Integrazioni/ }));
    expect(navigate).toHaveBeenCalledWith("integrations");
  });
});
