package com.aicompany.backend.orchestrator;

import com.aicompany.backend.orchestrator.model.TaskReview;
import com.aicompany.backend.orchestrator.service.ExecutionService;
import com.aicompany.backend.plan.model.ProjectPhase;
import com.aicompany.backend.plan.repository.ProjectPhaseRepository;
import com.aicompany.backend.plan.service.PlanningService;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 -- TD-41: a review (or a handoff) moves its task's phase forward, and
 * the phase used to be written from the copy loaded with the task, without a lock.
 * An approval of that phase committed in between left the copy a version behind,
 * and the flush failed with an optimistic-lock conflict: a 500 where the operator
 * had done nothing wrong. The interleaving is made deterministic here -- the phase
 * is read, the approval commits in its own transaction, then the review runs in
 * the first one -- and the review must win on top of the approval.
 */
class PhaseProgressRaceTest extends AbstractPostgresTest {

    @Autowired
    private ProjectRepository projects;
    @Autowired
    private ProjectPhaseRepository phases;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private ExecutionService execution;
    @Autowired
    private PlanningService planning;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aReviewCompletesAPhaseOnTopOfAnApprovalCommittedMeanwhile() {
        Project project = projects.saveAndFlush(new Project("Phase race " + System.nanoTime(), null));
        ProjectPhase phase = phases.saveAndFlush(new ProjectPhase(project, 1, "One", "the only phase", "docs/phases/PHASE-1.md"));
        Task task = new Task("The only task", null, TaskStatus.IN_PROGRESS, TaskPriority.LOW);
        task.assignTo(project);
        task.attachToPlan(phase, "TASK-001", "tasks/TASK-001.md");
        Long taskId = tasks.saveAndFlush(task).getId();
        Long phaseId = phase.getId();
        try {
            TransactionTemplate review = new TransactionTemplate(transactions);
            TransactionTemplate approval = new TransactionTemplate(transactions);
            approval.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            review.executeWithoutResult(status -> {
                Task loaded = tasks.findById(taskId).orElseThrow();
                long seen = loaded.getPhase().getVersion(); // the phase is now in this persistence context
                approval.executeWithoutResult(inner ->
                        planning.reviewPhase(phaseId, true, "approved meanwhile", Preconditions.at(seen)));
                execution.review(taskId, TaskReview.Verdict.ACCEPTED, "done", null, null, "tester",
                        Preconditions.at(loaded.getVersion()));
            });

            assertThat(jdbc.queryForObject("SELECT status FROM project_phases WHERE id = ?", String.class, phaseId))
                    .isEqualTo("DONE");
            assertThat(jdbc.queryForObject("SELECT approval FROM project_phases WHERE id = ?", String.class, phaseId))
                    .as("the approval committed meanwhile is kept, not overwritten").isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId)).isEqualTo("DONE");
        } finally {
            jdbc.update("DELETE FROM task_transitions WHERE task_id = ?", taskId);
            jdbc.update("DELETE FROM task_reviews WHERE task_id = ?", taskId);
            jdbc.update("DELETE FROM tasks WHERE id = ?", taskId);
            jdbc.update("DELETE FROM project_phases WHERE id = ?", phaseId);
            jdbc.update("DELETE FROM projects WHERE id = ?", project.getId());
        }
    }
}
