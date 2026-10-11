// What the control plane answers, for the tests of the 3D view: agents as
// GET /api/ecosystem/bindings returns them, the Hub as GET /api/skill-hub/installed.
import type { AgentConfiguration, Binding, HubApp, HubInstalled, HubSkill, ResourceRef } from "../../api/types";

export function binding(target: string, model?: { key: string; name: string; provider: string; kind: string }): Binding {
  return {
    model: model ? { key: model.key, displayName: model.name, role: "CODER", lifecycle: "ACTIVE", replacedBy: null, catalogued: true,
      engineRunnable: target === "engine" } : null,
    provider: model ? { key: model.provider, name: model.provider === "ollama" ? "Ollama" : "Anthropic", kind: model.kind,
      billing: model.kind === "LOCAL" ? "FREE" : "SUBSCRIPTION", status: "ACTIVE" } : null,
    target: { key: target, name: target === "engine" ? "AI Engine" : "Claude Code", delivery: target === "engine" ? "ENGINE_RUN" : "CLI_PROMPT",
      software: target === "engine" ? null : "claude-code", availability: "INSTALLED", providers: [], contextFile: null, howItWorks: "Come lavora." },
    valid: true, problems: [], warnings: [], summary: target,
  };
}

export function resource(key: string, kind: string, extra: Partial<ResourceRef> = {}): ResourceRef {
  return { key, name: key.replace(/-/g, " "), kind, description: `La risorsa ${key}`, configuration: null, ...extra };
}

export function agent(id: number, name: string, extra: Partial<AgentConfiguration> = {}): AgentConfiguration {
  return {
    id, name, role: "Backend Engineer", specialization: "Java", description: null, capabilities: [], status: "ACTIVE",
    parent: null, children: [], domain: null, systemPrompt: null, responsibilities: null, limits: null, outputFormat: null,
    directives: [], contextPolicy: null, resources: {}, software: [], model: null, executionTarget: "engine", binding: binding("engine"),
    origin: "TEMPLATE", defaults: null, modified: [], customizedAt: null, customizedBy: null, version: id * 10, ...extra,
  };
}

export const QWEN = { key: "qwen3.5:4b", name: "Qwen 3.5 4B", provider: "ollama", kind: "LOCAL" };
export const OPUS = { key: "claude-opus", name: "Claude Opus", provider: "anthropic", kind: "SUBSCRIPTION" };

/** Two agents and a sub-agent: one shared skill, one local and one cloud model, one in Claude Code. */
export function team(): AgentConfiguration[] {
  return [
    agent(1, "Backend Bot", {
      model: QWEN.key, binding: binding("engine", QWEN), capabilities: ["Scrive API REST", "Testa il codice"],
      systemPrompt: "Sei un backend engineer. La chiave è sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUV, non mostrarla.",
      directives: ["Prima i test", "Mai toccare il database di produzione"], responsibilities: "Le API", limits: "Niente deploy",
      resources: { SKILL: [resource("clean-code", "SKILL")], MCP: [resource("postgres-mcp", "MCP", { configuration: "PGPASSWORD=supersecret npx mcp-postgres" })] },
      software: [{ key: "intellij", name: "IntelliJ IDEA" }],
      children: [{ id: 3, name: "Test Bot", role: "QA" }],
    }),
    agent(2, "Frontend Bot", {
      role: "Frontend Engineer", model: OPUS.key, executionTarget: "claude-code", binding: binding("claude-code", OPUS),
      capabilities: ["testa il codice"], resources: { SKILL: [resource("clean-code", "SKILL"), resource("react", "SKILL")],
        TEMPLATE_PROVIDER: [resource("dribbble", "TEMPLATE_PROVIDER")] },
    }),
    agent(3, "Test Bot", { role: "QA", parent: { id: 1, name: "Backend Bot", role: "Backend Engineer" }, status: "INACTIVE" }),
  ];
}

function app(id: string, name: string, targets: string[]): HubApp {
  return { id, name, description: "", detected: true, software: null, targets, agents: [], locations: [], count: 0, installable: false };
}

function hubSkill(id: string, appId: string, kind: string, name: string, extra: Partial<HubSkill> = {}): HubSkill {
  return {
    id, app: appId, location: `${appId}.personal`, scope: "PERSONAL", kind, name, description: `${name} desc`, category: "dev",
    categoryLabel: "Sviluppo", plugin: null, pluginVersion: null, marketplace: null, enabled: true, version: null,
    path: `C:\\Users\\someone\\.claude\\skills\\${name}`, writable: true, modified: null, libraryKey: null, copyStatus: null,
    provenance: null, copies: null, updateAvailable: false, details: null, ...extra,
  };
}

/** Claude Code with two skills (one a copy of the library's clean-code), a plugin and a connector; Cursor, which no agent works in. */
export function hub(): HubInstalled {
  return {
    scannedAt: "2026-10-10T10:00:00Z", lastSync: null, syncIntervalSeconds: 0,
    apps: [app("aicos", "AI Company OS", ["engine"]), app("claude-code", "Claude Code", ["claude-code"]), app("cursor", "Cursor", [])],
    skills: [
      hubSkill("lib1", "aicos", "SKILL", "clean code", { scope: "LIBRARY" }),
      hubSkill("s1", "claude-code", "SKILL", "pdf"),
      hubSkill("s2", "claude-code", "SKILL", "clean-code-copy", { libraryKey: "clean-code" }),
      hubSkill("p1", "claude-code", "PLUGIN", "engineering", { details: { Comandi: "3", Header: "Authorization: Bearer abcdefghijklmnopqrstuvwxyz" } }),
      hubSkill("c1", "claude-code", "CONNECTOR", "github", { enabled: false }),
      hubSkill("x1", "cursor", "SKILL", "cursor-only"),
    ],
    categories: [], notes: [],
    summary: { total: 6, apps: 3, libraryEntries: 1, updates: 0, copies: 0, skills: 4, plugins: 1, connectors: 1, knowledge: 0 },
  };
}
