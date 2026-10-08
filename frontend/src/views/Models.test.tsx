import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane, type Session } from "../api/client";
import { ApiContext, SessionContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { Models } from "./Models";

const QWEN = { key: "ollama:qwen3.5:9b", providerKey: "ollama", displayName: "Qwen 3.5 9B", role: "PLANNER",
  capabilities: ["tools"], contextWindow: 16384, sizeGb: 6.6, parameters: "9B", lifecycle: "ACTIVE", replacedBy: null,
  notes: null, engineAvailable: true, engineDetail: null, version: 2, inputPricePerMtok: null, outputPricePerMtok: null,
  alsoServes: ["CODER"] };

describe("Modelli — the roles a model serves (TD-66)", () => {
  it("shows the other roles a model serves besides its own", async () => {
    const { fetcher } = fakeFetch({
      "GET /api/catalog/providers": () => ({ body: [] }),
      "GET /api/catalog/models": () => ({ body: { models: [QWEN, { ...QWEN, key: "ollama:qwen3.5:4b", displayName: "Qwen 3.5 4B",
        role: "FAST", alsoServes: [] }], uncatalogued: [], engineReachable: true, engineDefault: "echo:default" } }),
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}><Models /></ApiContext.Provider>);

    expect(await screen.findByText("serve anche: Codice")).toBeTruthy();
    expect(screen.getAllByText(/serve anche/)).toHaveLength(1);
  });
});

describe("Modelli — retiring a catalog entry (TD-71)", () => {
  function renderAs(role: "ADMIN" | "OPERATOR", routes: Parameters<typeof fakeFetch>[0]) {
    const { fetcher, calls } = fakeFetch(routes);
    const session: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
      user: { id: 1, username: "admin", displayName: null, role } as Session["user"] };
    render(
      <SessionContext.Provider value={session}>
        <ApiContext.Provider value={new ControlPlane(session, fetcher)}><Models /></ApiContext.Provider>
      </SessionContext.Provider>,
    );
    return calls;
  }
  const PREVIEW = { kind: "MODEL", subjects: [{ id: QWEN.key, name: QWEN.displayName }],
    deletes: ["«Qwen 3.5 9B» esce dal catalogo dei modelli.", "La voce viene congedata e il prossimo avvio non la reinserisce."],
    detaches: [], keeps: ["Il modello non è rimosso da Ollama né dal provider: esce solo la voce."], files: [], blockers: [], allowed: true };

  it("an admin retires a model under its tag, after reading what happens, and the list is read again", async () => {
    let models = [QWEN];
    const calls = renderAs("ADMIN", {
      "GET /api/catalog/providers": () => ({ body: [] }),
      "GET /api/catalog/models": () => ({ body: { models, uncatalogued: [], engineReachable: true, engineDefault: null } }),
      "GET /api/catalog/models/deletion": () => ({ body: PREVIEW }),
      "DELETE /api/catalog/models": () => { models = []; return { status: 204 }; },
    });
    fireEvent.click(await screen.findByRole("button", { name: "Congeda Qwen 3.5 9B" }));
    expect(await screen.findByText(/non è rimosso da Ollama/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Congeda$/ }));

    await waitFor(() => expect(screen.queryByText("Qwen 3.5 9B")).toBeNull());
    const deletion = calls.find((c) => c.method === "DELETE");
    expect(deletion?.path).toBe("/api/catalog/models?key=ollama%3Aqwen3.5%3A9b");
    expect(deletion?.headers["If-Match"]).toBe('"2"');
    expect(calls.find((c) => c.path.startsWith("/api/catalog/models/deletion"))?.path)
      .toBe("/api/catalog/models/deletion?key=ollama%3Aqwen3.5%3A9b");
  });

  it("an operator does not see the gesture", async () => {
    renderAs("OPERATOR", {
      "GET /api/catalog/providers": () => ({ body: [] }),
      "GET /api/catalog/models": () => ({ body: { models: [QWEN], uncatalogued: [], engineReachable: true, engineDefault: null } }),
    });
    expect(await screen.findByText("Qwen 3.5 9B")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Congeda/ })).toBeNull();
  });
});
