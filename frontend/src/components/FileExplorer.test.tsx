import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { FileExplorer, FileViewer } from "./FileExplorer";
import { App } from "../App";
import { program } from "../test/programs";

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

// --- ADR-040: «Apri con» and the context menu ---------------------------------------------

const SOFTWARE = [
  program("intellij-junie", "IntelliJ IDEA + Junie", [".java", "pom.xml"]),
  program("webstorm", "WebStorm", [".ts", ".tsx", ".css"]),
  program("vscode-continue", "Visual Studio Code + Continue.dev", [".md", ".json", ".ts", ".tsx", ".java", "*"],
    { fileArgs: ["{folder}", "{file}"] }),
  program("notepad-plus-plus", "Notepad++", [".txt", ".log", "*"]),
  program("postman", "Postman", [], { fileArgs: [], opensFiles: false }),
];

const launched = (key: string, command: string[]) => () => ({ status: 202, body: { key, command,
  workingDirectory: "C:\\work\\shop", folderOpened: false, fileOpened: true } });

describe("«Apri con» in the viewer (ADR-040)", () => {
  it("suggests the program that suits the file, and a new file brings a new suggestion", async () => {
    const { fetcher, calls } = fakeFetch({
      "GET /api/software": () => ({ body: SOFTWARE }),
      "GET /api/projects/3/browse/file": (call) => ({ body: view(decodeURIComponent(call.path.split("path=")[1]), "TEXT",
        { content: "x\n" }) }),
      "POST /api/software/intellij-junie/launch": launched("intellij-junie", ["C:\\Apps\\idea64.exe", "C:\\work\\shop\\src\\App.java"]),
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    const viewer = (path: string) => (
      <ApiContext.Provider value={api}><FileViewer file={{ projectId: 3, path }} onClose={() => undefined} /></ApiContext.Provider>);
    const { rerender } = render(viewer("src/App.java"));

    fireEvent.click(await screen.findByRole("button", { name: /Apri con IntelliJ IDEA$/ }));

    expect(await screen.findByText("Aperto con IntelliJ IDEA")).toBeTruthy();
    expect(screen.getByText("C:\\Apps\\idea64.exe C:\\work\\shop\\src\\App.java")).toBeTruthy();
    const launch = calls.find((c) => c.method === "POST")!;
    expect(launch.path).toBe("/api/software/intellij-junie/launch");
    expect(launch.body).toEqual({ projectId: 3, path: "src/App.java" });

    rerender(viewer("src/App.tsx"));
    expect(await screen.findByRole("button", { name: /Apri con WebStorm$/ })).toBeTruthy();
    expect(screen.queryByText("Aperto con IntelliJ IDEA")).toBeNull();
    // The Software Hub was read once for both files.
    expect(calls.filter((c) => c.path === "/api/software")).toHaveLength(1);
  });

  it("lists every program that opens files, the suggested one first, and opens with the one chosen", async () => {
    const calls = withApi({
      "GET /api/software": () => ({ body: SOFTWARE }),
      "GET /api/projects/3/browse/file": () => ({ body: view("src/App.java", "TEXT", { content: "x\n" }) }),
      "POST /api/software/notepad-plus-plus/launch": launched("notepad-plus-plus", ["C:\\Apps\\notepad++.exe", "x"]),
    }, <FileViewer file={{ projectId: 3, path: "src/App.java" }} onClose={() => undefined} />);
    await screen.findByRole("button", { name: /Apri con IntelliJ IDEA$/ });

    fireEvent.click(screen.getByRole("button", { name: "Scegli con quale programma aprire" }));
    const menu = screen.getByRole("menu", { name: "Apri con" });

    expect(within(menu).getByText("Consigliato per Java")).toBeTruthy();
    const items = within(menu).getAllByRole("menuitem");
    expect(items.map((item) => item.textContent)).toEqual([
      expect.stringContaining("IntelliJ IDEA + JunieAdatto ai file .java"),
      expect.stringContaining("Visual Studio Code + Continue.devAdatto ai file .java"),
      expect.stringContaining("Notepad++Apre qualsiasi file di testo"),
      expect.stringContaining("WebStorm"),
    ]);
    expect(within(items[0]).getByText("Consigliato")).toBeTruthy();
    expect(within(menu).queryByText(/Postman/)).toBeNull();

    fireEvent.click(items[2]);
    await screen.findByText("Aperto con Notepad++");
    expect(calls.find((c) => c.method === "POST")!.path).toBe("/api/software/notepad-plus-plus/launch");
    expect(screen.queryByRole("menu")).toBeNull();
  });
});

describe("the context menu of the explorer (ADR-040)", () => {
  const browse = {
    "GET /api/projects/3/browse": (call: { path: string }) => ({ body: call.path.includes("path=src") ? SRC : ROOT }),
    "GET /api/software": () => ({ body: SOFTWARE }),
  };
  const explorer = (props: Partial<Parameters<typeof FileExplorer>[0]> = {}) => (
    <FileExplorer projectHint={null} opened={null} onOpen={() => undefined} onClose={() => undefined} {...props} />);
  const rightClick = async (name: RegExp) => {
    fireEvent.contextMenu(await screen.findByRole("button", { name }), { clientX: 40, clientY: 80 });
    return screen.findByRole("menu");
  };

  it("offers what a file can do, with the program that suits it", async () => {
    withApi(browse, explorer());

    const menu = await rightClick(/README\.md/);

    expect(menu.getAttribute("aria-label")).toBe("Azioni su README.md");
    // Drawn in <body>: inside the sticky panel it went under the page next to it.
    expect(menu.parentElement).toBe(document.body);
    expect(screen.getByRole("complementary", { name: "File dei progetti" }).contains(menu)).toBe(false);
    expect(await within(menu).findByRole("menuitem", { name: /Apri con Visual Studio Code/ })).toBeTruthy();
    for (const label of [/^Apri$/, /^Apri con…/, /Mostra in Esplora risorse/, /^Copia percorso$/, /Copia percorso relativo/,
      /Rinomina…/, /Sposta nel Cestino/]) {
      expect(within(menu).getByRole("menuitem", { name: label })).toBeTruthy();
    }
    expect(document.activeElement?.textContent).toBe("Apri");
    fireEvent.keyDown(menu, { key: "ArrowDown" });
    expect(document.activeElement?.getAttribute("role") === "menuitem" && document.activeElement.textContent).toMatch(/Apri con Visual Studio Code/);
    fireEvent.keyDown(menu, { key: "Escape" });
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("«Apri con…» shows every program, and Indietro goes back to the menu", async () => {
    withApi(browse, explorer());
    const menu = await rightClick(/README\.md/);
    await within(menu).findByRole("menuitem", { name: /Apri con Visual Studio Code/ });

    fireEvent.click(within(menu).getByRole("menuitem", { name: /^Apri con…/ }));
    expect(within(menu).getByText("Consigliato per Markdown")).toBeTruthy();
    expect(within(menu).getByRole("menuitem", { name: /Notepad\+\+/ })).toBeTruthy();

    fireEvent.click(within(menu).getByRole("menuitem", { name: "Indietro" }));
    expect(within(menu).getByRole("menuitem", { name: /Rinomina…/ })).toBeTruthy();
  });

  it("opens a file with the suggested program, copies its path and shows it in Explorer", async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    const calls = withApi({ ...browse,
      "POST /api/software/vscode-continue/launch": launched("vscode-continue", ["Code.exe", "C:\\work\\shop", "README.md"]),
      "POST /api/projects/3/browse/reveal": () => ({ status: 204 }),
    }, explorer());

    let menu = await rightClick(/README\.md/);
    fireEvent.click(await within(menu).findByRole("menuitem", { name: /Apri con Visual Studio Code/ }));
    expect(await screen.findByText("Aperto con Visual Studio Code")).toBeTruthy();
    expect(calls.find((c) => c.path === "/api/software/vscode-continue/launch")!.body).toEqual({ projectId: 3, path: "README.md" });

    menu = await rightClick(/README\.md/);
    fireEvent.click(within(menu).getByRole("menuitem", { name: /^Copia percorso$/ }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("C:\\work\\shop\\README.md"));

    menu = await rightClick(/README\.md/);
    fireEvent.click(within(menu).getByRole("menuitem", { name: /Copia percorso relativo/ }));
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith("README.md"));

    menu = await rightClick(/README\.md/);
    fireEvent.click(within(menu).getByRole("menuitem", { name: /Mostra in Esplora risorse/ }));
    await screen.findByText("Aperto in Esplora risorse");
    expect(calls.find((c) => c.path === "/api/projects/3/browse/reveal")!.body).toEqual({ path: "README.md" });
  });

  it("renames in place, and the file in the viewer follows its new name", async () => {
    const onOpenedChange = vi.fn();
    const calls = withApi({ ...browse,
      "POST /api/projects/3/browse/rename": () => ({ body: entry("LEGGIMI.md", "FILE", 12) }),
    }, explorer({ opened: { projectId: 3, path: "README.md", projectName: "Shop" }, onOpenedChange }));

    const menu = await rightClick(/README\.md/);
    fireEvent.click(within(menu).getByRole("menuitem", { name: /Rinomina…/ }));
    const input = screen.getByRole("textbox", { name: "Nuovo nome per README.md" }) as HTMLInputElement;
    expect(input.value).toBe("README.md");
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, 6]);
    fireEvent.change(input, { target: { value: "LEGGIMI.md" } });
    fireEvent.keyDown(input, { key: "Enter" });

    await waitFor(() => expect(onOpenedChange).toHaveBeenCalledWith({ projectId: 3, path: "LEGGIMI.md", projectName: "Shop" }));
    expect(calls.find((c) => c.path === "/api/projects/3/browse/rename")!.body).toEqual({ path: "README.md", name: "LEGGIMI.md" });
    expect(calls.filter((c) => c.path === "/api/projects/3/browse")).toHaveLength(2);
    expect(await screen.findByText("Rinominato in LEGGIMI.md")).toBeTruthy();
  });

  it("F2 starts a rename, Escape gives it up, and a refused name stays to be corrected", async () => {
    const calls = withApi({ ...browse,
      "POST /api/projects/3/browse/rename": () => ({ status: 409, problem: true, body: {
        type: "urn:ai-company-os:problem:workspace-entry-exists", title: "Name already taken", status: 409,
        detail: "'logo.png' esiste già" } }),
    }, explorer());

    const row = await screen.findByRole("button", { name: /README\.md/ });
    fireEvent.keyDown(row, { key: "F2" });
    fireEvent.keyDown(screen.getByRole("textbox", { name: "Nuovo nome per README.md" }), { key: "Escape" });
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(calls.some((c) => c.method === "POST")).toBe(false);

    fireEvent.keyDown(await screen.findByRole("button", { name: /README\.md/ }), { key: "F2" });
    const input = screen.getByRole("textbox", { name: "Nuovo nome per README.md" });
    fireEvent.change(input, { target: { value: "logo.png" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(await screen.findByText("'logo.png' esiste già")).toBeTruthy();
    expect(screen.getByRole("textbox", { name: "Nuovo nome per README.md" })).toBeTruthy();
  });

  it("moves a file to the Recycle Bin after saying where it goes, and closes it in the viewer", async () => {
    const onOpenedChange = vi.fn();
    const calls = withApi({ ...browse,
      "GET /api/projects/3/browse/deletion": () => ({ body: { kind: "FILE", subjects: [{ id: "README.md", name: "README.md" }],
        deletes: ["Il file «README.md» va nel Cestino di Windows."], detaches: [],
        keeps: ["Si ripristina dal Cestino di Windows, finché non lo svuoti."], files: [], blockers: [], allowed: true } }),
      "DELETE /api/projects/3/browse": () => ({ status: 204 }),
    }, explorer({ opened: { projectId: 3, path: "README.md" }, onOpenedChange }));

    const menu = await rightClick(/README\.md/);
    fireEvent.click(within(menu).getByRole("menuitem", { name: /Sposta nel Cestino/ }));
    const dialog = await screen.findByRole("dialog", { name: "Sposta nel Cestino" });
    expect(await within(dialog).findByText("Il file «README.md» va nel Cestino di Windows.")).toBeTruthy();
    expect(within(dialog).queryByText("File rimossi dal disco")).toBeNull();
    expect(within(dialog).getByText("Va nel Cestino")).toBeTruthy();
    const confirm = within(dialog).getByRole("button", { name: /Sposta nel Cestino/ });
    await waitFor(() => expect((confirm as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(confirm);

    await waitFor(() => expect(onOpenedChange).toHaveBeenCalledWith(null));
    expect(calls.find((c) => c.method === "DELETE")!.path).toBe("/api/projects/3/browse?path=README.md");
    expect(await screen.findByText("«README.md» è nel Cestino")).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("creates a file in a folder and opens it, or a folder at the root from the empty space", async () => {
    const onOpen = vi.fn();
    const calls = withApi({ ...browse,
      "POST /api/projects/3/browse/create": (call) => ({ status: 201,
        body: (call.body as { kind: string }).kind === "FILE" ? entry("src/Nuovo.ts", "FILE", 0) : entry("assets", "DIRECTORY") }),
    }, explorer({ onOpen }));

    const menu = await rightClick(/^src$/);
    fireEvent.click(within(menu).getByRole("menuitem", { name: /Nuovo file…/ }));
    const input = await screen.findByRole("textbox", { name: "Nome del nuovo file" });
    fireEvent.change(input, { target: { value: "Nuovo.ts" } });
    fireEvent.keyDown(input, { key: "Enter" });

    await waitFor(() => expect(onOpen).toHaveBeenCalledWith({ projectId: 3, path: "src/Nuovo.ts", projectName: "Shop" }));
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({ parent: "src", name: "Nuovo.ts", kind: "FILE" });

    fireEvent.contextMenu(screen.getByRole("tree", { name: "Cartella del progetto" }), { clientX: 20, clientY: 300 });
    const rootMenu = screen.getByRole("menu", { name: "Azioni sulla cartella di Shop" });
    fireEvent.click(within(rootMenu).getByRole("menuitem", { name: /Nuova cartella…/ }));
    const folderName = screen.getByRole("textbox", { name: "Nome della nuova cartella" });
    fireEvent.change(folderName, { target: { value: "assets" } });
    fireEvent.keyDown(folderName, { key: "Enter" });
    await screen.findByText("Creata la cartella assets");
    expect(calls.filter((c) => c.method === "POST").at(-1)!.body).toEqual({ parent: "", name: "assets", kind: "DIRECTORY" });
  });

  it("an archived project's files are read, not changed", async () => {
    const archived = PROJECTS.map((p) => (p.id === 3 ? { ...p, status: "ARCHIVED" } : p));
    const { fetcher } = fakeFetch({ "GET /api/projects": () => ({ body: archived }), ...browse });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}>{explorer()}</ApiContext.Provider>);

    const menu = await rightClick(/README\.md/);
    for (const label of [/Rinomina…/, /Sposta nel Cestino/]) {
      expect((within(menu).getByRole("menuitem", { name: label }) as HTMLButtonElement).disabled).toBe(true);
    }
    expect((screen.getByRole("button", { name: "Nuovo file" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
