import { describe, expect, it } from "vitest";
import type { HubApp, HubSkill } from "../../api/types";
import { family, matches, NO_FILTERS, presence, sections, slug } from "./hubModel";

function app(id: string, name: string): HubApp {
  return { id, name, description: "", detected: true, software: null, targets: [], agents: [], locations: [], count: 0, installable: false };
}

function skill(over: Partial<HubSkill>): HubSkill {
  return {
    id: over.name ?? "x", app: "claude-code", location: "claude-code.personal", scope: "PERSONAL", kind: "SKILL", name: "x",
    description: null, category: "sviluppo", categoryLabel: "Sviluppo software", plugin: null, pluginVersion: null,
    marketplace: null, enabled: null, version: null, path: null, writable: true, modified: null, libraryKey: null,
    copyStatus: null, provenance: null, copies: null, updateAvailable: false, details: null, ...over,
  };
}

/** PHASE 31: the Knowledge Hub groups by application, then by type, plugins one by one. */
describe("hubModel", () => {
  const apps = [app("aicos", "AI Company OS"), app("claude-code", "Claude Code"), app("codex", "Codex · ChatGPT")];
  const skills = [
    skill({ id: "1", app: "codex", scope: "PLUGIN", plugin: "browser", pluginVersion: "1.2.0", name: "browser-use" }),
    skill({ id: "2", app: "codex", scope: "SYSTEM", name: "imagegen", category: "design" }),
    skill({ id: "3", app: "codex", scope: "PLUGIN", plugin: "github", name: "triage" }),
    skill({ id: "4", app: "claude-code", scope: "PERSONAL", name: "commit-helper", description: "Writes commit messages" }),
    skill({ id: "5", app: "codex", scope: "PLUGIN", plugin: "browser", name: "aa-first" }),
    skill({ id: "6", app: "aicos", scope: "LIBRARY", name: "Commit Helper", libraryKey: "commit-helper", updateAvailable: true }),
  ];

  it("groups each application's items by kind and type, with one group of skills per plugin carrying its version", () => {
    const withPlugins = [...skills,
      skill({ id: "p1", app: "codex", scope: "PLUGIN", kind: "PLUGIN", plugin: "browser", pluginVersion: "1.2.0", name: "browser" }),
      skill({ id: "m1", app: "codex", scope: "PLUGIN", kind: "CONNECTOR", name: "slack", details: { "Fornito da": "plugin a, plugin b" } }),
      skill({ id: "k1", app: "codex", scope: "KNOWLEDGE", kind: "KNOWLEDGE", name: "Memoria · Shop" })];
    const out = sections(apps, withPlugins);
    expect(out.map((s) => s.app.id)).toEqual(["aicos", "claude-code", "codex"]);
    const codex = out.find((s) => s.app.id === "codex")!;
    expect(codex.groups.map((g) => g.title)).toEqual(["Plugin installati", "Connettori (MCP)", "Skill di sistema",
      "Skill del plugin browser", "Skill del plugin github", "Knowledge, istruzioni e memoria"]);
    const browser = codex.groups.find((g) => g.title === "Skill del plugin browser")!;
    expect(browser.subtitle).toBe("v1.2.0");
    expect(browser.skills.map((s) => s.name)).toEqual(["aa-first", "browser-use"]);
    expect(codex.total).toBe(7);
    // The kind filter: only the connectors, everywhere.
    expect(withPlugins.filter((s) => matches(s, { ...NO_FILTERS, kind: "CONNECTOR" })).map((s) => s.id)).toEqual(["m1"]);
    // A connector is found by who provides it.
    expect(withPlugins.filter((s) => matches(s, { ...NO_FILTERS, query: "plugin b" })).map((s) => s.id)).toEqual(["m1"]);
  });

  it("filters by application, type, category, status and every word of the query", () => {
    expect(skills.filter((s) => matches(s, { ...NO_FILTERS, app: "codex", scope: "PLUGIN" })).map((s) => s.id)).toEqual(["1", "3", "5"]);
    expect(skills.filter((s) => matches(s, { ...NO_FILTERS, category: "design" })).map((s) => s.id)).toEqual(["2"]);
    expect(skills.filter((s) => matches(s, { ...NO_FILTERS, status: "updates" })).map((s) => s.id)).toEqual(["6"]);
    expect(skills.filter((s) => matches(s, { ...NO_FILTERS, query: "commit messages" })).map((s) => s.id)).toEqual(["4"]);
    expect(sections(apps, skills.filter((s) => matches(s, { ...NO_FILTERS, app: "claude-code" }))).map((s) => s.app.id))
      .toEqual(["claude-code"]);
  });

  it("knows the same skill in two applications by its name", () => {
    expect(family("Commit Helper")).toBe(family("commit-helper"));
    expect(presence(skills).get("SKILL:commit-helper")).toEqual(["claude-code", "aicos"]);
  });

  it("makes a library key out of any name", () => {
    expect(slug("Revisione delle API REST")).toBe("revisione-delle-api-rest");
    expect(slug("Accessibilità dei form!")).toBe("accessibilita-dei-form");
  });
});
