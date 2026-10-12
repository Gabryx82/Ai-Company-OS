import { describe, expect, it } from "vitest";
import { program } from "../test/programs";
import { fileTypeOf, openWith } from "./fileOpeners";

// The shipped catalog's declarations, abridged where nothing depends on the rest.
const INTELLIJ = program("intellij-junie", "IntelliJ IDEA + Junie", [".java", ".kt", ".kts", ".groovy", ".gradle",
  ".properties", ".sql", "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"]);
const NETBEANS = program("netbeans", "Apache NetBeans", [".jsp", ".jspf", ".tag", ".tagx", ".tld", ".form", "build.xml",
  "nbactions.xml"], { fileArgs: ["--open", "{file}"] });
const WEBSTORM = program("webstorm", "WebStorm", [".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".vue", ".svelte", ".astro",
  ".css", ".scss", ".sass", ".less", ".html", ".htm", "package.json", "tsconfig.json"]);
const VSCODE = program("vscode-continue", "Visual Studio Code + Continue.dev", [".md", ".mdx", ".json", ".jsonc", ".json5",
  ".yml", ".yaml", ".toml", ".xml", ".xsd", ".svg", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".css", ".scss", ".html",
  ".py", ".java", ".go", ".rs", ".rb", ".php", ".sh", ".bash", ".ps1", ".psm1", ".bat", ".cmd", ".sql", ".graphql",
  ".prisma", ".env", ".gitignore", ".gitattributes", ".editorconfig", ".dockerignore", "dockerfile", "docker-compose.yml",
  "makefile", "*"], { category: "AGENTIC_IDE", fileArgs: ["{folder}", "{file}"] });
const NOTEPAD = program("notepad-plus-plus", "Notepad++", [".txt", ".log", ".ini", ".cfg", ".conf", ".csv", ".tsv", ".nfo", "*"],
  { category: "EDITOR", openFolder: false });
const PYCHARM = program("pycharm", "PyCharm", [".py", ".pyi", ".ipynb", "requirements.txt", "pyproject.toml", "pipfile",
  "setup.cfg", "tox.ini"]);
const BLENDER = program("blender", "Blender", [".blend"], { category: "THREE_D", openFolder: false });
const ANTIGRAVITY = program("antigravity-ide", "Antigravity IDE", [], { category: "AGENTIC_IDE", fileArgs: ["{folder}", "{file}"] });
const POSTMAN = program("postman", "Postman", [], { category: "API", fileArgs: [], opensFiles: false });
const HUB = [INTELLIJ, NETBEANS, WEBSTORM, VSCODE, NOTEPAD, PYCHARM, BLENDER, ANTIGRAVITY, POSTMAN];

const suggested = (path: string, hub = HUB, kind?: Parameters<typeof openWith>[2]) =>
  openWith(hub, path, kind).suggested?.software.key ?? null;

describe("«Apri con»: the program that suits a file (ADR-040)", () => {
  it("suggests the program made for the file, and the suggestion follows the file", () => {
    expect(suggested("src/main/java/App.java")).toBe("intellij-junie");
    expect(suggested("frontend/src/App.tsx")).toBe("webstorm");
    expect(suggested("scripts/train.py")).toBe("pycharm");
    expect(suggested("docs/adr/ADR-040.md")).toBe("vscode-continue");
    expect(suggested("web/index.jsp")).toBe("netbeans");
    expect(suggested("art/scene.blend")).toBe("blender");
    expect(suggested("logs/backend.log")).toBe("notepad-plus-plus");
  });

  it("a file name the catalog names beats its extension", () => {
    const pom = openWith(HUB, "backend/pom.xml");
    expect(pom.suggested?.software.key).toBe("intellij-junie");
    expect(pom.suggested?.reason).toBe("Fatto per Progetto Maven (pom.xml)");
    expect(pom.suitable.map((c) => c.software.key)).toEqual(["vscode-continue", "notepad-plus-plus"]);
    expect(suggested("frontend/package.json")).toBe("webstorm");
    expect(suggested("Dockerfile")).toBe("vscode-continue");
  });

  it("among programs that declare the extension, the more specialised one comes first", () => {
    const choice = openWith(HUB, "src/App.java");
    expect(choice.suggested?.reason).toBe("Adatto ai file .java");
    expect(choice.suitable.map((c) => c.software.key)).toEqual(["vscode-continue", "notepad-plus-plus"]);
    expect(choice.others.map((c) => c.software.key)).toEqual(["netbeans", "antigravity-ide", "blender", "pycharm", "webstorm"]
      .sort((a, b) => HUB.find((s) => s.key === a)!.name.localeCompare(HUB.find((s) => s.key === b)!.name, "it")));
  });

  it("any text file goes to the lighter editor, a binary one to nobody in particular", () => {
    expect(suggested("LICENSE")).toBe("notepad-plus-plus");
    expect(openWith(HUB, "LICENSE").suggested?.reason).toBe("Apre qualsiasi file di testo");
    const image = openWith(HUB, "assets/logo.png");
    expect(image.suggested).toBeNull();
    expect(image.type.label).toBe("Immagine PNG");
    expect(image.others.length).toBe(8);
    // Once read, the viewer knows: an unknown extension that is text is text.
    expect(suggested("data/export.xyz", HUB, "TEXT")).toBe("notepad-plus-plus");
    expect(suggested("data/export.xyz", HUB, "BINARY")).toBeNull();
  });

  it("a program that cannot receive a file now is listed with the reason, never suggested", () => {
    const hub = [INTELLIJ, { ...WEBSTORM, opensFiles: false, availability: "NOT_INSTALLED" as const, resolvedExecutable: null },
      { ...VSCODE, opensFiles: false, resolvedExecutable: null }, NOTEPAD];
    const choice = openWith(hub, "src/App.tsx");
    expect(choice.suggested?.software.key).toBe("notepad-plus-plus");
    expect(choice.unavailable.map((c) => [c.software.key, c.unavailable])).toEqual([
      ["webstorm", "Non installato"],
      ["vscode-continue", "Aperto dal menu Start: non riceve un file"],
    ]);
    expect(openWith(hub, "src/App.tsx").others.map((c) => c.software.key)).toEqual(["intellij-junie"]);
  });

  it("programs the catalog does not let open files are not offered", () => {
    const keys = (choice: ReturnType<typeof openWith>) =>
      [choice.suggested, ...choice.suitable, ...choice.others, ...choice.unavailable].map((c) => c?.software.key);
    expect(keys(openWith(HUB, "README.md"))).not.toContain("postman");
  });

  it("names the kind of file the way the operator does", () => {
    expect(fileTypeOf("src/App.tsx")).toEqual({ name: "app.tsx", extension: ".tsx", label: "TypeScript React", text: true });
    expect(fileTypeOf(".gitignore").label).toBe("Git ignore");
    expect(fileTypeOf("notes/strano.abc")).toEqual({ name: "strano.abc", extension: ".abc", label: "File .abc", text: null });
    expect(fileTypeOf("Makefile").label).toBe("Makefile");
  });
});
