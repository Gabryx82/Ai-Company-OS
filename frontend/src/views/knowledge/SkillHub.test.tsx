import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../../api/client";
import { ApiContext } from "../../context";
import { fakeFetch } from "../../test/fakeServer";
import { KnowledgeHub } from "../KnowledgeHub";

const app = (id: string, name: string, installable = false) => ({
  id, name, description: `${name} desc`, detected: true, software: null, targets: [], agents: [],
  locations: [], count: 0, installable,
});

const skill = (over: Record<string, unknown>) => ({
  id: "x", app: "claude-code", location: "claude-code.personal", scope: "PERSONAL", kind: "SKILL", name: "x",
  description: null, category: "sviluppo", categoryLabel: "Sviluppo software", plugin: null, pluginVersion: null,
  marketplace: null, enabled: null, version: null, path: "C:/x/SKILL.md", writable: true, modified: null, libraryKey: null,
  copyStatus: null, provenance: null, copies: [], updateAvailable: false, ...over,
});

const INSTALLED = {
  scannedAt: "2026-10-07T10:00:00Z", lastSync: null, syncIntervalSeconds: 300,
  apps: [app("aicos", "AI Company OS", true), app("claude-code", "Claude Code", true), app("claude-desktop", "Claude Desktop"),
    app("codex", "Codex · ChatGPT"),
    { ...app("agents", "Agent Skills condivise", true), locations: [{ id: "agents.personal", label: "Skill personali condivise",
      scope: "PERSONAL", path: "C:/Users/me/.agents/skills", exists: true, writable: true, readers: ["codex", "gemini-cli"], count: 1 }] }],
  skills: [
    skill({ id: "aicos:pdf", app: "aicos", location: "aicos.library", scope: "LIBRARY", name: "pdf", libraryKey: "pdf",
      provenance: { provider: "skills-sh", providerName: "skills.sh", reference: "anthropics/skills:skills/pdf", sourceUrl: null,
        installedVersion: null, latestVersion: null, installedAt: null, checkedAt: null, updateAvailable: false,
        localChanges: false, checkError: null } }),
    skill({ id: "c1", name: "commit-helper", description: "Writes commit messages" }),
    skill({ id: "x1", app: "codex", location: "codex.plugins", scope: "PLUGIN", plugin: "browser", pluginVersion: "1.2.0",
      name: "browser-use", writable: false, enabled: false, category: "browser", categoryLabel: "Browser e automazione" }),
    skill({ id: "x2", app: "codex", location: "codex.system", scope: "SYSTEM", name: "imagegen", writable: false }),
    skill({ id: "d1", app: "claude-desktop", location: "claude-desktop.account", scope: "PLUGIN", kind: "PLUGIN",
      name: "security-guidance", plugin: "security-guidance", pluginVersion: "2.0.8", writable: false, path: "C:/x/plugin.json",
      details: { Skill: "0", Comandi: "0", Agenti: "0", Hook: "sì", Connettori: "0", Stato: "attivo" } }),
    skill({ id: "d2", app: "claude-desktop", location: "claude-desktop.connectors", scope: "PLUGIN", kind: "CONNECTOR",
      name: "slack", description: "http · https://mcp.slack.com/mcp · da 8 fonti", writable: false, path: null,
      details: { Tipo: "http", Indirizzo: "https://mcp.slack.com/mcp", "Fornito da": "plugin design, plugin sales" } }),
    skill({ id: "a1", app: "agents", location: "agents.personal", name: "emil-design-eng", description: "Design engineering" }),
  ],
  categories: [{ key: "sviluppo", label: "Sviluppo software" }, { key: "browser", label: "Browser e automazione" }],
  notes: [], summary: { total: 4, apps: 2, libraryEntries: 1, updates: 0, copies: 0 },
};

