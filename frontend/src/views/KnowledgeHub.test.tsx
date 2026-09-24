import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { KnowledgeHub } from "./KnowledgeHub";
import { ErrorBoundary } from "../components/ErrorBoundary";

/**
 * The regression found in the PHASE 14 review: a template without sub-agents
 * arrived with `children: null` and blanked the whole console.
 */
describe("KnowledgeHub", () => {
  it("renders resources and templates even when the server sends null lists", async () => {
    const { fetcher } = fakeFetch({
      "GET /api/resources": () => ({ body: [
        { id: 1, key: "clean-code-java", kind: "SKILL", name: "Clean code Java", description: "Naming.", tags: null,
          sourceUrl: null, searchUrl: null, configuration: null },
      ] }),
      "GET /api/agent-profiles": () => ({ body: [] }),
      "GET /api/projects": () => ({ body: [] }),
      "GET /api/agent-templates": () => ({ body: [
        { key: "reviewer", name: "Code Reviewer", role: "Reviewer", specialization: "Review", model: "ollama:qwen3.5:9b",
          domain: "Qualità", children: null },
      ] }),
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(
      <ApiContext.Provider value={api}>
        <ErrorBoundary label="Knowledge Hub"><KnowledgeHub /></ErrorBoundary>
      </ApiContext.Provider>,
    );

    expect(await screen.findByText("Code Reviewer")).toBeTruthy();
    expect(await screen.findByText("Clean code Java")).toBeTruthy();
    expect(screen.queryByText(/si è interrotta/)).toBeNull();
  });

  it("a view that throws takes only itself down", () => {
    function Broken(): never {
      throw new Error("boom");
    }
    const spy = console.error;
    console.error = () => undefined;
    try {
      render(<div><span>Navigazione</span><ErrorBoundary label="Rotta"><Broken /></ErrorBoundary></div>);
    } finally {
      console.error = spy;
    }
    expect(screen.getByText("Navigazione")).toBeTruthy();
    expect(screen.getByText(/La vista «Rotta» si è interrotta/)).toBeTruthy();
  });
});
