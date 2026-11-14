package com.aicompany.backend.journal;

import com.aicompany.backend.binding.ExecutionTargetCatalog;
import com.aicompany.backend.plan.service.PlanMarkdown;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PHASE 37 (ADR-047): what each agent did, step by step, newest first -- the moments the
 * control plane records (a task started, handed to a tool, run on the AI Engine, reviewed,
 * completed) and the steps the agent writes in its diary while it works, with the outcome
 * it leaves at the end. Read only: the console shows it in the 3D office.
 */
@Service
public class AgentJournal {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    public enum Kind {
        /** The task was handed to a tool: from now on it is in progress there. */
        DELIVERED,
        STARTED,
        /** A step the agent wrote in its diary. */
        STEP,
        /** The agent wrote its outcome. */
        OUTCOME,
        RUN_QUEUED, RUN_STARTED, RUN_SUCCEEDED, RUN_FAILED,
        ACCEPTED, CHANGES_REQUESTED,
        COMPLETED, STOPPED, REOPENED
    }

    /**
     * One moment of an agent's work. {@code approximate} when the time was inferred (a diary line
     * written without one). {@code task} is the plan code, or TASK-&lt;id&gt; outside a plan.
     */
    public record JournalEntry(String key, @Nullable Instant at, boolean approximate, @Nullable Long agentId, long taskId,
                               String task, String taskTitle, Kind kind, String text, @Nullable String actor) {
    }

    private record TaskRow(long id, String label, String title, @Nullable Long agentId, @Nullable Long projectId,
                           @Nullable String documentPath) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectWorkspaceService workspace;
    private final ExecutionTargetCatalog targets;
    private final ZoneId zone = ZoneId.systemDefault();

    public AgentJournal(JdbcTemplate jdbc, ProjectWorkspaceService workspace, ExecutionTargetCatalog targets) {
        this.jdbc = jdbc;
        this.workspace = workspace;
        this.targets = targets;
    }

