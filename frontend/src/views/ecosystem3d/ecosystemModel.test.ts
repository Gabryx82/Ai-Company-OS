import { describe, expect, it } from "vitest";
import {
  INITIAL_VIEW, REDACTED, agentsOf, buildGraph, countByCategory, neighbours, redactSecrets, reveal, searchNodes, visibleGraph,
  type ViewState,
} from "./ecosystemModel";
import { agent, hub, team } from "./fixtures";

const ALL: ViewState = { ...INITIAL_VIEW, hidden: [] };

describe("the ecosystem graph (ADR-037)", () => {
  it("draws one node per agent and per thing that defines it, read from the bindings", () => {
    const g = buildGraph(team());
    const ids = g.nodes.map((n) => n.id);
    expect(ids).toEqual(expect.arrayContaining([
      "agent:1", "agent:2", "agent:3", "model:qwen3.5:4b", "model:claude-opus", "provider:ollama", "provider:anthropic",
      "target:engine", "target:claude-code", "res:clean-code", "res:react", "res:postgres-mcp", "sw:intellij",
      "systemPrompt:1", "responsibilities:1", "limits:1", "directive:1:0", "directive:1:1", "role:backend engineer", "role:qa",
    ]));
    expect(g.byId.get("res:postgres-mcp")!.category).toBe("mcp");
    // Every kind of harness resource has a place, template providers among the integrations.
    expect(g.byId.get("res:dribbble")).toMatchObject({ category: "plugin", subtitle: "Fonte di template" });
    expect(g.byId.get("limits:1")!.category).toBe("role");
    expect(g.byId.get("agent:3")!.status).toBe("inactive");
  });

  it("keeps what two agents share as one node with an edge from each", () => {
    const g = buildGraph(team());
    expect(g.nodes.filter((n) => n.id === "res:clean-code")).toHaveLength(1);
    expect(g.into.get("res:clean-code")!.map((e) => e.source).sort()).toEqual(["agent:1", "agent:2"]);
    // Capabilities are shared by their text, whatever the case.
    expect(g.into.get("capability:testa il codice")!.map((e) => e.source).sort()).toEqual(["agent:1", "agent:2"]);
  });

  it("chains model to provider, says local or cloud, and hangs sub-agents from their parent", () => {
    const g = buildGraph(team());
    expect(g.out.get("model:qwen3.5:4b")!.map((e) => e.target)).toEqual(["provider:ollama"]);
    const local = g.byId.get("model:qwen3.5:4b")!.detail;
    const cloud = g.byId.get("model:claude-opus")!.detail;
    expect(local.type === "model" && local.locality).toBe("LOCAL");
    expect(cloud.type === "model" && cloud.locality).toBe("SUBSCRIPTION");
    expect(g.edges.find((e) => e.kind === "subagent")).toMatchObject({ source: "agent:1", target: "agent:3" });
  });

  it("never carries a resource's configuration nor a secret written in a prompt", () => {
    const g = buildGraph(team(), hub());
    const everything = JSON.stringify(g.nodes.map((n) => [n.label, n.subtitle, n.search, n.detail]));
    expect(everything).not.toContain("supersecret");
    expect(everything).not.toContain("sk-ant-api03");
    expect(everything).not.toContain("abcdefghijklmnopqrstuvwxyz");
    expect(everything).not.toContain("C:\\\\Users");
    const mcp = g.byId.get("res:postgres-mcp")!.detail;
    expect(mcp.type === "resource" && mcp.configured).toBe(true);
    const prompt = g.byId.get("systemPrompt:1")!.detail;
    expect(prompt.type === "text" && prompt.text).toContain(`La chiave è ${REDACTED}`);
  });

  it("brings in the skills, plugins and connectors of the apps agents work in, and only those", () => {
    const g = buildGraph(team(), hub());
    expect(g.byId.get("hubgroup:claude-code:SKILL")!.detail).toMatchObject({ type: "group", count: 2 });
    expect(g.byId.get("hubgroup:claude-code:PLUGIN")!.category).toBe("plugin");
    expect(g.byId.get("hubgroup:claude-code:CONNECTOR")!.category).toBe("mcp");
    expect(g.into.get("hubgroup:claude-code:SKILL")!.map((e) => e.source)).toEqual(["target:claude-code"]);
    // Cursor: no agent works there. The library: its entries are the harness resources already drawn.
    expect(g.nodes.some((n) => n.id.includes("cursor") || n.id.includes("aicos") || n.id === "hub:lib1")).toBe(false);
    // The copy of a library skill is that skill: the cluster points to it, no second node.
    expect(g.byId.has("hub:s2")).toBe(false);
    expect(g.out.get("hubgroup:claude-code:SKILL")!.map((e) => e.target).sort()).toEqual(["hub:s1", "res:clean-code"]);
    expect(g.byId.get("hub:c1")!.status).toBe("inactive");
  });

  it("redacts keys, tokens, passwords and credentials in URLs but leaves the prose readable", () => {
    expect(redactSecrets("usa sk-proj-abcdefghijklmnopqrstu per l'API")).toBe(`usa ${REDACTED} per l'API`);
    expect(redactSecrets("Authorization: Bearer abc.def-ghijklmnop")).toBe(`Authorization: Bearer ${REDACTED}`);
    expect(redactSecrets("API_KEY=12345678 e password: hunter22")).toBe(`API_KEY=${REDACTED} e password: ${REDACTED}`);
    expect(redactSecrets("postgres://admin:s3cr3t@localhost/db")).toBe(`postgres://admin:${REDACTED}@localhost/db`);
    expect(redactSecrets("ghp_abcdefghijklmnopqrstuvwxyz123456")).toBe(REDACTED);
    expect(redactSecrets("Scrivi i test prima del codice.")).toBe("Scrivi i test prima del codice.");
  });
});

