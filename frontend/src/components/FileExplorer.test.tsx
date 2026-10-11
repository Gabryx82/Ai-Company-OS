import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { FileExplorer, FileViewer } from "./FileExplorer";
import { App } from "../App";

const project = (id: number, name: string, workspacePath: string | null) => ({ id, name, description: null,
  status: "ACTIVE", createdAt: "2026-10-01T10:00:00Z", updatedAt: "2026-10-01T10:00:00Z", projectType: null, stack: null,
  workspacePath, autonomyLevel: "GUIDED", planStatus: "NONE", version: 0 });
const PROJECTS = [project(3, "Shop", "C:\\work\\shop"), project(7, "Blog", "C:\\work\\blog"), project(9, "Idea", null)];
const entry = (path: string, kind: "DIRECTORY" | "FILE" | "LINK", size: number | null = null) => ({
  name: path.split("/").pop()!, path, kind, size, modifiedAt: "2026-10-10T09:00:00Z" });
const ROOT = { path: "", truncated: false, entries: [entry("src", "DIRECTORY"), entry(".env", "FILE", 30),
  entry("logo.png", "FILE", 900), entry("README.md", "FILE", 12)] };
const SRC = { path: "src", truncated: false, entries: [entry("src/App.tsx", "FILE", 40)] };

function view(path: string, kind: string, extra: Record<string, unknown> = {}) {
  return { path, name: path.split("/").pop(), size: 12, modifiedAt: "2026-10-10T09:00:00Z", kind,
    contentType: null, content: null, reason: null, ...extra };
}

function withApi(routes: Parameters<typeof fakeFetch>[0], ui: ReactElement) {
  const { fetcher, calls } = fakeFetch({ "GET /api/projects": () => ({ body: PROJECTS }), ...routes });
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  render(<ApiContext.Provider value={api}>{ui}</ApiContext.Provider>);
  return calls;
}

beforeEach(() => { try { localStorage.clear(); } catch { /* jsdom */ } });

describe("the file explorer (ADR-039)", () => {
  it("offers the projects that have a folder, starting from the one in view", async () => {
    const calls = withApi({ "GET /api/projects/7/browse": () => ({ body: ROOT }) },
      <FileExplorer projectHint={7} opened={null} onOpen={() => undefined} onClose={() => undefined} />);

    const select = await screen.findByRole("combobox", { name: "Progetto" });
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["Shop", "Blog"]);
    expect((select as HTMLSelectElement).value).toBe("7");
    expect(await screen.findByRole("button", { name: /README\.md/ })).toBeTruthy();
    expect(calls.some((c) => c.path === "/api/projects/3/browse")).toBe(false);
  });

  it("reads a folder only when it is opened, and opens a file in the viewer", async () => {
    const onOpen = vi.fn();
    const calls = withApi({
      "GET /api/projects/3/browse": (call) => ({ body: call.path.includes("path=src") ? SRC : ROOT }),
    }, <FileExplorer projectHint={null} opened={null} onOpen={onOpen} onClose={() => undefined} />);

    const folder = await screen.findByRole("button", { name: /^src$/ });
    expect(calls.filter((c) => c.path.startsWith("/api/projects/3/browse")).map((c) => c.path))
      .toEqual(["/api/projects/3/browse"]);

    fireEvent.click(folder);
    fireEvent.click(await screen.findByRole("button", { name: /App\.tsx/ }));
    expect(calls.map((c) => c.path)).toContain("/api/projects/3/browse?path=src");
    expect(onOpen).toHaveBeenCalledWith({ projectId: 3, path: "src/App.tsx", projectName: "Shop" });
  });

  it("filters the names it has read", async () => {
    withApi({ "GET /api/projects/3/browse": () => ({ body: ROOT }) },
      <FileExplorer projectHint={null} opened={null} onOpen={() => undefined} onClose={() => undefined} />);
    await screen.findByRole("button", { name: /README\.md/ });

    fireEvent.change(screen.getByRole("searchbox", { name: "Filtra i file" }), { target: { value: "logo" } });

    expect(screen.getByRole("button", { name: /logo\.png/ })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /README\.md/ })).toBeNull();
  });

  it("says so when no project has a folder", async () => {
    const { fetcher } = fakeFetch({ "GET /api/projects": () => ({ body: [project(9, "Idea", null)] }) });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}>
      <FileExplorer projectHint={null} opened={null} onOpen={() => undefined} onClose={() => undefined} /></ApiContext.Provider>);

    expect(await screen.findByText(/Nessun progetto ha una cartella/)).toBeTruthy();
  });
});

