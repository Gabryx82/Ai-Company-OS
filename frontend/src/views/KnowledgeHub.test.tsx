import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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

  it("opens a skill as its file, edits it and saves it with the entry's tag (ADR-026)", async () => {
    const skill = { id: 1, key: "api-review", kind: "SKILL", name: "Revisione API", description: "REST", tags: ["api"],
      sourceUrl: null, searchUrl: null, configuration: null, origin: "USER", fileBacked: true,
      filePath: "skills/api-review/SKILL.md", version: 3 };
    const content = ["---", "key: api-review", "name: Revisione API", "kind: SKILL", "---", "", "# Regole", ""].join("\n");
    const { fetcher, calls } = fakeFetch({
      "GET /api/resources": () => ({ body: [skill] }),
      "GET /api/library": () => ({ body: { root: "C:/Users/op/.aicos/library", exists: true, skills: 1, knowledge: 0, layout: "" } }),
      "GET /api/agent-templates": () => ({ body: [] }),
      "GET /api/agent-profiles": () => ({ body: [] }),
      "GET /api/projects": () => ({ body: [] }),
      "GET /api/resources/api-review/file": () => ({ etag: '"3"', body: { key: "api-review", relativePath: "skills/api-review/SKILL.md",
        absolutePath: "C:/Users/op/.aicos/library/skills/api-review/SKILL.md", exists: true, content, modified: null, version: 3 } }),
      "PUT /api/resources/api-review/file": () => ({ etag: '"4"', body: { key: "api-review", relativePath: "skills/api-review/SKILL.md",
        absolutePath: "x", exists: true, content, modified: null, version: 4 } }),
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}><KnowledgeHub /></ApiContext.Provider>);

    fireEvent.click(await screen.findByRole("button", { name: /Revisione API/ }));
    const editor = await screen.findByLabelText("Contenuto del file") as HTMLTextAreaElement;
    expect(editor.value).toContain("# Regole");
    expect(screen.getByText("C:/Users/op/.aicos/library/skills/api-review/SKILL.md")).toBeTruthy();

    fireEvent.change(editor, { target: { value: `${content}- Mai 200 per una creazione\n` } });
    fireEvent.click(screen.getByRole("button", { name: "Salva file" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.headers["If-Match"]).toBe('"3"');
    expect((put.body as { content: string }).content).toContain("Mai 200 per una creazione");
  });
});
