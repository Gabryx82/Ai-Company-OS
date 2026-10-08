import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
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
