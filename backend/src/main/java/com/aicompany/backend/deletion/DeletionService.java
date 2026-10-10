package com.aicompany.backend.deletion;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.user.service.SecurityLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deleting for real (ADR-027): distinct from archiving, reserved to admins, and
 * guarded three times -- the row's tag (ADR-009), the resource's name typed back
 * as confirmation, and a refusal while a run is in flight. What a delete takes
 * with it is computed first and shown ({@link #previewTask}, {@link #previewProject}),
 * then recorded in the security log.
 *
 * <p>PHASE 32 (TD-72): the preview is a {@link DeletionPreview}, like every other
 * delete of the console (ADR-035 §3), and the delete answers {@code 204}. A run in
 * flight is a blocker of the preview, and a delete attempted anyway is the same
 * {@code 409 deletion-blocked} with the same sentence.
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
    public DeletionPreview previewTask(Long taskId) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        return taskImpact(task);
    }

    public void deleteTask(Long taskId, String confirmation, String by, Precondition precondition) {
        Task task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        precondition.requireSatisfiedBy(task.getVersion());
        requireConfirmation(task.getTitle(), confirmation);
        DeletionPreview impact = taskImpact(task).requireAllowed();
        removeTaskRows(List.of(taskId));
        log.record(SecurityLog.DATA_DELETED, by, null, "task " + taskId + " «" + task.getTitle() + "»: "
                + String.join(" ", impact.deletes()));
    }

    // --- projects ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DeletionPreview previewProject(Long projectId, TasksPolicy policy) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return projectImpact(project, policy);
    }

    public void deleteProject(Long projectId, TasksPolicy policy, String confirmation, String by,
                              Precondition precondition) {
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        precondition.requireSatisfiedBy(project.getVersion());
        requireConfirmation(project.getName(), confirmation);
        DeletionPreview impact = projectImpact(project, policy).requireAllowed();
        List<Long> taskIds = jdbc.queryForList("SELECT id FROM tasks WHERE project_id = ?", Long.class, projectId);
        // PHASE 28 (ADR-033): the Blueprint is the project's own description; it goes with it.
        // Proposals first -- they point at tasks that may be deleted just below.
        jdbc.update("DELETE FROM blueprint_ai_actions WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_change_proposals WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_edges WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_nodes WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_scans WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_layouts WHERE project_id = ?", projectId);
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
                + policy + ": " + String.join(" ", impact.deletes()) + " Folder kept: " + project.getWorkspacePath());
    }

    // --- a project's Blueprint (PHASE 30, ADR-035 §8) -----------------------------------

    /**
     * What deleting a project's Blueprint would take: every block, relation,
     * position, scan and proposed change. The code is never touched (ADR-033), and
     * the tasks created from accepted proposals stay -- they are work, not the
     * drawing of it. A new scan rebuilds what the code asserts; the manual blocks
     * and the arrangement do not come back.
     */
    @Transactional(readOnly = true)
    public com.aicompany.backend.deletion.DeletionPreview previewBlueprint(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return blueprintImpact(project);
    }

    /**
     * Deletes the whole Blueprint, under the layout's tag (the one the canvas
     * holds) and with the project's name typed back. The project row is locked
     * first: every Blueprint write -- a scan included -- takes it shared, so none
     * runs alongside.
     */
    public void deleteBlueprint(Long projectId, String confirmation, String by, Precondition precondition) {
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (project.isArchived()) {
            throw new com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException();
        }
        Long layoutVersion = jdbc.queryForList("SELECT version FROM blueprint_layouts WHERE project_id = ? FOR UPDATE",
                Long.class, projectId).stream().findFirst().orElse(0L);
        precondition.requireSatisfiedBy(layoutVersion);
        requireConfirmation(project.getName(), confirmation);
        com.aicompany.backend.deletion.DeletionPreview impact = blueprintImpact(project).requireAllowed();
        jdbc.update("DELETE FROM blueprint_ai_actions WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_change_proposals WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_edges WHERE project_id = ?", projectId);
        jdbc.update("UPDATE blueprint_nodes SET parent_id = NULL WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_nodes WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_scans WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM blueprint_layouts WHERE project_id = ?", projectId);
        log.record(SecurityLog.DATA_DELETED, by, null, "blueprint of project " + projectId + " «" + project.getName()
                + "»: " + String.join(" ", impact.deletes()));
    }

    private com.aicompany.backend.deletion.DeletionPreview blueprintImpact(Project project) {
        long id = project.getId();
        int blocks = count("SELECT count(*) FROM blueprint_nodes WHERE project_id = ?", id);
        int manual = count("SELECT count(*) FROM blueprint_nodes WHERE project_id = ? AND origin = 'MANUAL'", id);
        int relations = count("SELECT count(*) FROM blueprint_edges WHERE project_id = ?", id);
        int scans = count("SELECT count(*) FROM blueprint_scans WHERE project_id = ?", id);
        int proposals = count("SELECT count(*) FROM blueprint_change_proposals WHERE project_id = ?", id);
        int withTask = count("SELECT count(*) FROM blueprint_change_proposals WHERE project_id = ? AND task_id IS NOT NULL", id);
        com.aicompany.backend.deletion.DeletionPreview.Builder preview =
                com.aicompany.backend.deletion.DeletionPreview.of("BLUEPRINT").subject(id, project.getName());
        preview.deletes(blocks + " blocchi (" + manual + " disegnati a mano), " + relations + " relazioni, la disposizione "
                + "sul canvas e " + scans + " scansioni.");
        if (proposals > 0) {
            preview.deletes(proposals + " proposte di modifica al codice.");
        }
        if (withTask > 0) {
            preview.keeps("Le " + withTask + " task create da proposte accettate restano.");
        }
        preview.keeps("Il codice del progetto non viene toccato: una nuova scansione ricostruisce ciò che il codice "
                + "dice; i blocchi manuali e la disposizione non tornano.");
        if (project.isArchived()) {
            preview.blocker("Il progetto è archiviato: il suo Blueprint si consulta ma non si modifica. Ripristinalo prima.");
        }
        return preview.build();
    }

    // --- helpers -------------------------------------------------------------------------

    private void removeTaskRows(List<Long> taskIds) {
        for (Long id : taskIds) {
            jdbc.update("UPDATE daily_items d SET title = COALESCE(d.title, t.title) || ' (task eliminata)', task_id = NULL, "
                    + "version = d.version + 1, updated_at = now() FROM tasks t WHERE d.task_id = t.id AND t.id = ?", id);
            // A proposal accepted into this task keeps its record, without the task.
            jdbc.update("UPDATE blueprint_change_proposals SET task_id = NULL WHERE task_id = ?", id);
            jdbc.update("DELETE FROM task_transitions WHERE task_id = ?", id);
            jdbc.update("DELETE FROM blueprint_ai_actions WHERE task_id = ?", id);
            jdbc.update("DELETE FROM task_reviews WHERE task_id = ?", id);
            jdbc.update("DELETE FROM task_handoffs WHERE task_id = ?", id);
            jdbc.update("DELETE FROM task_runs WHERE task_id = ?", id);
            jdbc.update("DELETE FROM tasks WHERE id = ?", id);
        }
    }

    private DeletionPreview taskImpact(Task task) {
        long id = task.getId();
        DeletionPreview.Builder preview = DeletionPreview.of("TASK").subject(id, task.getTitle());
        preview.deletes("La task «" + task.getTitle() + "», con " + count("SELECT count(*) FROM task_runs WHERE task_id = ?", id)
                + " esecuzioni, " + count("SELECT count(*) FROM task_handoffs WHERE task_id = ?", id) + " handoff e "
                + count("SELECT count(*) FROM task_reviews WHERE task_id = ?", id) + " review.");
        int moves = count("SELECT count(*) FROM task_transitions WHERE task_id = ?", id);
        if (moves > 0) {
            preview.deletes("La storia della task (" + moves + " transizioni).");
        }
        int daily = count("SELECT count(*) FROM daily_items WHERE task_id = ?", id);
        if (daily > 0) {
            preview.detaches(daily + " voci del Daily Work restano, come voci personali con il titolo della task.");
        }
        int proposals = count("SELECT count(*) FROM blueprint_change_proposals WHERE task_id = ?", id);
        if (proposals > 0) {
            preview.detaches(proposals + " proposte del Blueprint accettate in questa task restano, senza la task.");
        }
        if (task.getPhase() != null) {
            preview.keeps("La fase del piano resta, senza questa task.");
        }
        if (task.getDocumentPath() != null) {
            preview.keeps("Il documento " + task.getDocumentPath() + " resta nella cartella del progetto.");
        }
        if (count("SELECT count(*) FROM task_runs WHERE task_id = ? AND status IN ('QUEUED','RUNNING')", id) > 0) {
            preview.blocker("Un'esecuzione della task è in corso: attendi che finisca, o annullala.");
        }
        return preview.build();
    }

    private DeletionPreview projectImpact(Project project, TasksPolicy policy) {
        long id = project.getId();
        String inTasks = "SELECT id FROM tasks WHERE project_id = " + id;
        int taskCount = count("SELECT count(*) FROM tasks WHERE project_id = ?", id);
        DeletionPreview.Builder preview = DeletionPreview.of("PROJECT").subject(id, project.getName());
        preview.deletes("Il progetto «" + project.getName() + "», con " + count("SELECT count(*) FROM project_phases WHERE project_id = ?", id)
                + " fasi, " + count("SELECT count(*) FROM plan_runs WHERE project_id = ?", id) + " generazioni di piano e "
                + count("SELECT count(*) FROM project_resources WHERE project_id = ?", id) + " risorse adottate.");
        String theTasks = taskCount == 1 ? "La task del progetto" : "Le " + taskCount + " task del progetto";
        if (taskCount > 0 && policy == TasksPolicy.DELETE) {
            preview.deletes(theTasks + ", con " + count("SELECT count(*) FROM task_runs WHERE task_id IN (" + inTasks + ")")
                    + " esecuzioni, " + count("SELECT count(*) FROM task_handoffs WHERE task_id IN (" + inTasks + ")")
                    + " handoff, " + count("SELECT count(*) FROM task_reviews WHERE task_id IN (" + inTasks + ")")
                    + " review e la loro storia.");
            int daily = count("SELECT count(*) FROM daily_items WHERE task_id IN (" + inTasks + ")");
            if (daily > 0) {
                preview.detaches(daily + " voci del Daily Work restano, come voci personali con il titolo della loro task.");
            }
        } else if (taskCount > 0) {
            preview.detaches(theTasks + (taskCount == 1 ? " resta" : " restano")
                    + ", senza progetto, senza fase e senza codice di piano.");
        }
        int blocks = count("SELECT count(*) FROM blueprint_nodes WHERE project_id = ?", id);
        if (blocks > 0) {
            preview.deletes("Il Blueprint del progetto (" + blocks + " blocchi, "
                    + count("SELECT count(*) FROM blueprint_edges WHERE project_id = ?", id) + " relazioni).");
        }
        if (project.getWorkspacePath() != null) {
            preview.keeps("La cartella " + project.getWorkspacePath() + " resta sul disco: eliminala tu, se vuoi.");
        }
        for (Map<String, Object> running : jdbc.queryForList("SELECT DISTINCT t.id, t.title FROM task_runs r "
                + "JOIN tasks t ON t.id = r.task_id WHERE t.project_id = ? AND r.status IN ('QUEUED','RUNNING') ORDER BY t.id", id)) {
            preview.blocker("La task «" + running.get("title") + "» (#" + running.get("id") + ") ha un'esecuzione in corso: "
                    + "attendi che finisca, o annullala.");
        }
        return preview.build();
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
