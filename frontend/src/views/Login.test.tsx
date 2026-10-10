import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { fakeFetch } from "../test/fakeServer";

const ADMIN = { id: 1, username: "admin", displayName: "Administrator", role: "ADMIN", enabled: true,
  mustChangePassword: true, lastLoginAt: null, passwordChangedAt: null, version: 0 };

describe("sign-in (ADR-024)", () => {
  it("signs in as a browser: the session token stays in the cookie, the page keeps only the anti-forgery token (TD-53)", async () => {
    const { fetcher, calls } = fakeFetch({
      "POST /api/auth/session": () => ({ body: { user: ADMIN, csrfToken: "csrf-abc" } }),
      "GET /api/auth/session": () => ({ body: { user: ADMIN, csrfToken: "csrf-abc" } }),
      "POST /api/auth/logout": () => ({ status: 204 }),
    });
    const session = await ControlPlane.login("http://localhost:8081/", "admin", "a-long-password", fetcher);

    expect(calls[0].path).toBe("/api/auth/session");
    expect(calls[0].headers.Authorization).toBeUndefined();
    expect(calls[0].credentials).toBe("include");
    expect(calls[0].body).toEqual({ username: "admin", password: "a-long-password" });
    // The browser is asked to show the cookie back before the console says "signed in".
    expect(calls[1].method).toBe("GET");
    expect(calls[1].path).toBe("/api/auth/session");
    expect(session).toEqual({ baseUrl: "http://localhost:8081", csrf: "csrf-abc", user: ADMIN });
    expect(JSON.stringify(session)).not.toContain("aicos_s_");

    await new ControlPlane(session, fetcher).logout();
    expect(calls[2].headers.Authorization).toBeUndefined();
    expect(calls[2].headers["X-AICOS-CSRF"]).toBe("csrf-abc");
    expect(calls[2].credentials).toBe("include");
  });

  it("says why, once, when the browser does not send the cookie back (another host name)", async () => {
    const { fetcher } = fakeFetch({
      "POST /api/auth/session": () => ({ body: { user: ADMIN, csrfToken: "csrf-abc" } }),
      "GET /api/auth/session": () => ({ status: 401, problem: true,
        body: { type: "urn:ai-company-os:problem:unauthenticated", title: "Unauthenticated", status: 401 } }),
    });
    const refused = await ControlPlane.login("http://127.0.0.1:8081", "admin", "a-long-password", fetcher).catch((e) => e);
    expect(refused.slug).toBe("session-cookie-not-sent");
    expect(refused.detail).toContain("stesso nome host");
  });

  it("explains a refused sign-in in words and clears the password field", async () => {
    const { Login } = await import("./Login");
    const original = globalThis.fetch;
    const { fetcher } = fakeFetch({
      "POST /api/auth/session": () => ({ status: 401, problem: true,
        body: { type: "urn:ai-company-os:problem:invalid-credentials", title: "Sign-in refused", status: 401 } }),
    });
    globalThis.fetch = fetcher;
    const onSignIn = vi.fn();
    try {
      render(<Login onSignIn={onSignIn} />);
      fireEvent.change(screen.getByLabelText("Utente"), { target: { value: "admin" } });
      fireEvent.change(screen.getByLabelText("Password"), { target: { value: "wrong-password" } });
      fireEvent.click(screen.getByRole("button", { name: "Accedi" }));

      await screen.findByText("Accesso negato");
      await waitFor(() => expect((screen.getByLabelText("Password") as HTMLInputElement).value).toBe(""));
      expect(onSignIn).not.toHaveBeenCalled();
    } finally {
      globalThis.fetch = original;
    }
  });
});
