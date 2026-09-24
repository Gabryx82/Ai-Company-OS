package com.aicompany.backend.deletion;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.run.exception.TaskRunInProgressException;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.user.service.SecurityLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deleting for real (ADR-027): distinct from archiving, reserved to admins, and
 * guarded three times -- the row's tag (ADR-009), the resource's name typed back
 * as confirmation, and a refusal while a run is in flight. What a delete takes
 * with it is computed first and shown ({@link #previewTask}, {@link #previewProject}),
 * then reported, and recorded in the security log.
 *
 * <p>What it never takes: files. The project's folder and the task documents
 * stay on disk -- they are the operator's, and a database delete is not the
 * place to remove them. Daily items that referenced a deleted task become
 * personal items that keep its title, instead of vanishing from the day.
 */
@Service
@Transactional
public class DeletionService {

    /** What happens to the tasks of a deleted project. There is no default: the caller says. */
    public enum TasksPolicy { DETACH, DELETE }

    public record Impact(String kind, Long id, String name, int tasks, int runs, int handoffs, int reviews,
                         int dailyItems, int phases, int planRuns, int resources, boolean runInProgress,
                         String folder, List<String> notes) {
    }

    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final JdbcTemplate jdbc;
    private final SecurityLog log;

    public DeletionService(TaskRepository tasks, ProjectRepository projects, JdbcTemplate jdbc, SecurityLog log) {
        this.tasks = tasks;
        this.projects = projects;
        this.jdbc = jdbc;
        this.log = log;
    }

    // --- tasks ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Impact previewTask(Long taskId) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        return taskImpact(task);
    }

    public Impact deleteTask(Long taskId, String confirmation, String by, Precondition precondition) {
        Task task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        precondition.requireSatisfiedBy(task.getVersion());
        requireConfirmation(task.getTitle(), confirmation);
        Impact impact = taskImpact(task);
        if (impact.runInProgress()) {
            throw new TaskRunInProgressException(taskId);
        }
        removeTaskRows(List.of(taskId));
        log.record(SecurityLog.DATA_DELETED, by, null, "task " + taskId + " «" + task.getTitle() + "»: "
                + impact.runs() + " run, " + impact.handoffs() + " handoff, " + impact.reviews() + " review");
        return impact;
    }

    // --- projects ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Impact previewProject(Long projectId, TasksPolicy policy) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return projectImpact(project, policy);
    }

    public Impact deleteProject(Long projectId, TasksPolicy policy, String confirmation, String by,
                                Precondition precondition) {
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        precondition.requireSatisfiedBy(project.getVersion());
        requireConfirmation(project.getName(), confirmation);
        Impact impact = projectImpact(project, policy);
        if (impact.runInProgress()) {
            Long running = jdbc.queryForObject("SELECT r.task_id FROM task_runs r JOIN tasks t ON t.id = r.task_id "
                    + "WHERE t.project_id = ? AND r.status IN ('QUEUED','RUNNING') LIMIT 1", Long.class, projectId);
            throw new TaskRunInProgressException(running);
        }
        List<Long> taskIds = jdbc.queryForList("SELECT id FROM tasks WHERE project_id = ?", Long.class, projectId);
        if (policy == TasksPolicy.DELETE) {
            removeTaskRows(taskIds);
        } else {
            // Kept, and outside any project: no plan code or document that points into a project that is gone.
            jdbc.update("UPDATE tasks SET project_id = NULL, phase_id = NULL, code = NULL, document_path = NULL, "
                    + "version = version + 1 WHERE project_id = ?", projectId);
        }
        jdbc.update("UPDATE tasks SET phase_id = NULL WHERE phase_id IN (SELECT id FROM project_phases WHERE project_id = ?)",
                projectId);
        jdbc.update("DELETE FROM project_phases WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM plan_runs WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM project_resources WHERE project_id = ?", projectId);
        projects.delete(project);
        projects.flush();
        log.record(SecurityLog.DATA_DELETED, by, null, "project " + projectId + " «" + project.getName() + "», tasks "
                + policy + " (" + impact.tasks() + "); folder kept: " + impact.folder());
        return impact;
    }

    // --- helpers -------------------------------------------------------------------------

    private void removeTaskRows(List<Long> taskIds) {
        for (Long id : taskIds) {
            jdbc.update("UPDATE daily_items d SET title = COALESCE(d.title, t.title) || ' (task eliminata)', task_id = NULL, "
                    + "version = d.version + 1, updated_at = now() FROM tasks t WHERE d.task_id = t.id AND t.id = ?", id);
            jdbc.update("DELETE FROM task_reviews WHERE task_id = ?", id);
            jdbc.update("DELETE FROM task_handoffs WHERE task_id = ?", id);
            jdbc.update("DELETE FROM task_runs WHERE task_id = ?", id);
            jdbc.update("DELETE FROM tasks WHERE id = ?", id);
        }
    }

    private Impact taskImpact(Task task) {
        long id = task.getId();
        List<String> notes = new ArrayList<>();
        if (task.getDocumentPath() != null) {
            notes.add("Il documento " + task.getDocumentPath() + " resta nella cartella del progetto.");
        }
        if (task.getPhase() != null) {
            notes.add("La task appartiene a una fase del piano: la fase resta, senza questa task.");
        }
        int daily = count("SELECT count(*) FROM daily_items WHERE task_id = ?", id);
        if (daily > 0) {
            notes.add(daily + " voci del Daily Work restano, come voci personali con il titolo della task.");
        }
        return new Impact("TASK", id, task.getTitle(), 1, count("SELECT count(*) FROM task_runs WHERE task_id = ?", id),
                count("SELECT count(*) FROM task_handoffs WHERE task_id = ?", id),
                count("SELECT count(*) FROM task_reviews WHERE task_id = ?", id), daily, 0, 0, 0,
                count("SELECT count(*) FROM task_runs WHERE task_id = ? AND status IN ('QUEUED','RUNNING')", id) > 0,
                null, notes);
    }

    private Impact projectImpact(Project project, TasksPolicy policy) {
        long id = project.getId();
        String inTasks = "SELECT id FROM tasks WHERE project_id = " + id;
        int taskCount = count("SELECT count(*) FROM tasks WHERE project_id = ?", id);
        List<String> notes = new ArrayList<>();
        notes.add(policy == TasksPolicy.DELETE
                ? "Le " + taskCount + " task del progetto vengono eliminate, con esecuzioni, handoff e review."
                : "Le " + taskCount + " task del progetto restano, senza progetto, senza fase e senza codice di piano.");
        if (project.getWorkspacePath() != null) {
            notes.add("La cartella " + project.getWorkspacePath() + " resta sul disco: eliminala tu, se vuoi.");
        }
        boolean deleting = policy == TasksPolicy.DELETE;
        return new Impact("PROJECT", id, project.getName(), taskCount,
                deleting ? count("SELECT count(*) FROM task_runs WHERE task_id IN (" + inTasks + ")") : 0,
                deleting ? count("SELECT count(*) FROM task_handoffs WHERE task_id IN (" + inTasks + ")") : 0,
                deleting ? count("SELECT count(*) FROM task_reviews WHERE task_id IN (" + inTasks + ")") : 0,
                deleting ? count("SELECT count(*) FROM daily_items WHERE task_id IN (" + inTasks + ")") : 0,
                count("SELECT count(*) FROM project_phases WHERE project_id = ?", id),
                count("SELECT count(*) FROM plan_runs WHERE project_id = ?", id),
                count("SELECT count(*) FROM project_resources WHERE project_id = ?", id),
                count("SELECT count(*) FROM task_runs WHERE status IN ('QUEUED','RUNNING') AND task_id IN (" + inTasks + ")") > 0,
                project.getWorkspacePath(), notes);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** The name typed back, ignoring case and surrounding spaces: a guard against a click, not a password. */
    static void requireConfirmation(String name, String confirmation) {
        if (confirmation == null || !confirmation.strip().toLowerCase(Locale.ROOT).equals(name.strip().toLowerCase(Locale.ROOT))) {
            throw new DeletionConfirmationException(name);
        }
    }
}