describe("what the view shows", () => {
  it("starts with the ecosystem, without the per-agent text", () => {
    const v = visibleGraph(buildGraph(team()), INITIAL_VIEW);
    expect(v.ids.has("agent:3")).toBe(true);
    expect(v.ids.has("res:clean-code")).toBe(true);
    expect(v.ids.has("systemPrompt:1")).toBe(false);
    expect(v.ids.has("directive:1:0")).toBe(false);
    expect(v.nodes.slice(0, 3).every((n) => n.category === "agent")).toBe(true);
  });

  it("hides a category, keeps a cluster closed until expanded", () => {
    const g = buildGraph(team(), hub());
    const shut = visibleGraph(g, ALL);
    expect(shut.ids.has("hubgroup:claude-code:SKILL")).toBe(true);
    expect(shut.ids.has("hub:s1")).toBe(false);
    const open = visibleGraph(g, { ...ALL, expanded: ["hubgroup:claude-code:SKILL"] });
    expect(open.ids.has("hub:s1")).toBe(true);
    expect(open.owners.get("res:clean-code")!.sort()).toEqual(["agent:1", "agent:2", "hubgroup:claude-code:SKILL"]);
    const noSkills = visibleGraph(g, { ...ALL, hidden: ["skill"], expanded: ["hubgroup:claude-code:SKILL"] });
    expect([...noSkills.ids].some((id) => id === "res:clean-code" || id.startsWith("hubgroup:claude-code:SKILL"))).toBe(false);
  });

  it("collapsing an agent hides what is only its own, not what another shown agent shares", () => {
    const g = buildGraph(team());
    const v = visibleGraph(g, { ...ALL, collapsed: [1] });
    expect(v.ids.has("agent:1")).toBe(true);
    expect(v.ids.has("res:postgres-mcp")).toBe(false);
    expect(v.ids.has("res:clean-code")).toBe(true);
    expect(v.edges.some((e) => e.source === "agent:1" && e.target === "res:clean-code")).toBe(true);
    expect(v.hiddenOf.get(1)).toBeGreaterThan(5);
  });

  it("filters by agent, and shows one agent with everything it has and its parent and sub-agents around it", () => {
    const g = buildGraph(team());
    const filtered = visibleGraph(g, { ...ALL, agents: [2] });
    expect([...filtered.ids].filter((id) => id.startsWith("agent:"))).toEqual(["agent:2"]);
    expect(filtered.ids.has("res:postgres-mcp")).toBe(false);

    const single = visibleGraph(g, { ...ALL, mode: "agent", focus: 3 });
    expect([...single.context]).toEqual([1]);
    expect(single.ids.has("agent:1")).toBe(true);
    expect(single.ids.has("res:postgres-mcp")).toBe(false);
    expect(single.edges.some((e) => e.kind === "subagent")).toBe(true);
    const focusBackend = visibleGraph(g, { ...ALL, mode: "agent", focus: 1 });
    expect(focusBackend.ids.has("systemPrompt:1")).toBe(true);
    expect(focusBackend.ids.has("agent:2")).toBe(false);
  });

  it("reveals a search hit: its category, its cluster, one of its agents", () => {
    const g = buildGraph(team(), hub());
    const fromDefault = reveal(g, INITIAL_VIEW, "directive:1:1");
    expect(fromDefault.hidden).not.toContain("directive");
    expect(visibleGraph(g, fromDefault).ids.has("directive:1:1")).toBe(true);
    const member = reveal(g, ALL, "hub:p1");
    expect(member.expanded).toEqual(["hubgroup:claude-code:PLUGIN"]);
    expect(visibleGraph(g, member).ids.has("hub:p1")).toBe(true);
    const filtered = reveal(g, { ...ALL, agents: [2] }, "res:postgres-mcp");
    expect(visibleGraph(g, filtered).ids.has("res:postgres-mcp")).toBe(true);
    const single = reveal(g, { ...ALL, mode: "agent", focus: 2 }, "res:postgres-mcp");
    expect(single.focus).toBe(1);
    expect(reveal(g, ALL, "agent:1")).toBe(ALL);
  });
});

describe("searching and relations", () => {
  it("ranks names before text, and agents first", () => {
    const g = buildGraph(team(), hub());
    expect(searchNodes(g, "bot").slice(0, 3).map((h) => h.node.id)).toEqual(["agent:1", "agent:2", "agent:3"]);
    const hits = searchNodes(g, "produzione");
    expect(hits.map((h) => [h.node.id, h.where])).toEqual([["directive:1:1", "text"]]);
    expect(searchNodes(g, "x")).toEqual([]);
  });

  it("knows a node's neighbours and the agents it belongs to", () => {
    const g = buildGraph(team(), hub());
    expect([...neighbours(g, "res:clean-code")].sort()).toEqual(["agent:1", "agent:2", "hubgroup:claude-code:SKILL", "res:clean-code"]);
    expect(agentsOf(g, "hub:s1")).toEqual([2]);
    expect(agentsOf(g, "provider:ollama")).toEqual([1]);
  });

  it("counts by category, clusters aside", () => {
    const counts = countByCategory(buildGraph(team(), hub()).nodes);
    expect(counts.agent).toBe(3);
    expect(counts.skill).toBe(3);
    expect(counts.plugin).toBe(2);
  });

  it("a graph with no agents is empty, and an agent without model has no model node", () => {
    expect(buildGraph([]).nodes).toEqual([]);
    const g = buildGraph([agent(9, "Solo")]);
    expect(g.nodes.some((n) => n.category === "model")).toBe(false);
    expect(g.byId.has("target:engine")).toBe(true);
  });
});
