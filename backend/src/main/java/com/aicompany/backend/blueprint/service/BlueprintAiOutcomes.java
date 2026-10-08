package com.aicompany.backend.blueprint.service;

import com.aicompany.backend.blueprint.model.AiAction;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The outcome of an AI action, back in the block it was asked about (PHASE 32,
 * TD-63; ADR-033 §8). An action asks through a task; what the task produced is
 * read where it is: the output of the task's last successful run through the AI
 * Engine, or else the Esito section an external agent fills in the task's
 * document after a handoff. Nothing is copied, nothing is applied: the operator
 * reads it in the block and decides whether it becomes the block's description or
 * pseudocode -- an override, like any edit of theirs (§3, §7).
 */
@Service
public class BlueprintAiOutcomes {

    /**
     * The Esito section: to the Note section that follows it in a plan's task document,
     * or to the end -- an agent may well use second-level headings inside it.
     */
    private static final Pattern ESITO = Pattern.compile("(?ms)^##\\s+Esito\\s*$(.*?)(?=^##\\s+Note\\s*$|\\z)");

    /** Where an outcome may go when the operator applies it; null when it is only to be read. */
    static @Nullable String appliesTo(AiAction action) {
        return switch (action) {
            case EXPLAIN_COMPONENT -> "description";
            case GENERATE_PSEUDOCODE -> "pseudocode";
            default -> null;
        };
    }

    public record BlueprintAiActionView(Long id, AiAction action, Long taskId, String taskTitle, String taskStatus,
                                        String requestedBy, Instant requestedAt, @Nullable String outcome,
                                        @Nullable String outcomeSource, @Nullable Instant outcomeAt,
                                        @Nullable String appliesTo) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectWorkspaceService workspace;
    private final BlueprintService blueprint;

    public BlueprintAiOutcomes(JdbcTemplate jdbc, ProjectWorkspaceService workspace, BlueprintService blueprint) {
        this.jdbc = jdbc;
        this.workspace = workspace;
        this.blueprint = blueprint;
    }

    /** Called in the transaction that creates the action's task. */
    void record(Long projectId, Long nodeId, Long taskId, AiAction action, String by) {
        jdbc.update("INSERT INTO blueprint_ai_actions (project_id, node_id, task_id, action, requested_by) VALUES (?, ?, ?, ?, ?)",
                projectId, nodeId, taskId, action.name(), by);
    }

    /** The actions asked about a block, the most recent first, each with its outcome when there is one. */
    @Transactional(readOnly = true)
    public List<BlueprintAiActionView> of(Long projectId, Long nodeId) {
        blueprint.requireNode(projectId, nodeId);
        Optional<Path> folder = workspace.folderOf(projectId);
        return jdbc.queryForList("""
                SELECT a.id, a.action, a.task_id, a.requested_by, a.created_at, t.title, t.status, t.code, t.document_path
                FROM blueprint_ai_actions a JOIN tasks t ON t.id = a.task_id
                WHERE a.project_id = ? AND a.node_id = ? ORDER BY a.id DESC""", projectId, nodeId)
                .stream().map(row -> view(row, folder)).toList();
    }

    private BlueprintAiActionView view(Map<String, Object> row, Optional<Path> folder) {
        Long taskId = ((Number) row.get("task_id")).longValue();
        AiAction action = AiAction.valueOf((String) row.get("action"));
        String outcome = null;
        String source = null;
        Instant at = null;
        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT id, output, finished_at FROM task_runs WHERE task_id = ? AND status = 'SUCCEEDED'
                ORDER BY id DESC LIMIT 1""", taskId);
        if (!runs.isEmpty() && runs.get(0).get("output") != null) {
            outcome = (String) runs.get(0).get("output");
            source = "esecuzione #" + runs.get(0).get("id");
            at = instant(runs.get(0).get("finished_at"));
        } else if (folder.isPresent()) {
            String document = row.get("document_path") != null ? (String) row.get("document_path")
                    : "tasks/" + (row.get("code") != null ? row.get("code") : "TASK-" + taskId) + ".md";
            Optional<String> esito = workspace.readIn(folder.get(), document).flatMap(BlueprintAiOutcomes::esito);
            if (esito.isPresent()) {
                outcome = esito.get();
                source = "sezione Esito di " + document;
            }
        }
        return new BlueprintAiActionView(((Number) row.get("id")).longValue(), action, taskId, (String) row.get("title"),
                (String) row.get("status"), (String) row.get("requested_by"), instant(row.get("created_at")), outcome, source,
                at, appliesTo(action));
    }

    /** The Esito section of a task document, unless it is still the placeholder. */
    static Optional<String> esito(String document) {
        Matcher m = ESITO.matcher(document);
        if (!m.find()) {
            return Optional.empty();
        }
        String text = m.group(1).strip();
        if (text.isEmpty() || text.startsWith("_Da compilare")) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private static @Nullable Instant instant(@Nullable Object value) {
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        return value instanceof Instant i ? i : null;
    }
}
