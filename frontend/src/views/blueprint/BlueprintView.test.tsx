import { beforeAll, describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../../api/client";
import { ApiContext } from "../../context";
import { fakeFetch } from "../../test/fakeServer";
import { BlueprintView } from "./BlueprintView";

// React Flow measures its container and its nodes; jsdom measures nothing.
beforeAll(() => {
  class ResizeObserverStub {
    constructor(private readonly callback: ResizeObserverCallback) {}
    observe(target: Element) {
      this.callback([{ target, contentRect: { width: 800, height: 600 } } as unknown as ResizeObserverEntry], this as unknown as ResizeObserver);
    }
    unobserve() {}
    disconnect() {}
  }
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub;
  (globalThis as unknown as { DOMMatrixReadOnly: unknown }).DOMMatrixReadOnly = class {
    m22 = 1;
    constructor(transform?: string) {
      const scale = transform?.match(/scale\(([1-9.])\)/)?.[1];
      this.m22 = scale !== undefined ? +scale : 1;
    }
  };
  Object.defineProperties(HTMLElement.prototype, {
    offsetHeight: { get() { return 120; }, configurable: true },
    offsetWidth: { get() { return 250; }, configurable: true },
  });
});

const vocabulary = {
  kinds: [
    { kind: "SERVICE", label: "Service", family: "CLASSES", defaultLayer: "BACKEND", section: "CLASS", column: 5 },
    { kind: "REPOSITORY", label: "Repository", family: "DATA", defaultLayer: "DATABASE", section: "CLASS", column: 6 },
    { kind: "CUSTOM", label: "Componente custom", family: "CUSTOM", defaultLayer: "OTHER", section: "NONE", column: 9 },
  ],
  relations: [
    { relation: "USES", label: "usa", family: "DEPENDENCY", description: "dipende da" },
    { relation: "SERVICE_TO_REPOSITORY", label: "service → repository", family: "DEPENDENCY", description: "accede ai dati" },
  ],
  actions: [{ action: "EXPLAIN_COMPONENT", label: "Spiega il componente", description: "" }],
  statuses: ["PLANNED", "IN_PROGRESS", "IMPLEMENTED", "DEPRECATED"], pathStates: ["LINKED", "TO_CREATE", "DOCUMENT_REFERENCE", "VIRTUAL", "MISSING"],
  proposalKinds: ["CREATE_FILE", "ADD_DEPENDENCY", "CUSTOM"],
};

const metrics = { totalNodes: 2, files: 2, classes: 2, endpoints: 0, databaseEntities: 0, tests: 0, detectedRelations: 1,
  manualRelations: 0, proposedRelations: 0, unresolvedRelations: 0, missingNodes: 0,
  byLayer: { BACKEND: 1, DATABASE: 1 }, byFamily: { CLASSES: 1, DATA: 1 } };

function node(id: number, name: string, kind: string) {
  return { id, key: `java:com.acme.${name}`, kind, layer: kind === "SERVICE" ? "BACKEND" : "DATABASE",
    family: kind === "SERVICE" ? "CLASSES" : "DATA", origin: "DETECTED", status: "IMPLEMENTED", pathState: "LINKED", name,
    filePath: `src/main/java/com/acme/${name}.java`, language: "java", description: "Business rules.", pseudocodePreview: "RETURN x",
    tags: [], parentId: null, expanded: false, expandable: false, members: 1, version: 3 };
}

const graph = {
  projectId: 7, archived: false, workspaceReady: true,
  nodes: [node(1, "TaskService", "SERVICE"), node(2, "TaskRepository", "REPOSITORY")],
  edges: [{ id: 9, source: 1, target: 2, relation: "SERVICE_TO_REPOSITORY", family: "DEPENDENCY", origin: "DETECTED",
    label: "save()", description: null, code: { path: "src/main/java/com/acme/TaskService.java", line: 12, excerpt: "tasks.save(t)" }, version: 0 }],
  layout: { positions: { 1: { x: 0, y: 0 }, 2: { x: 340, y: 0 } }, version: 4 },
  lastScan: { id: 1, startedAt: "2026-09-29T10:00:00Z", finishedAt: "2026-09-29T10:00:02Z", requestedBy: "operator", files: 2,
    truncated: false, nodesDetected: 2, edgesDetected: 1, nodesAdded: 2, nodesMissing: 0, unresolved: 0, languages: "java 2 (ast)" },
  metrics, pendingProposals: 1,
};

const detail = {
  ...node(1, "TaskService", "SERVICE"), section: "CLASS", pseudocode: "find(id):\n  RETURN tasks.findById(id)", notes: null,
  structure: { packageName: "com.acme", className: "TaskService", extendsType: null, implementsTypes: [], annotations: ["Service"],
    fields: [{ name: "tasks", type: "TaskRepository", visibility: "private", annotations: [] }],
    methods: [{ name: "find", signature: "Task find(Long id)", visibility: "public", returnType: "Task", parameters: ["Long id"],
      annotations: [], exceptions: [], calls: ["tasks.findById()"], callTargets: [], isStatic: false, startLine: 10, endLine: 12,
      pseudocode: "RETURN tasks.findById(id)" }],
    dependencies: ["TaskRepository"], exceptions: [], relatedTests: ["TaskServiceTest"], signature: null, inputs: [], output: null,
    visibility: null, calls: [], calledBy: [], httpMethod: null, route: null, controller: null, requestDto: null, responseDto: null,
    service: null, authorization: null, statusCodes: [], exports: [], imports: [], props: [], hooks: [], table: null, columns: [] },
  detected: { name: "TaskService", kind: "SERVICE", description: "Business rules.", pseudocode: "find(id):\n  RETURN tasks.findById(id)" },
  overrides: ["description"], startLine: 5, endLine: 20,
  incoming: [], outgoing: [{ edgeId: 9, relation: "SERVICE_TO_REPOSITORY", origin: "DETECTED", label: "save()", nodeId: 2,
    nodeName: "TaskRepository", nodeKind: "REPOSITORY" }],
  lastScanAt: "2026-09-29T10:00:02Z", createdAt: "2026-09-29T10:00:02Z", updatedAt: "2026-09-29T10:00:02Z",
};

function mount(routes: Parameters<typeof fakeFetch>[0]) {
  const server = fakeFetch({
    "GET /api/blueprint/vocabulary": () => ({ body: vocabulary }),
    ...routes,
  });
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, server.fetcher);
  render(<ApiContext.Provider value={api}><BlueprintView projectId={7} onOpenTask={() => undefined} /></ApiContext.Provider>);
  return server;
}

