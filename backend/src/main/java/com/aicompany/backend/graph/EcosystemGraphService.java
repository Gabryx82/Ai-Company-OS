package com.aicompany.backend.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Second Brain (directive §17): the whole ecosystem as one graph -- projects,
 * phases, tasks, agents and sub-agents, models, providers, software, skills,
 * knowledge, MCP, tools, documents and the day's work -- assembled from the
 * index at read time. Nothing is stored twice: a node is a row, an edge is a
 * foreign key or a link row, read in one pass of plain queries.
 */
@Service
@Transactional(readOnly = true)
public class EcosystemGraphService {

    public record Node(String id, String type, String label, String status, Map<String, Object> meta) {
    }

    public record Edge(String source, String target, String kind) {
    }

    public record Graph(List<Node> nodes, List<Edge> edges) {
    }

    private final JdbcTemplate jdbc;

    public EcosystemGraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Graph graph() {
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();

        jdbc.query("SELECT id, name, status, project_type, plan_status, workspace_path FROM projects", rs -> {
            String id = "project:" + rs.getLong(1);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("projectType", rs.getString(4));
            meta.put("planStatus", rs.getString(5));
            meta.put("workspace", rs.getString(6));
            nodes.put(id, new Node(id, "project", rs.getString(2), rs.getString(3), meta));
            if (rs.getString(6) != null) {
                String master = "document:" + rs.getLong(1) + ":MASTER_PROMPT.md";
                nodes.put(master, new Node(master, "document", "MASTER_PROMPT.md", null, Map.of("path", "MASTER_PROMPT.md")));
                edges.add(new Edge(id, master, "has-document"));
            }
            if (!"NONE".equals(rs.getString(5))) {
                String plan = "document:" + rs.getLong(1) + ":IMPLEMENTATION_PLAN.md";
                nodes.put(plan, new Node(plan, "document", "IMPLEMENTATION_PLAN.md", null,
                        Map.of("path", "docs/IMPLEMENTATION_PLAN.md")));
                edges.add(new Edge(id, plan, "has-document"));
            }
        });
        jdbc.query("SELECT id, project_id, number, title, status, approval FROM project_phases", rs -> {
            String id = "phase:" + rs.getLong(1);
            nodes.put(id, new Node(id, "phase", "Fase " + rs.getInt(3) + " — " + rs.getString(4), rs.getString(5),
                    Map.of("approval", rs.getString(6))));
            edges.add(new Edge("project:" + rs.getLong(2), id, "contains"));
        });
        jdbc.query("SELECT id, title, status, priority, project_id, phase_id, agent_id, code FROM tasks", rs -> {
            String id = "task:" + rs.getLong(1);
            String code = rs.getString(8);
            nodes.put(id, new Node(id, "task", (code == null ? "#" + rs.getLong(1) : code) + " " + rs.getString(2),
                    rs.getString(3), Map.of("priority", rs.getString(4))));
            long phase = rs.getLong(6);
            if (!rs.wasNull()) {
                edges.add(new Edge("phase:" + phase, id, "contains"));
            } else {
                long project = rs.getLong(5);
                if (!rs.wasNull()) {
                    edges.add(new Edge("project:" + project, id, "contains"));
                }
            }
            long agent = rs.getLong(7);
            if (!rs.wasNull()) {
                edges.add(new Edge(id, "agent:" + agent, "assigned-to"));
            }
        });
        Set<String> usedModels = new HashSet<>();
        jdbc.query("SELECT id, name, role, status, model, parent_id FROM agents", rs -> {
            String id = "agent:" + rs.getLong(1);
            long parent = rs.getLong(6);
            boolean sub = !rs.wasNull();
            nodes.put(id, new Node(id, sub ? "subagent" : "agent", rs.getString(2), rs.getString(4),
                    Map.of("role", rs.getString(3))));
            if (sub) {
                edges.add(new Edge(id, "agent:" + parent, "subagent-of"));
            }
            if (rs.getString(5) != null) {
                edges.add(new Edge(id, "model:" + rs.getString(5), "uses-model"));
                usedModels.add(rs.getString(5));
            }
        });
        jdbc.query("SELECT key, name, kind, status FROM model_providers", rs -> {
            String id = "provider:" + rs.getString(1);
            nodes.put(id, new Node(id, "provider", rs.getString(2), rs.getString(4), Map.of("kind", rs.getString(3))));
        });
        jdbc.query("SELECT key, display_name, role, lifecycle, provider_key FROM llm_models", rs -> {
            String id = "model:" + rs.getString(1);
            nodes.put(id, new Node(id, "model", rs.getString(2), rs.getString(4), Map.of("role", rs.getString(3))));
            edges.add(new Edge(id, "provider:" + rs.getString(5), "served-by"));
        });
        jdbc.query("SELECT key, kind, name FROM harness_resources", rs -> {
            String id = "resource:" + rs.getString(1);
            nodes.put(id, new Node(id, rs.getString(2).toLowerCase(), rs.getString(3), null, Map.of()));
        });
        jdbc.query("SELECT a.agent_id, r.key FROM agent_resources a JOIN harness_resources r ON r.id = a.resource_id",
                rs -> { edges.add(new Edge("agent:" + rs.getLong(1), "resource:" + rs.getString(2), "equipped-with")); });
        jdbc.query("SELECT p.project_id, r.key FROM project_resources p JOIN harness_resources r ON r.id = p.resource_id",
                rs -> { edges.add(new Edge("project:" + rs.getLong(1), "resource:" + rs.getString(2), "adopts")); });
        jdbc.query("SELECT key, name, category, execution_target FROM software WHERE enabled", rs -> {
            String id = "software:" + rs.getString(1);
            nodes.put(id, new Node(id, "software", rs.getString(2), null,
                    Map.of("category", rs.getString(3), "executionTarget", rs.getBoolean(4))));
        });
        jdbc.query("SELECT a.agent_id, s.key FROM agent_software a JOIN software s ON s.id = a.software_id",
                rs -> { edges.add(new Edge("agent:" + rs.getLong(1), "software:" + rs.getString(2), "uses-software")); });
        jdbc.query("SELECT t.id, h.target FROM task_handoffs h JOIN tasks t ON t.id = h.task_id",
                rs -> { edges.add(new Edge("task:" + rs.getLong(1), "software:" + rs.getString(2), "handed-to")); });
        jdbc.query("SELECT id, title, status, task_id, day FROM daily_items WHERE day >= CURRENT_DATE - 1 AND day <= CURRENT_DATE + 7",
                rs -> {
                    String id = "daily:" + rs.getLong(1);
                    nodes.put(id, new Node(id, "daily", rs.getString(2) == null ? "Oggi" : rs.getString(2),
                            rs.getString(3), Map.of("day", rs.getString(5))));
                    long task = rs.getLong(4);
                    if (!rs.wasNull()) {
                        edges.add(new Edge(id, "task:" + task, "refers-to"));
                    }
                });

        // Unused catalog leaves (models nobody uses, software nobody reaches) stay: the Second
        // Brain shows what exists. Edges to nodes that do not exist (a model an agent names
        // but nobody catalogued) are dropped rather than drawn to nothing.
        List<Edge> valid = edges.stream()
                .filter(edge -> nodes.containsKey(edge.source()) && nodes.containsKey(edge.target()))
                .toList();
        return new Graph(new ArrayList<>(nodes.values()), valid);
    }
}
