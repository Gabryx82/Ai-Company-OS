import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { fakeFetch } from "../test/fakeServer";

const ADMIN = { id: 1, username: "admin", displayName: "Administrator", role: "ADMIN", enabled: true,
  mustChangePassword: true, lastLoginAt: null, passwordChangedAt: null, version: 0 };

describe("sign-in (ADR-024)", () => {
  it("sends username and password without a bearer token and keeps the session token it gets back", async () => {
    const { fetcher, calls } = fakeFetch({
      "POST /api/auth/login": () => ({ body: { token: "aicos_s_abc", expiresAt: "2026-09-26T00:00:00Z", user: ADMIN } }),
    });
    const session = await ControlPlane.login("http://localhost:8081/", "admin", "a-long-password", fetcher);

    expect(calls[0].headers.Authorization).toBeUndefined();
    expect(calls[0].body).toEqual({ username: "admin", password: "a-long-password" });
    expect(session).toEqual({ baseUrl: "http://localhost:8081", token: "aicos_s_abc", user: ADMIN });

    const api = new ControlPlane(session, fetcher);
    await api.logout().catch(() => undefined);
    expect(calls[1].headers.Authorization).toBe("Bearer aicos_s_abc");
  });

  it("explains a refused sign-in in words and clears the password field", async () => {
    const { Login } = await import("./Login");
    const original = globalThis.fetch;
    const { fetcher } = fakeFetch({
      "POST /api/auth/login": () => ({ status: 401, problem: true,
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
