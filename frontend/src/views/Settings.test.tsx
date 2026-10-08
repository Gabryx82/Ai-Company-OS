import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane, type Session } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { Settings } from "./Settings";

const ADMIN = { id: 1, username: "admin", displayName: "Administrator", role: "ADMIN" as const, enabled: true,
  mustChangePassword: true, lastLoginAt: null, passwordChangedAt: "2026-09-25T10:00:00Z", version: 0 };

function renderSettings(routes: Parameters<typeof fakeFetch>[0]) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/ecosystem/services": () => ({ body: [] }),
    "GET /api/admin/users": () => ({ body: [ADMIN] }),
    "GET /api/admin/security-events": () => ({ body: [] }),
    "GET /api/admin/catalog/retirements": () => ({ body: [] }),
    ...routes,
  });
  const session: Session = { baseUrl: "http://localhost:8081", token: "aicos_s_abc", user: ADMIN };
  const onSession = vi.fn();
  render(
    <ApiContext.Provider value={new ControlPlane(session, fetcher)}>
      <Settings session={session} onSessionChanged={onSession} />
    </ApiContext.Provider>,
  );
  return { calls, onSession };
}

describe("my account (ADR-024)", () => {
  it("changes the password and refreshes who is signed in", async () => {
    const { calls, onSession } = renderSettings({
      "PUT /api/auth/password": () => ({ status: 204 }),
      "GET /api/auth/me": () => ({ body: { name: "admin", role: "ADMIN", kind: "USER",
        user: { ...ADMIN, mustChangePassword: false, version: 1 } } }),
    });
    fireEvent.change(screen.getByLabelText("Password attuale"), { target: { value: "the-old-secret-1" } });
    fireEvent.change(screen.getByLabelText("Nuova password"), { target: { value: "a-new-long-secret" } });
    fireEvent.change(screen.getByLabelText("Ripeti la nuova password"), { target: { value: "a-new-long-secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Cambia password" }));

    await screen.findByText("Password cambiata");
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({ currentPassword: "the-old-secret-1", newPassword: "a-new-long-secret" });
    expect(onSession).toHaveBeenCalledWith(expect.objectContaining({ user: expect.objectContaining({ mustChangePassword: false }) }));
  });

  it("says why the button cannot be pressed yet instead of doing nothing", () => {
    renderSettings({});
    fireEvent.change(screen.getByLabelText("Password attuale"), { target: { value: "the-old-secret-1" } });
    fireEvent.change(screen.getByLabelText("Nuova password"), { target: { value: "short" } });
    expect(screen.getByText(/ancora 7 caratteri/)).toBeTruthy();
  });

  it("saves the display name on the account, where the top bar reads it", async () => {
    const { calls, onSession } = renderSettings({
      "PUT /api/auth/profile": () => ({ body: { ...ADMIN, displayName: "Gabriele", version: 1 } }),
    });
    fireEvent.change(screen.getByLabelText("Nome visualizzato"), { target: { value: "Gabriele" } });
    fireEvent.click(screen.getByRole("button", { name: "Salva nome" }));

    await screen.findByText("Salvato");
    expect(calls.find((c) => c.path === "/api/auth/profile")?.body).toEqual({ displayName: "Gabriele" });
    expect(onSession).toHaveBeenCalledWith(expect.objectContaining({ user: expect.objectContaining({ displayName: "Gabriele" }) }));
  });
});

describe("retired catalog entries (TD-71)", () => {
  it("lists what an admin retired and puts one back as the catalog ships it", async () => {
    let retired = [
      { catalog: "MODEL", key: "ollama:qwen3.5:4b", retiredBy: "admin", retiredAt: "2026-10-08T10:00:00Z" },
      { catalog: "SOFTWARE", key: "postman", retiredBy: "admin", retiredAt: "2026-10-08T11:00:00Z" },
    ];
    const { calls } = renderSettings({
      "GET /api/admin/catalog/retirements": () => ({ body: retired }),
      "DELETE /api/admin/catalog/retirements/MODEL/ollama%3Aqwen3.5%3A4b": () => {
        retired = retired.filter((r) => r.key !== "ollama:qwen3.5:4b");
        return { status: 204 };
      },
    });
    expect(await screen.findByText("ollama:qwen3.5:4b")).toBeTruthy();
    expect(screen.getByText("Modelli LLM")).toBeTruthy();
    expect(screen.getByText("Software Hub")).toBeTruthy();

    fireEvent.click(screen.getAllByRole("button", { name: "Ripristina" })[0]);
    await waitFor(() => expect(screen.queryByText("ollama:qwen3.5:4b")).toBeNull());
    expect(screen.getByText("postman")).toBeTruthy();
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/api/admin/catalog/retirements/MODEL/ollama%3Aqwen3.5%3A4b")).toBe(true);
  });

  it("says when nothing is retired", async () => {
    renderSettings({});
    expect(await screen.findByText("Nessuna voce congedata.")).toBeTruthy();
  });
});
