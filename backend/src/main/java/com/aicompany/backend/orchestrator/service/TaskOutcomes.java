package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a task produced, read where it is (PHASE 32, TD-55 and TD-63): the output
 * of its last successful run through the AI Engine, and the Esito section that an
 * agent fills in the task's document after a handoff (ADR-021 §4). Nothing is
 * copied or applied: the console shows it, the operator reviews it.
 */
@Service
public class TaskOutcomes {

    /**
     * The Esito section: to the Note section that follows it in a plan's task document,
     * or to the end -- an agent may well use second-level headings inside it.
     */
    private static final Pattern ESITO = Pattern.compile("(?ms)^##\\s+Esito\\s*$(.*?)(?=^##\\s+Note\\s*$|\\z)");

    public record TaskOutcome(Long taskId, String text, String source, @Nullable Instant at, @Nullable Long runId,
                              @Nullable String documentPath) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectWorkspaceService workspace;

    public TaskOutcomes(JdbcTemplate jdbc, ProjectWorkspaceService workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    /** The outcomes of a task, the most recent first; empty while nothing has been produced. */
    @Transactional(readOnly = true)
    public List<TaskOutcome> of(Long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, project_id, code, document_path FROM tasks WHERE id = ?", taskId);
        if (rows.isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        Map<String, Object> task = rows.get(0);
        List<TaskOutcome> outcomes = new ArrayList<>();
        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT id, output, finished_at FROM task_runs WHERE task_id = ? AND status = 'SUCCEEDED'
                ORDER BY id DESC LIMIT 1""", taskId);
        if (!runs.isEmpty() && runs.get(0).get("output") != null) {
            Long runId = ((Number) runs.get(0).get("id")).longValue();
            outcomes.add(new TaskOutcome(taskId, (String) runs.get(0).get("output"), "esecuzione #" + runId,
                    instant(runs.get(0).get("finished_at")), runId, null));
        }
        document(taskId, task).ifPresent(outcomes::add);
        outcomes.sort(Comparator.comparing((TaskOutcome o) -> o.at() == null ? Instant.MIN : o.at()).reversed());
        return outcomes;
    }

    /** The task document's Esito, where the handoff wrote the document (ADR-021 §4). */
    private Optional<TaskOutcome> document(Long taskId, Map<String, Object> task) {
        Long projectId = task.get("project_id") == null ? null : ((Number) task.get("project_id")).longValue();
        String label = task.get("code") != null ? (String) task.get("code") : "TASK-" + taskId;
        Optional<Path> folder = projectId == null ? Optional.of(workspace.inboxOf(taskId)).filter(Files::isDirectory)
                : workspace.folderOf(projectId);
        if (folder.isEmpty()) {
            return Optional.empty();
        }
        String path = projectId == null ? "TASK.md"
                : task.get("document_path") != null ? (String) task.get("document_path") : "tasks/" + label + ".md";
        return workspace.readIn(folder.get(), path).flatMap(TaskOutcomes::esito).map(text ->
                new TaskOutcome(taskId, text, "sezione Esito di " + path, modified(folder.get().resolve(path)), null, path));
    }

    /** The Esito section of a task document, unless it is still the placeholder. */
    public static Optional<String> esito(String document) {
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

    private static @Nullable Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException | RuntimeException unknown) {
            return null;
        }
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