const MARKETPLACES = [
  { key: "skillsmp", name: "SkillsMP", description: "Aggregatore", homepage: "https://skillsmp.com",
    searchUrl: "https://skillsmp.com/search?q={q}", api: null, repo: null, official: false, community: true },
  { key: "claude-plugins-official", name: "Claude Code · plugin ufficiali", description: "Plugin", homepage: "https://claude.com/plugins",
    searchUrl: null, api: "CLAUDE_MARKETPLACE", repo: "anthropics/claude-plugins-official", official: true, community: false,
    kind: "PLUGIN", marketplace: "claude-plugins-official", targets: ["claude-code"] },
  { key: "mcp-registry", name: "Registro ufficiale MCP", description: "Server MCP", homepage: "https://registry.modelcontextprotocol.io",
    searchUrl: null, api: "MCP_REGISTRY", repo: null, official: true, community: false, kind: "CONNECTOR", marketplace: null,
    targets: ["claude-code", "codex", "claude-desktop"] },
  { key: "smithery-servers", name: "Smithery · server MCP", description: "Server MCP", homepage: "https://smithery.ai",
    searchUrl: "https://smithery.ai/search?q={q}", api: null, repo: null, official: false, community: true, kind: "CONNECTOR",
    marketplace: null, targets: null },
];

function mount(routes: Parameters<typeof fakeFetch>[0]) {
  const { fetcher, calls } = fakeFetch({
    "GET /api/skill-hub/installed": () => ({ body: INSTALLED }),
    "GET /api/skill-hub/marketplaces": () => ({ body: [
      { key: "skills-sh", name: "skills.sh", description: "Directory", homepage: "https://skills.sh", searchUrl: "https://skills.sh/?q={q}",
        api: "SKILLS_SH", repo: null, official: false, community: true },
      { key: "skillsmp", name: "SkillsMP", description: "Aggregatore", homepage: "https://skillsmp.com",
        searchUrl: "https://skillsmp.com/search?q={q}", api: null, repo: null, official: false, community: true },
    ] }),
    ...routes,
  });
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  render(<ApiContext.Provider value={api}><KnowledgeHub /></ApiContext.Provider>);
  return calls;
}

