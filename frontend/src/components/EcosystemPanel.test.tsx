import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { EcosystemPanel } from "./EcosystemPanel";

const service = (key: string, name: string, over: Record<string, unknown> = {}) => ({
  key, name, url: null, healthUrl: null, command: null, availability: "STOPPED", autostart: true, position: 1,
  timeoutSeconds: 90, lastStatus: "NEVER", lastMessage: null, lastAttemptAt: null, version: 0, ...over,
});

describe("EcosystemPanel (ADR-028)", () => {
  it("shows what answers and what happened at the last start, and never offers to start what already runs", async () => {
    const { fetcher, calls } = fakeFetch({
      "GET /api/ecosystem/services": () => ({ body: [
        service("open-webui", "Open WebUI", { availability: "RUNNING", lastStatus: "ALREADY_RUNNING",
          lastMessage: "Già attivo su http://localhost:8080: nessun nuovo avvio." }),
        service("omniverse-3d", "3D Omniverse", { lastStatus: "FAILED", lastMessage: "Avviato, ma non risponde dopo 120 s" }),
      ] }),
      "POST /api/ecosystem/services/omniverse-3d/start": () => ({ body: service("omniverse-3d", "3D Omniverse", { lastStatus: "STARTING" }) }),
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}><EcosystemPanel /></ApiContext.Provider>);

    expect(await screen.findByText("già attivo")).toBeTruthy();
    expect(screen.getByText(/non risponde dopo 120 s/)).toBeTruthy();
    const buttons = screen.getAllByRole("button", { name: "Avvia" }) as HTMLButtonElement[];
    expect(buttons[0].disabled).toBe(true);
    expect(buttons[1].disabled).toBe(false);

    fireEvent.click(buttons[1]);
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/ecosystem/services/omniverse-3d/start")).toBe(true));
  });
});