describe("the file viewer (ADR-039)", () => {
  const created: string[] = [];
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => { created.push("blob:logo"); return "blob:logo"; });
    URL.revokeObjectURL = vi.fn();
  });

  it("shows a text file with its line numbers", async () => {
    withApi({ "GET /api/projects/3/browse/file": () => ({ body: view("src/App.tsx", "TEXT",
      { content: "const a = 1;\nconst b = 2;\n" }) }) },
    <FileViewer file={{ projectId: 3, path: "src/App.tsx", projectName: "Shop" }} onClose={() => undefined} />);

    expect(await screen.findByText(/const a = 1;/)).toBeTruthy();
    expect(document.querySelector(".file-code-lines")!.textContent).toBe("1\n2");
    expect(screen.getByText("Shop")).toBeTruthy();
  });

  it("never shows a file of credentials, only why", async () => {
    withApi({ "GET /api/projects/3/browse/file": () => ({ body: view(".env", "WITHHELD",
      { reason: "Il nome indica un file di credenziali: non viene letto." }) }) },
    <FileViewer file={{ projectId: 3, path: ".env" }} onClose={() => undefined} />);

    expect(await screen.findByText(/file di credenziali/)).toBeTruthy();
    expect(document.querySelector(".file-code")).toBeNull();
  });

  it("shows an image from its bytes, and Escape closes the file", async () => {
    const onClose = vi.fn();
    const calls = withApi({
      "GET /api/projects/3/browse/file": () => ({ body: view("logo.png", "IMAGE", { contentType: "image/png" }) }),
      "GET /api/projects/3/browse/image": () => ({ body: undefined }),
    }, <FileViewer file={{ projectId: 3, path: "logo.png" }} onClose={onClose} />);

    const image = await screen.findByRole("img", { name: "logo.png" });
    expect(image.getAttribute("src")).toBe("blob:logo");
    expect(calls.map((c) => c.path)).toContain("/api/projects/3/browse/image?path=logo.png");

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalled();
  });
});

describe("the File button of the top bar (ADR-039)", () => {
  const original = globalThis.fetch;
  afterEach(() => { globalThis.fetch = original; window.location.hash = ""; });

  it("opens the panel on the left, and a file takes the place of the page until another page is chosen", async () => {
    const ADMIN = { id: 1, username: "admin", displayName: "Administrator", role: "ADMIN", enabled: true,
      mustChangePassword: false, lastLoginAt: null, passwordChangedAt: null, version: 0 };
    const { fetcher } = fakeFetch({
      "GET /api/auth/session": () => ({ body: { user: ADMIN, csrfToken: "csrf-abc" } }),
      "GET /api/auth/me": () => ({ body: { name: "admin", role: "ADMIN", kind: "USER", user: ADMIN } }),
      "GET /api/usage": () => ({ body: [] }),
      "GET /api/projects": () => ({ body: PROJECTS }),
      "GET /api/projects/3/browse": () => ({ body: ROOT }),
      "GET /api/projects/3/browse/file": () => ({ body: view("README.md", "TEXT", { content: "# Shop\n" }) }),
    });
    globalThis.fetch = fetcher;
    window.location.hash = "#/usage";
    render(<App />);

    const toggle = await screen.findByRole("button", { name: /^File$/ });
    expect(screen.queryByRole("complementary", { name: "File dei progetti" })).toBeNull();
    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-pressed")).toBe("true");
    const panel = await screen.findByRole("complementary", { name: "File dei progetti" });

    fireEvent.click(await within(panel).findByRole("button", { name: /README\.md/ }));
    expect(await screen.findByText("# Shop")).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Consumi e quote", hidden: true }).closest(".content")!.hasAttribute("hidden")).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: /Modelli LLM/ }));
    await waitFor(() => expect(screen.queryByText("# Shop")).toBeNull());
  });
});
