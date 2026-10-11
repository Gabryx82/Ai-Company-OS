import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { fakeFetch } from "./test/fakeServer";
import { App } from "./App";

const ADMIN = { id: 1, username: "admin", displayName: "Administrator", role: "ADMIN", enabled: true,
  mustChangePassword: false, lastLoginAt: null, passwordChangedAt: null, version: 0 };

describe("a new tab and the browser's session cookie (TD-53)", () => {
  const original = globalThis.fetch;
  beforeEach(() => sessionStorage.clear());
  afterEach(() => { globalThis.fetch = original; });

  it("signs in by itself when the browser already holds a session for the control plane", async () => {
    const { fetcher, calls } = fakeFetch({
      "GET /api/auth/session": () => ({ body: { user: ADMIN, csrfToken: "csrf-abc" } }),
      "GET /api/auth/me": () => ({ body: { name: "admin", role: "ADMIN", kind: "USER", user: ADMIN } }),
    });
    globalThis.fetch = fetcher;
    render(<App />);

    expect(await screen.findAllByText("Administrator")).not.toHaveLength(0);
    expect(screen.queryByRole("button", { name: "Accedi" })).toBeNull();
    expect(calls[0].path).toBe("/api/auth/session");
    expect(calls[0].credentials).toBe("include");
    expect(JSON.parse(sessionStorage.getItem("aicos.session")!)).toEqual(
      { baseUrl: "http://localhost:8081", csrf: "csrf-abc", user: ADMIN });
  });

  it("shows the sign-in when there is no session", async () => {
    const { fetcher } = fakeFetch({
      "GET /api/auth/session": () => ({ status: 401, problem: true,
        body: { type: "urn:ai-company-os:problem:unauthenticated", title: "Unauthenticated", status: 401 } }),
    });
    globalThis.fetch = fetcher;
    render(<App />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Accedi" })).toBeTruthy());
  });
});

describe("the Ecosistema 3D tab (ADR-037)", () => {
  const original = globalThis.fetch;
  beforeEach(() => sessionStorage.clear());
  afterEach(() => { globalThis.fetch = original; window.location.hash = ""; vi.restoreAllMocks(); });

  it("is in the navigation next to Agenti and loads its view on demand", async () => {
    // jsdom draws no WebGL: the view says so, and everything around the canvas is there.
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    const { fetcher, calls } = fakeFetch({
      "GET /api/auth/session": () => ({ body: { user: ADMIN, csrfToken: "csrf-abc" } }),
      "GET /api/auth/me": () => ({ body: { name: "admin", role: "ADMIN", kind: "USER", user: ADMIN } }),
      "GET /api/ecosystem/bindings": () => ({ body: [] }),
      "GET /api/skill-hub/installed": () => ({ body: { scannedAt: "", lastSync: null, syncIntervalSeconds: 0, apps: [], skills: [],
        categories: [], notes: [], summary: {} } }),
    });
    globalThis.fetch = fetcher;
    window.location.hash = "#/ecosystem3d";
    render(<App />);

    const nav = await screen.findByRole("navigation", { name: "Sections" });
    const items = [...nav.querySelectorAll("button.nav-item")].map((b) => b.textContent);
    expect(items.indexOf("Ecosistema 3D")).toBe(items.indexOf("Agenti") + 1);
    // The lazy chunk carries three.js: with the whole suite running, transforming it takes longer than
    // findBy's default second.
    expect(await screen.findByRole("heading", { name: "Ecosistema 3D" }, { timeout: 10000 })).toBeTruthy();
    expect(await screen.findByText(/WebGL non disponibile/)).toBeTruthy();
    await waitFor(() => expect(calls.some((c) => c.path === "/api/ecosystem/bindings")).toBe(true));
  });
});