    /** The journal of one agent, or of every agent with a task when {@code agentId} is null. */
    @Transactional(readOnly = true)
    public List<JournalEntry> entries(@Nullable Long agentId, int limit) {
        String which = agentId == null ? "t.agent_id IS NOT NULL" : "t.agent_id = ?";
        Object[] args = agentId == null ? new Object[] {limit} : new Object[] {agentId, limit};
        Map<Long, TaskRow> tasks = new HashMap<>();
        jdbc.query("SELECT id, code, title, agent_id, project_id, document_path FROM tasks t WHERE " + which,
                rs -> {
                    long id = rs.getLong("id");
                    tasks.put(id, new TaskRow(id, rs.getString("code") != null ? rs.getString("code") : "TASK-" + id,
                            rs.getString("title"), rs.getObject("agent_id", Long.class),
                            rs.getObject("project_id", Long.class), rs.getString("document_path")));
                }, agentId == null ? new Object[0] : new Object[] {agentId});
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<JournalEntry> entries = new ArrayList<>();

        // Handoffs: the moment the task went to a tool and started there.
        Map<Long, Instant> delivered = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT h.id, h.task_id, h.target, h.requested_by, h.created_at FROM task_handoffs h
                JOIN tasks t ON t.id = h.task_id WHERE %s ORDER BY h.id DESC LIMIT ?""".formatted(which), args)) {
            TaskRow task = tasks.get(number(row.get("task_id")));
            Instant at = instant(row.get("created_at"));
            delivered.merge(task.id(), at, (a, b) -> a.isAfter(b) ? a : b);
            String target = (String) row.get("target");
            String name = targets.find(target).map(ExecutionTargetCatalog.ExecutionTarget::name).orElse(target);
            entries.add(entry("handoff:" + row.get("id"), at, false, task.agentId(), task, Kind.DELIVERED,
                    "Consegnata a " + name + ": la task è in corso", (String) row.get("requested_by")));
        }

        // Transitions the handoffs and the reviews do not already say.
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT tr.id, tr.task_id, tr.transition, tr.actor, tr.via, tr.occurred_at FROM task_transitions tr
                JOIN tasks t ON t.id = tr.task_id WHERE %s ORDER BY tr.id DESC LIMIT ?""".formatted(which), args)) {
            String transition = (String) row.get("transition");
            String via = (String) row.get("via");
            if ("HANDOFF".equals(via) || "REVIEW".equals(via)) {
                continue;
            }
            TaskRow task = tasks.get(number(row.get("task_id")));
            Kind kind;
            String text;
            switch (transition) {
                case "START" -> {
                    kind = Kind.STARTED;
                    text = "RUN".equals(via) ? "Task avviata per un'esecuzione del motore AI" : "Task avviata";
                }
                case "COMPLETE" -> {
                    kind = Kind.COMPLETED;
                    text = "Task completata";
                }
                case "STOP" -> {
                    kind = Kind.STOPPED;
                    text = "Task fermata: torna da iniziare";
                }
                default -> {
                    kind = Kind.REOPENED;
                    text = "Task riaperta";
                }
            }
            entries.add(entry("transition:" + row.get("id"), instant(row.get("occurred_at")), false, task.agentId(), task, kind,
                    text, (String) row.get("actor")));
        }

        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT r.id, r.task_id, r.verdict, r.note, r.reviewer, r.created_at FROM task_reviews r
                JOIN tasks t ON t.id = r.task_id WHERE %s ORDER BY r.id DESC LIMIT ?""".formatted(which), args)) {
            TaskRow task = tasks.get(number(row.get("task_id")));
            boolean accepted = "ACCEPTED".equals(row.get("verdict"));
            String note = (String) row.get("note");
            String text = (accepted ? "Review: accettata, task completata" : "Review: modifiche richieste")
                    + (note == null || note.isBlank() ? "" : " — " + note.strip());
            entries.add(entry("review:" + row.get("id"), instant(row.get("created_at")), false, task.agentId(), task,
                    accepted ? Kind.ACCEPTED : Kind.CHANGES_REQUESTED, text, (String) row.get("reviewer")));
        }

        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT r.id, r.task_id, r.agent_id, r.status, r.requested_model, r.served_model, r.failure_type,
                       r.output_tokens, r.created_at, r.started_at, r.finished_at, r.requested_by FROM task_runs r
                JOIN tasks t ON t.id = r.task_id WHERE %s ORDER BY r.id DESC LIMIT ?""".formatted(which), args)) {
            TaskRow task = tasks.get(number(row.get("task_id")));
            Long agent = row.get("agent_id") == null ? task.agentId() : number(row.get("agent_id"));
            String id = String.valueOf(row.get("id"));
            String model = row.get("served_model") != null ? (String) row.get("served_model")
                    : row.get("requested_model") != null ? (String) row.get("requested_model") : "il modello predefinito";
            String by = (String) row.get("requested_by");
            entries.add(entry("run:" + id + ":queued", instant(row.get("created_at")), false, agent, task, Kind.RUN_QUEUED,
                    "Esecuzione #" + id + " in coda sul motore AI", by));
            if (row.get("started_at") != null) {
                entries.add(entry("run:" + id + ":started", instant(row.get("started_at")), false, agent, task, Kind.RUN_STARTED,
                        "Il motore AI lavora con " + model, null));
            }
            if (row.get("finished_at") != null) {
                boolean ok = "SUCCEEDED".equals(row.get("status"));
                Object tokens = row.get("output_tokens");
                entries.add(entry("run:" + id + ":finished", instant(row.get("finished_at")), false, agent, task,
                        ok ? Kind.RUN_SUCCEEDED : Kind.RUN_FAILED,
                        ok ? "Il motore AI ha risposto" + (tokens == null ? "" : " (" + tokens + " token)")
                                : "Esecuzione non riuscita: " + row.get("failure_type"), null));
            }
        }

        // What the agent writes while it works, and at the end: only where a handoff prepared it.
        for (Map.Entry<Long, Instant> handed : delivered.entrySet()) {
            TaskRow task = tasks.get(handed.getKey());
            Optional<Path> folder = folderOf(task.id(), task.projectId());
            if (folder.isEmpty()) {
                continue;
            }
            String document = taskDocument(task.label(), task.projectId(), task.documentPath());
            String diary = PlanMarkdown.diaryPath(document);
            workspace.readIn(folder.get(), diary).ifPresent(text -> {
                for (DiaryFormat.Step step : DiaryFormat.parse(text, modified(folder.get().resolve(diary)), handed.getValue(), zone)) {
                    entries.add(entry("diary:" + task.id() + ":" + step.position(), step.at(), step.approximate(), task.agentId(),
                            task, Kind.STEP, step.text(), null));
                }
            });
            String outcome = PlanMarkdown.outcomePath(document);
            workspace.readIn(folder.get(), outcome).map(String::strip)
                    .filter(text -> !text.isEmpty() && !text.startsWith("_Da compilare"))
                    .ifPresent(text -> entries.add(entry("outcome:" + task.id(), modified(folder.get().resolve(outcome)), false,
                            task.agentId(), task, Kind.OUTCOME, "Ha scritto l'esito in " + outcome, null)));
        }

        entries.sort(Comparator.comparing((JournalEntry e) -> e.at() == null ? Instant.MIN : e.at()).reversed()
                .thenComparing(JournalEntry::key, Comparator.reverseOrder()));
        return entries.size() > limit ? List.copyOf(entries.subList(0, limit)) : entries;
    }

    /** The task's latest handoff, which its changes are measured from; empty when it was never handed off. */
    @Transactional(readOnly = true)
    public Optional<WorkChanges.Baseline> baselineOf(long taskId) {
        return jdbc.query("SELECT id, created_at FROM task_handoffs WHERE task_id = ? ORDER BY id DESC LIMIT 1",
                (rs, n) -> new WorkChanges.Baseline(rs.getLong("id"), rs.getTimestamp("created_at").toInstant()), taskId)
                .stream().findFirst();
    }

    /** The folder a task's work happens in, if it exists: its project's, or its inbox outside a project. */
    @Transactional(readOnly = true)
    public Optional<Path> folderOf(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT project_id FROM tasks WHERE id = ?", taskId);
        if (rows.isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        Object project = rows.getFirst().get("project_id");
        return folderOf(taskId, project == null ? null : number(project));
    }

    private Optional<Path> folderOf(long taskId, @Nullable Long projectId) {
        return projectId != null ? workspace.folderOf(projectId)
                : Optional.of(workspace.inboxOf(taskId)).filter(Files::isDirectory);
    }

    /** As the handoff names it: the plan's document, tasks/&lt;label&gt;.md in a project, TASK.md in an inbox. */
    static String taskDocument(String label, @Nullable Long projectId, @Nullable String documentPath) {
        return projectId != null && documentPath != null ? documentPath : projectId != null ? "tasks/" + label + ".md" : "TASK.md";
    }

    private static JournalEntry entry(String key, @Nullable Instant at, boolean approximate, @Nullable Long agentId, TaskRow task,
                                      Kind kind, String text, @Nullable String actor) {
        return new JournalEntry(key, at, approximate, agentId, task.id(), task.label(), task.title(), kind, text, actor);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return (Instant) value;
    }

    private static @Nullable Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException | RuntimeException unknown) {
            return null;
        }
    }
}