describe("BlueprintView", () => {
  it("shows the numbers, the filters and the blocks the server sent", async () => {
    mount({ "GET /api/projects/7/blueprint": () => ({ body: graph }) });
    const numbers = await screen.findByLabelText("Metriche del Blueprint");
    expect(within(numbers).getByText("Relazioni rilevate")).toBeTruthy();
    expect(screen.getByRole("group", { name: "Filtra per livello" })).toBeTruthy();
    expect(screen.getByRole("button", { name: /Rianalizza il codice/ })).toBeTruthy();
    expect(screen.getByText(/1$/, { selector: ".badge" })).toBeTruthy();
    expect(await screen.findByTestId("block-1")).toBeTruthy();
    expect(screen.getByTestId("block-2").textContent).toContain("TaskRepository");
    // once the blocks are measured, the relation between them is drawn
    await waitFor(() => expect(document.querySelectorAll(".react-flow__edge").length).toBe(1));
  });

  it("an empty Blueprint offers the scan, and the scan is a POST", async () => {
    const empty = { ...graph, nodes: [], edges: [], lastScan: null, layout: { positions: {}, version: 0 },
      metrics: { ...metrics, totalNodes: 0 }, pendingProposals: 0 };
    let scanned = false;
    const server = mount({
      "GET /api/projects/7/blueprint": () => ({ body: scanned ? graph : empty }),
      "POST /api/projects/7/blueprint/scan": () => { scanned = true; return { body: graph.lastScan }; },
    });
    expect(await screen.findByText("Il Blueprint è vuoto")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Analizza il codice" })[0]);
    expect(await screen.findByText(/Analisi completata: 2 file, 2 componenti/)).toBeTruthy();
    expect(server.calls.some((c) => c.method === "POST" && c.path === "/api/projects/7/blueprint/scan")).toBe(true);
  });

  it("a search opens the Inspector, which says what the code wrote and saves with the tag it read", async () => {
    const server = mount({
      "GET /api/projects/7/blueprint": () => ({ body: graph }),
      "GET /api/projects/7/blueprint/nodes/1": () => ({ body: detail, etag: "\"3\"" }),
      "PUT /api/projects/7/blueprint/nodes/1": (call) => ({ body: { ...detail, description: (call.body as { description: string }).description,
        version: 4 }, etag: "\"4\"" }),
    });
    fireEvent.change(await screen.findByLabelText("Cerca un blocco"), { target: { value: "service" } });
    fireEvent.click(await screen.findByRole("option", { name: /TaskService/ }));

    const inspector = await screen.findByLabelText("Inspector del blocco");
    expect(within(inspector).getAllByText("Rilevato dal codice").length).toBeGreaterThan(0);
    expect(within(inspector).getByText("Modifica manuale")).toBeTruthy();
    expect(within(inspector).getByText("Task find(Long id)")).toBeTruthy();
    expect(within(inspector).getByText("TaskServiceTest")).toBeTruthy();
    // reading mode: nothing to edit
    expect(within(inspector).queryByRole("button", { name: "Salva modifiche" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Modifica" }));
    fireEvent.change(await within(inspector).findByLabelText("Descrizione"), { target: { value: "Regole delle task." } });
    fireEvent.click(within(inspector).getByRole("button", { name: "Salva modifiche" }));
    await waitFor(() => expect(server.calls.some((c) => c.method === "PUT")).toBe(true));
    const put = server.calls.find((c) => c.method === "PUT")!;
    expect(put.headers["If-Match"]).toBe("\"3\"");
    expect((put.body as { description: string }).description).toBe("Regole delle task.");
    expect(await within(inspector).findByText("Salvato.")).toBeTruthy();
  });

  it("a stale tag is not retried: the block is read again", async () => {
    let reads = 0;
    const server = mount({
      "GET /api/projects/7/blueprint": () => ({ body: graph }),
      "GET /api/projects/7/blueprint/nodes/1": () => { reads += 1; return { body: detail, etag: "\"3\"" }; },
      "PUT /api/projects/7/blueprint/nodes/1": () => ({ status: 412, problem: true,
        body: { type: "urn:ai-company-os:problem:precondition-failed", title: "Precondition failed", status: 412, detail: "changed" } }),
    });
    fireEvent.change(await screen.findByLabelText("Cerca un blocco"), { target: { value: "TaskService" } });
    fireEvent.keyDown(screen.getByLabelText("Cerca un blocco"), { key: "Enter" });
    const inspector = await screen.findByLabelText("Inspector del blocco");
    fireEvent.click(screen.getByRole("button", { name: "Modifica" }));
    fireEvent.change(await within(inspector).findByLabelText("Nome"), { target: { value: "Altro nome" } });
    fireEvent.click(within(inspector).getByRole("button", { name: "Salva modifiche" }));
    await waitFor(() => expect(reads).toBe(2));
    expect(server.calls.filter((c) => c.method === "PUT")).toHaveLength(1);
  });
});
