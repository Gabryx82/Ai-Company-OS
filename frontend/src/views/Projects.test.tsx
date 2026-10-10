import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane, type Session } from "../api/client";
import { ApiContext, SessionContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { Projects } from "./Projects";

const SHOP = { id: 3, name: "Shop", description: null, status: "ACTIVE", planStatus: "NONE", projectType: null,
  autonomyLevel: "GUIDED", stack: null, workspacePath: "C:/work/shop", version: 4,
  createdAt: "2026-10-01T09:00:00Z", updatedAt: "2026-10-01T09:00:00Z" };

function preview(deletes: string[], detaches: string[]) {
  return { kind: "PROJECT", subjects: [{ id: "3", name: "Shop" }], deletes, detaches,
    keeps: ["La cartella C:/work/shop resta sul disco: eliminala tu, se vuoi."], files: [], blockers: [], allowed: true };
}

function renderAsAdmin(routes: Parameters<typeof fakeFetch>[0]) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/projects": () => ({ body: [SHOP] }),
    "GET /api/tasks": () => ({ body: [] }),
    "GET /api/catalog/project-types": () => ({ body: [] }),
    "GET /api/projects/3": () => ({ body: SHOP, etag: '"4"' }),
    "GET /api/projects/3/deletion?tasks=DETACH": () => ({ body: preview(["Il progetto «Shop», con 2 fasi, 1 generazioni di piano e 0 risorse adottate."],
      ["La task del progetto resta, senza progetto, senza fase e senza codice di piano."]) }),
    "GET /api/projects/3/deletion?tasks=DELETE": () => ({ body: preview(["Il progetto «Shop», con 2 fasi, 1 generazioni di piano e 0 risorse adottate.",
      "La task del progetto, con 3 esecuzioni, 0 handoff, 1 review e la loro storia."], []) }),
    ...routes,
  });
  const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
    user: { id: 1, username: "admin", displayName: null, role: "ADMIN" } as Session["user"] };
  render(
    <SessionContext.Provider value={session}>
      <ApiContext.Provider value={new ControlPlane(session, fetcher)}>
        <Projects selected={null} navigate={() => undefined} />
      </ApiContext.Provider>
    </SessionContext.Provider>,
  );
  return calls;
}

describe("Progetti — deleting a project in the one dialog of the console (TD-72)", () => {
  it("shows the server's preview for the tasks' fate, asks for the name, and deletes under the project's tag", async () => {
    const calls = renderAsAdmin({ "DELETE /api/projects/3": () => ({ status: 204 }) });
    fireEvent.click(await screen.findByRole("button", { name: "Elimina" }));

    expect(await screen.findByText("La task del progetto resta, senza progetto, senza fase e senza codice di piano.")).toBeTruthy();
    expect(screen.getByText("La cartella C:/work/shop resta sul disco: eliminala tu, se vuoi.")).toBeTruthy();

    fireEvent.click(screen.getByLabelText(/Vengono eliminate anche loro/));
    expect(await screen.findByText("La task del progetto, con 3 esecuzioni, 0 handoff, 1 review e la loro storia.")).toBeTruthy();
    expect(screen.queryByText(/resta, senza progetto/)).toBeNull();

    const confirm = screen.getByRole("button", { name: /Elimina definitivamente/ }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Conferma nome"), { target: { value: " shop " } });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);

    await waitFor(() => expect(calls.some((c) => c.method === "DELETE")).toBe(true));
    const deleted = calls.find((c) => c.method === "DELETE")!;
    expect(deleted.path).toBe("/api/projects/3?tasks=DELETE&confirm=Shop");
    expect(deleted.headers["If-Match"]).toBe('"4"');
    await waitFor(() => expect(screen.queryByLabelText("Conferma nome")).toBeNull());
  });

  it("offers archiving, the reversible way, next to the delete", async () => {
    const calls = renderAsAdmin({ "POST /api/projects/3/archive": () => ({ body: { ...SHOP, status: "ARCHIVED" }, etag: '"5"' }) });
    fireEvent.click(await screen.findByRole("button", { name: "Elimina" }));
    await screen.findByText(/La task del progetto resta/);

    const dialog = screen.getByLabelText("Conferma nome").closest("form")!;
    fireEvent.click(Array.from(dialog.querySelectorAll("button")).find((b) => b.textContent === "Archivia")!);

    await waitFor(() => expect(calls.some((c) => c.method === "POST")).toBe(true));
    const archived = calls.find((c) => c.method === "POST")!;
    expect(archived.path).toBe("/api/projects/3/archive");
    expect(archived.headers["If-Match"]).toBe('"4"');
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
    await waitFor(() => expect(screen.queryByLabelText("Conferma nome")).toBeNull());
  });
});