/** PHASE 31 (ADR-036): the Knowledge Hub as one place -- installed everywhere, marketplaces, updates. */
describe("Knowledge Hub across applications", () => {
  beforeEach(() => localStorage.clear());

  it("shows every application's skills grouped by type and plugin, and imports one into the library", async () => {
    const calls = mount({
      "GET /api/skill-hub/installed/c1": () => ({ body: { skill: INSTALLED.skills[1], content: "---\nname: commit-helper\n---\n# Commit",
        truncated: false, files: [{ path: "SKILL.md", size: 30 }], folder: "C:/x" } }),
      "POST /api/skill-hub/installed/c1/import": () => ({ status: 201, body: { key: "commit-helper", name: "commit-helper" } }),
    });

    const nav = await screen.findByRole("navigation", { name: "Applicazioni" });
    expect(within(nav).getByText("Claude Code")).toBeTruthy();
    // Every application is a section; Codex's skills are grouped by plugin and by type.
    const codex = await screen.findByRole("region", { name: "Codex · ChatGPT" });
    expect(within(codex).getByText("Skill del plugin browser")).toBeTruthy();
    expect(within(codex).getByText("v1.2.0")).toBeTruthy();
    expect(within(codex).getByText("Skill di sistema")).toBeTruthy();
    // Claude Desktop: a plugin made only of hooks is there, and its connectors.
    const desktop = screen.getByRole("region", { name: "Claude Desktop" });
    expect(within(desktop).getByText("Plugin installati")).toBeTruthy();
    expect(within(desktop).getByText("security-guidance")).toBeTruthy();
    expect(within(desktop).getByText("hook")).toBeTruthy();
    expect(within(desktop).getByText("da 2 plugin")).toBeTruthy();
    expect(within(codex).getByText("disattivata")).toBeTruthy();

    // One application at a time, one type at a time.
    fireEvent.click(within(nav).getByRole("button", { name: /Codex · ChatGPT/ }));
    const types = screen.getByRole("group", { name: "Tipologia" });
    fireEvent.click(within(types).getByRole("button", { name: /Di sistema/ }));
    expect(screen.getByText("imagegen")).toBeTruthy();
    expect(screen.queryByText("browser-use")).toBeNull();
    fireEvent.click(within(types).getByRole("button", { name: /Tutte le tipologie/ }));
    // What Codex reads from the shared ~/.agents/skills is Codex's too, under that folder's name.
    expect(screen.getByText("emil-design-eng")).toBeTruthy();
    expect(screen.getByText("Lette anche da qui · Skill personali condivise")).toBeTruthy();
    fireEvent.click(within(nav).getByRole("button", { name: /Claude Code/ }));
    expect(screen.queryByText("emil-design-eng")).toBeNull();
    fireEvent.click(within(nav).getByRole("button", { name: /Tutte le app/ }));

    fireEvent.click(screen.getByRole("button", { name: /commit-helper/ }));
    expect((await screen.findByLabelText("Contenuto della skill")).textContent).toContain("# Commit");
    fireEvent.change(screen.getByLabelText("Chiave nella libreria"), { target: { value: "commit-rules" } });
    fireEvent.click(screen.getByRole("button", { name: /Importa nella libreria/ }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/import"))).toBe(true));
    expect(calls.find((c) => c.path.endsWith("/import"))!.body).toEqual({ key: "commit-rules" });
    expect(await screen.findByText(/Importata nella libreria: commit-helper/)).toBeTruthy();
  });

  it("filters by kind, and shows a connector without anything to import or open", async () => {
    const calls = mount({
      "GET /api/skill-hub/installed/d2": () => ({ body: { skill: INSTALLED.skills[5], content: "slack\n\nTipo: http",
        truncated: false, files: [], folder: "C:/x" } }),
    });
    const kinds = await screen.findByRole("group", { name: "Tipo" });
    fireEvent.click(within(kinds).getByRole("button", { name: /Connettori \(1\)/ }));
    expect(screen.queryByText("security-guidance")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /slack/ }));
    expect((await screen.findByLabelText("Contenuto della skill")).textContent).toContain("Tipo: http");
    expect(screen.getByText("plugin design, plugin sales")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Importa nella libreria/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /Apri cartella/ })).toBeNull();
    expect(calls.some((c) => c.path.endsWith("/open") || c.path.endsWith("/import"))).toBe(false);
  });

  it("searches the marketplaces, knows what is installed, previews a package and installs it where asked", async () => {
    const calls = mount({
      "GET /api/skill-hub/marketplaces/search": () => ({ body: { results: [
        { provider: "skills-sh", ref: "anthropics/skills/pdf", name: "pdf", description: null, author: "anthropics",
          source: "anthropics/skills", installs: 206133, version: null, url: "https://skills.sh/anthropics/skills/pdf", suspicious: false },
        { provider: "skills-sh", ref: "anthropics/skills/xlsx", name: "xlsx", description: "Spreadsheets", author: "anthropics",
          source: "anthropics/skills", installs: 1200, version: null, url: null, suspicious: false },
      ], errors: [{ provider: "clawhub", message: "clawhub.ai non risponde" }] } }),
      "GET /api/skill-hub/marketplaces/skills-sh/package": () => ({ body: { provider: "skills-sh", ref: "anthropics/skills/xlsx",
        name: "xlsx", description: "Spreadsheets", version: "1.0", sourceUrl: null, files: [{ path: "SKILL.md", size: 40 },
          { path: "recalc.py", size: 90 }], skillMd: "---\nname: xlsx\n---\n# Excel", security: null,
        warnings: ["Il pacchetto contiene script"] } }),
      "POST /api/skill-hub/install": () => ({ status: 201, body: { key: "xlsx", name: "xlsx", filePath: "skills/xlsx/SKILL.md",
        copies: [{ app: "claude-code", outcome: "CREATED", path: "x", backup: null, message: "Installata" }] } }),
    });

    fireEvent.click(await screen.findByRole("tab", { name: "Marketplace" }));
    expect(await screen.findByText("SkillsMP")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Cerca nei marketplace"), { target: { value: "pdf" } });
    fireEvent.click(screen.getByRole("button", { name: "Cerca nei marketplace" }));

    expect(await screen.findByText("installata come pdf")).toBeTruthy();
    expect(screen.getByText(/clawhub.ai non risponde/)).toBeTruthy();
    expect(screen.getByText("↓ 206.1 k")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Anteprima e installa" }));
    const dialog = await screen.findByRole("dialog");
    expect((await within(dialog).findByLabelText("SKILL.md del pacchetto")).textContent).toContain("# Excel");
    expect(within(dialog).getByText(/contiene script/)).toBeTruthy();
    // Only the applications the Hub can write to are offered.
    expect(within(dialog).queryByText("Codex · ChatGPT")).toBeNull();
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Claude Code/ }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Installa" }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/api/skill-hub/install")).toBe(true));
    expect(calls.find((c) => c.path === "/api/skill-hub/install")!.body)
      .toEqual({ provider: "skills-sh", ref: "anthropics/skills/xlsx", key: "xlsx", targets: ["claude-code"] });
    expect(await screen.findByText(/Installata nella libreria come «xlsx»/)).toBeTruthy();
  });

  it("finds connectors, asks what they need, and installs them in the applications chosen without keeping the key", async () => {
    const calls = mount({
      "GET /api/skill-hub/marketplaces": () => ({ body: MARKETPLACES }),
      "GET /api/skill-hub/marketplaces/search": () => ({ body: { results: [
        { provider: "mcp-registry", ref: "io.github.acme/files", name: "files", description: "Files", author: "io.github.acme",
          source: null, installs: null, version: "0.3.0", url: null, suspicious: false, kind: "CONNECTOR",
          targets: ["claude-code", "codex", "claude-desktop"], details: { Tipo: "locale", Richiede: "ACME_KEY" } },
        { provider: "mcp-registry", ref: "io.github.acme/templated", name: "templated", description: null, author: "io.github.acme",
          source: null, installs: null, version: null, url: null, suspicious: false, kind: "CONNECTOR", targets: [],
          details: { Tipo: "non installabile da qui" } },
      ], errors: [] } }),
      "GET /api/skill-hub/marketplaces/mcp-registry/item": () => ({ body: {
        provider: "mcp-registry", ref: "io.github.acme/files", kind: "CONNECTOR", name: "Acme Files", installName: "files",
        description: "Files", version: "0.3.0", sourceUrl: null, details: { Tipo: "locale", Avvio: "npx -y @acme/files-mcp@0.3.0" },
        inputs: [{ name: "ACME_KEY", kind: "ENV", required: true, secret: true, description: "API key", defaultValue: null }],
        targets: [
          { app: "claude-code", appName: "Claude Code", available: true, method: "CLI",
            command: "claude mcp add --scope user files -- cmd /c npx -y @acme/files-mcp@0.3.0", note: null },
          { app: "codex", appName: "ChatGPT · Codex", available: false, method: "CLI", command: "codex mcp add files", note: null },
          { app: "claude-desktop", appName: "Claude Desktop", available: true, method: "CONFIG",
            command: "claude_desktop_config.json → mcpServers.files", note: "Riavvia Claude Desktop" },
        ],
        warnings: ["Un connettore locale è un programma"] } }),
      "POST /api/skill-hub/connectors/install": () => ({ body: { results: [
        { target: "claude-code", status: "INSTALLED", message: "Connettore «files» aggiunto a Claude Code",
          command: "claude mcp add --scope user -e ACME_KEY=•••• files", output: "Added", confirmation: null, software: null },
        { target: "claude-desktop", status: "INSTALLED", message: "Connettore «files» aggiunto a Claude Desktop: riavvia l'app",
          command: null, output: null, confirmation: null, software: "claude-desktop" },
      ] } }),
    });

    fireEvent.click(await screen.findByRole("tab", { name: "Marketplace" }));
    const kinds = await screen.findByRole("tablist", { name: "Cosa cercare" });
    fireEvent.click(within(kinds).getByRole("tab", { name: /^Connettori/ }));
    // Only the catalogs of connectors: the skill ones are behind their own tab.
    expect((await screen.findAllByText("Registro ufficiale MCP")).length).toBeGreaterThan(0);
    expect(screen.queryByText("SkillsMP")).toBeNull();
    fireEvent.change(screen.getByLabelText("Cerca nei marketplace"), { target: { value: "acme" } });
    fireEvent.click(screen.getByRole("button", { name: "Cerca nei marketplace" }));
    await waitFor(() => expect(calls.some((c) => c.path.startsWith("/api/skill-hub/marketplaces/search"))).toBe(true));
    expect(calls.find((c) => c.path.startsWith("/api/skill-hub/marketplaces/search"))!.path).toContain("kind=CONNECTOR");
    expect(await screen.findByText("richiede ACME_KEY")).toBeTruthy();
    expect(screen.getByText("da configurare a mano")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Dettagli e installa" }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText(/programma/)).toBeTruthy();
    // Where it can go, as it would be done; an application not on this PC cannot be chosen.
    expect(within(dialog).getByText("claude mcp add --scope user files -- cmd /c npx -y @acme/files-mcp@0.3.0")).toBeTruthy();
    expect((within(dialog).getByRole("checkbox", { name: /ChatGPT · Codex/ }) as HTMLInputElement).disabled).toBe(true);
    const install = within(dialog).getByRole("button", { name: "Installa" }) as HTMLButtonElement;
    expect(install.disabled).toBe(true);
    const key = within(dialog).getByLabelText(/ACME_KEY/) as HTMLInputElement;
    expect(key.type).toBe("password");
    fireEvent.change(key, { target: { value: "sk-SECRET-1" } });
    fireEvent.click(install);

    await waitFor(() => expect(calls.some((c) => c.path === "/api/skill-hub/connectors/install")).toBe(true));
    expect(calls.find((c) => c.path === "/api/skill-hub/connectors/install")!.body).toEqual({
      provider: "mcp-registry", ref: "io.github.acme/files", targets: ["claude-code", "claude-desktop"], name: "files",
      values: { ACME_KEY: "sk-SECRET-1" }, replace: false });
    expect(await within(dialog).findByText(/aggiunto a Claude Desktop/)).toBeTruthy();
    expect(within(dialog).getAllByText("installato")).toHaveLength(2);
    expect(dialog.innerHTML).not.toContain("sk-SECRET-1");
    expect(await screen.findByText(/installato in: Claude Code, Claude Desktop/)).toBeTruthy();
  });

  it("installs a plugin with the application's CLI, and runs a declared command only once it was read and approved", async () => {
    const sha = "a".repeat(64);
    const calls = mount({
      "GET /api/skill-hub/marketplaces": () => ({ body: MARKETPLACES }),
      "GET /api/skill-hub/marketplaces/claude-plugins-official/browse": () => ({ body: [
        { provider: "claude-plugins-official", ref: "frontend-design", name: "frontend-design", description: "Distinctive UI",
          author: "Anthropic", source: "anthropics/claude-plugins-official", installs: null, version: null, url: null, suspicious: false,
          kind: "PLUGIN", targets: ["claude-code"], details: { Marketplace: "claude-plugins-official", Categoria: "development" } },
      ] }),
      "GET /api/skill-hub/marketplaces/claude-plugins-official/item": () => ({ body: {
        provider: "claude-plugins-official", ref: "frontend-design", kind: "PLUGIN", name: "frontend-design", installName: "frontend-design",
        description: "Distinctive UI", version: null, sourceUrl: null, details: { Marketplace: "claude-plugins-official" }, inputs: [],
        targets: [{ app: "claude-code", appName: "Claude Code", available: true, method: "CLI",
          command: "claude plugin install frontend-design@claude-plugins-official --scope user", note: null }],
        warnings: [] } }),
      "POST /api/skill-hub/plugins/install": (call) => ({ body: (call.body as { confirmation?: string }).confirmation
        ? { target: "claude-code", status: "INSTALLED", message: "Installato in Claude Code", command: null, output: null,
          confirmation: null, software: null }
        : { target: "claude-code", status: "NEEDS_CONFIRMATION", message: "Il marketplace dichiara un comando",
          command: "npx -y @anthropic/setup", output: null, confirmation: sha, software: null } }),
    });

    fireEvent.click(await screen.findByRole("tab", { name: "Marketplace" }));
    fireEvent.click(within(await screen.findByRole("tablist", { name: "Cosa cercare" })).getByRole("tab", { name: /^Plugin/ }));
    fireEvent.click(await screen.findByRole("button", { name: /plugin ufficiali.*cerca e installa qui/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Dettagli e installa" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(await within(dialog).findByRole("button", { name: "Installa" }));

    expect((await within(dialog).findByLabelText("Comando dichiarato")).textContent).toBe("npx -y @anthropic/setup");
    expect(calls.filter((c) => c.path === "/api/skill-hub/plugins/install")).toHaveLength(1);
    expect(calls.find((c) => c.path === "/api/skill-hub/plugins/install")!.body)
      .toEqual({ provider: "claude-plugins-official", ref: "frontend-design", target: "claude-code" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Ho letto il comando: installa" }));
    await waitFor(() => expect(calls.filter((c) => c.path === "/api/skill-hub/plugins/install")).toHaveLength(2));
    expect(calls.filter((c) => c.path === "/api/skill-hub/plugins/install")[1].body)
      .toEqual({ provider: "claude-plugins-official", ref: "frontend-design", target: "claude-code", confirmation: sha });
    expect(await within(dialog).findByText("Installato in Claude Code")).toBeTruthy();
  });

  it("applies the safe updates at once, and overwrites a change only after asking", async () => {
    const items = [
      { id: "mkt:pdf", type: "MARKETPLACE", app: "aicos", name: "pdf", key: "pdf", from: "1.0", to: "1.1", detail: "Nuova versione",
        applicable: true, needsOverwrite: false, software: null, command: null },
      { id: "pull:c9", type: "PULL", app: "claude-code", name: "api-review", key: "api-review", from: null, to: null,
        detail: "Cambiata sia in Claude Code sia nella libreria", applicable: true, needsOverwrite: true, software: null, command: null },
      { id: "plugin:claude-desktop:render", type: "PLUGIN", app: "claude-desktop", name: "render", key: null, from: "0.2.2", to: "0.3.0",
        detail: "Claude Desktop aggiorna i plugin da sé", applicable: false, needsOverwrite: false, software: "claude-desktop", command: null },
    ];
    const calls = mount({
      "GET /api/skill-hub/updates": () => ({ body: { checkedAt: "2026-10-07T10:00:00Z", items, errors: [] } }),
      "POST /api/skill-hub/updates/apply": (call) => ({ body: { results: (call.body as { ids: string[] }).ids.map((id) =>
        ({ id, outcome: "UPDATED", message: `ok ${id}` })) } }),
    });

    fireEvent.click(await screen.findByRole("tab", { name: "Aggiornamenti" }));
    expect(await screen.findByText("Plugin delle app")).toBeTruthy();
    expect(screen.getByText("0.2.2 → 0.3.0")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Aggiorna tutto (1)" }));
    await waitFor(() => expect(calls.filter((c) => c.path === "/api/skill-hub/updates/apply")).toHaveLength(1));
    expect(calls.find((c) => c.path === "/api/skill-hub/updates/apply")!.body).toEqual({ ids: ["mkt:pdf"], overwrite: false });

    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    fireEvent.click(await screen.findByRole("button", { name: "Riporta nella libreria e sovrascrivi" }));
    expect(calls.filter((c) => c.path === "/api/skill-hub/updates/apply")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Riporta nella libreria e sovrascrivi" }));
    await waitFor(() => expect(calls.filter((c) => c.path === "/api/skill-hub/updates/apply")).toHaveLength(2));
    expect(calls.filter((c) => c.path === "/api/skill-hub/updates/apply")[1].body).toEqual({ ids: ["pull:c9"], overwrite: true });
    confirm.mockRestore();
  });
});

/** PHASE 32 (TD-76): the heuristic chose the category; the operator corrects it, by name. */
describe("Knowledge Hub — a skill's category by hand", () => {
  beforeEach(() => localStorage.clear());

  it("chooses another category and gives it back to the heuristic", async () => {
    const calls = mount({
      "GET /api/skill-hub/installed/c1": () => ({ body: { skill: INSTALLED.skills[1], content: "# Commit",
        truncated: false, files: [], folder: "C:/x" } }),
      "PUT /api/skill-hub/categories": () => ({ status: 204 }),
    });
    fireEvent.click(await screen.findByRole("button", { name: /commit-helper/ }));
    const picker = await screen.findByLabelText("Categoria di commit-helper") as HTMLSelectElement;
    expect(picker.value).toBe("");
    expect(within(picker).getByText("Automatica (Sviluppo software)")).toBeTruthy();

    fireEvent.change(picker, { target: { value: "browser" } });
    await waitFor(() => expect(calls.filter((c) => c.method === "PUT")).toHaveLength(1));
    expect(calls.find((c) => c.method === "PUT")!.body).toEqual({ name: "commit-helper", category: "browser" });
    expect(await screen.findByText(/commit-helper: categoria «Browser e automazione»/)).toBeTruthy();

    fireEvent.change(picker, { target: { value: "" } });
    await waitFor(() => expect(calls.filter((c) => c.method === "PUT")).toHaveLength(2));
    expect(calls.filter((c) => c.method === "PUT")[1].body).toEqual({ name: "commit-helper", category: null });
  });
});

/** PHASE 32 (TD-84): a plugin built into Claude Code is listed, without a folder to open. */
describe("Knowledge Hub — Claude Code's built-in plugins", () => {
  beforeEach(() => localStorage.clear());

  it("shows a built-in plugin and offers no folder", async () => {
    const builtin = skill({ id: "b1", location: "claude-code.plugins", scope: "PLUGIN", kind: "PLUGIN", name: "agents-md",
      plugin: "agents-md", marketplace: "builtin", writable: false, path: null,
      details: { Tipo: "interno di Claude Code (@builtin)", Usato: "3 volte" } });
    const { fetcher } = fakeFetch({
      "GET /api/skill-hub/installed": () => ({ body: { ...INSTALLED, skills: [...INSTALLED.skills, builtin] } }),
      "GET /api/skill-hub/marketplaces": () => ({ body: [] }),
      "GET /api/skill-hub/installed/b1": () => ({ body: { skill: builtin, content: "agents-md", truncated: false, files: [], folder: "" } }),
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}><KnowledgeHub /></ApiContext.Provider>);

    fireEvent.click(await screen.findByRole("button", { name: /agents-md/ }));
    expect(await screen.findByText(/Un plugin interno arriva con Claude Code/)).toBeTruthy();
    expect(screen.getByText("interno di Claude Code (@builtin)")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Apri cartella/ })).toBeNull();
  });
});
