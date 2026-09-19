package com.aicompany.backend.task;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.agent.service.AgentService;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.PreconditionFailedException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.project.service.ProjectService;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.task.exception.ArchivedProjectTaskIsImmutableException;
import com.aicompany.backend.task.exception.InactiveAgentCannotReceiveTasksException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three interleavings TASK-009 introduces, against real PostgreSQL with two
 * threads and hand-controlled transactions.
 *
 * <p>Each one exists because the assignment path acquires a lock that the
 * artifacts claim it needs, and each is the only thing that would notice if that
 * lock were dropped. The mutations are recorded in {@code IMPLEMENTATION.md} §6;
 * without them these tests would be three green lights with nothing behind them.
 *
 * <h2>The harness</h2>
 *
 * <p>Copied in shape from {@code ProjectConcurrencyTest} on purpose -- two
 * transactions opened by hand so the commit boundary is the test's, latches for
 * every ordering constraint, and never a sleep. The one timed wait,
 * {@link #LOCK_OBSERVATION_WINDOW}, is not synchronisation standing in for a
 * latch: "the other transaction is parked on a row lock" has no positive signal,
 * so the waiting thread gives up after a bounded window and commits, and that
 * commit is exactly what releases the other one.
 *
 * <p><strong>Copied a little too faithfully at first.</strong> The bounded wait
 * was used for both directions, including the one where a thread waits for the
 * other to have <em>decided</em> -- and that thread is not blocked on anything, it
 * is just slow on a cold JVM. If the bound expired, the second thread would go
 * ahead against a row nobody had locked yet and the schedule would stop being the
 * one the test describes, in either direction: a red that blames the
 * implementation, or a green that proves nothing. {@link #awaitOrFail} separates
 * the two cases and says which is which; the same fix went into
 * {@code PreconditionConcurrencyTest}, which had inherited the defect from
 * TASK-008.
 *
 * <p>That was found while chasing a failure it did not cause, and the honest note
 * is worth more than the fix: {@code anArchiveCannotCommit...} was failing on what
 * looked like clean code because a mutation had been applied and never reverted --
 * the harness could not put back a deleted block and its driver ignored the exit
 * status. The lock it tests was there all along. See {@code ARTIFACT.md} §7.
 *
 * <p>Commit order is established by a shared counter stamped in an
 * {@code afterCommit} callback, never by wall-clock time.
 */
class TaskAgentConcurrencyTest extends AbstractPostgresTest {

    private static final long LOCK_OBSERVATION_WINDOW = TimeUnit.SECONDS.toMillis(3);
    private static final long TEST_TIMEOUT_SECONDS = 30;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService threads;
    private AtomicInteger commitSequence;

    @BeforeEach
    void startFromAnEmptyRegistry() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        agentRepository.deleteAll();
        threads = Executors.newFixedThreadPool(2);
        commitSequence = new AtomicInteger();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    // ------------------------------------------------------------------
    // I-8 -- two associations, one row, one version
    // ------------------------------------------------------------------

    /**
     * I-8, AC-8. A caller changing the project and a caller changing the agent,
     * on the same task, both holding the tag they read a moment ago.
     *
     * <p>They are not in conflict about the <em>domain</em>: one writes
     * {@code project_id}, the other {@code agent_id}, and a system that versioned
     * the two associations separately would let both through and call it
     * concurrency. It would be wrong -- they are two writes to one row, and the
     * second decided against a task it had not seen. ADR-010 §4 refuses the
     * separate version, and this is that refusal made observable: one 200, one
     * 412.
     *
     * <p>L0 is what makes the answer definite rather than a coin toss: the second
     * transaction waits on the task row, re-reads it, and finds the version moved.
     */
    @Test
    void changingTheProjectAndChangingTheAgentAreOneRowAndOneVersion() throws Exception {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        Long agentId = agentRepository.saveAndFlush(new Agent("Backend", "Engineer", "jvm")).getId();
        Long taskId = taskRepository.saveAndFlush(
                new Task("Wire the planner", null, TaskStatus.OPEN, "HIGH")).getId();

        Precondition bothRead = taskPrecondition(taskId);

        CountDownLatch projectWriterIsInsideTheLock = new CountDownLatch(1);
        CountDownLatch agentWriterHasFinished = new CountDownLatch(1);

        AtomicReference<Throwable> projectWriterFailure = new AtomicReference<>();
        AtomicReference<Throwable> agentWriterFailure = new AtomicReference<>();

        Future<?> changingProject = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    taskService.assignToProject(taskId, projectId, bothRead);
                    projectWriterIsInsideTheLock.countDown();
                    awaitAtMost(agentWriterHasFinished);
                    return null;
                });
            } catch (Throwable failure) {
                projectWriterFailure.set(unwrap(failure));
            }
        });

        Future<?> changingAgent = threads.submit(() -> {
            awaitOrFail(projectWriterIsInsideTheLock, "the project writer to take the task row");
            try {
                newTransaction().execute(status -> taskService.assignToAgent(taskId, agentId, bothRead));
            } catch (Throwable failure) {
                agentWriterFailure.set(unwrap(failure));
            }
            agentWriterHasFinished.countDown();
        });

        changingProject.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        changingAgent.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(projectWriterFailure.get())
                .as("the writer that got there first has no reason to fail")
                .isNull();

        assertThat(agentWriterFailure.get())
                .as("""
                    Two writes to one row, and the second held a tag the first had already \
                    moved. Letting it through would be exactly the lost update a separate \
                    association version would have hidden -- ADR-010 §4.""")
                .isInstanceOf(PreconditionFailedException.class);

        assertThat(projectIdOf(taskId)).isEqualTo(projectId);
        assertThat(agentIdOf(taskId))
                .as("the refused writer must not have written")
                .isNull();
        assertThat(versionOf(taskId))
                .as("one write happened, so the counter moved by one")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // I-9 -- the agent's lifecycle against an assignment to it
    // ------------------------------------------------------------------

    /**
     * I-9. An assignment has read the agent, found it active and decided; before
     * it commits, somebody deactivates that agent.
     *
     * <p>The invariant is the one ADR-006 §4 worded for projects, on the other
     * registry: <strong>at the commit of an assignment, the agent it depended on
     * <em>was</em> active</strong> -- not "had been checked". L2 takes the agent
     * row {@code FOR SHARE} and holds it to commit, and {@code FOR UPDATE} cannot
     * slip in front of that, so only two outcomes remain legal:
     *
     * <ul>
     *   <li>the assignment commits first, and the deactivation follows -- an agent
     *       switched off holding one more task, which ADR-010 D3 explicitly
     *       allows and which the reassignment path exists to resolve;</li>
     *   <li>the deactivation got there first, and the assignment is refused with
     *       the conflict of D1, leaving the task unassigned.</li>
     * </ul>
     *
     * <p><strong>Mutation.</strong> Replace {@code findByIdForShare} with
     * {@code findById} in {@code TaskService.requireAgentForDecision} and the
     * deactivation is no longer held back: it commits inside the window and the
     * assignment commits after it, attaching work to an agent that was already
     * switched off. The commit-order assertion below is what catches that.
     */
    @Test
    void aDeactivationCannotCommitBetweenAnAssignmentsDecisionAndItsCommit() throws Exception {

        Long agentId = agentRepository.saveAndFlush(new Agent("Backend", "Engineer", "jvm")).getId();
        Long taskId = taskRepository.saveAndFlush(
                new Task("Wire the planner", null, TaskStatus.OPEN, "HIGH")).getId();

        Precondition taskAsRead = taskPrecondition(taskId);
        Precondition agentAsRead = agentPrecondition(agentId);

        CountDownLatch assignmentHasDecided = new CountDownLatch(1);
        CountDownLatch deactivationHasCommitted = new CountDownLatch(1);

        AtomicInteger assignmentCommit = new AtomicInteger();
        AtomicInteger deactivationCommit = new AtomicInteger();
        AtomicReference<Throwable> assignmentFailure = new AtomicReference<>();

        Future<?> assigning = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    stampCommitOrder(assignmentCommit);

                    // Reads the agent, finds it active, decides. On the baseline
                    // this is an unlocked read; under L2 it is SELECT ... FOR SHARE.
                    taskService.assignToAgent(taskId, agentId, taskAsRead);

                    assignmentHasDecided.countDown();

                    // Bounded: if the deactivation is parked behind our lock it will
                    // never signal, and going ahead to commit is what lets it through.
                    awaitAtMost(deactivationHasCommitted);
                    return null;
                });
            } catch (Throwable failure) {
                assignmentFailure.set(unwrap(failure));
            }
        });

        Future<?> deactivating = threads.submit(() -> {
            awaitOrFail(assignmentHasDecided, "the assignment to decide against an active agent");
            newTransaction().execute(status -> {
                stampCommitOrder(deactivationCommit);
                return agentService.deactivate(agentId, agentAsRead);
            });
            deactivationHasCommitted.countDown();
        });

        assigning.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        deactivating.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (assignmentFailure.get() == null) {

            assertThat(agentIdOf(taskId))
                    .as("an assignment that reported success must have been written")
                    .isEqualTo(agentId);

            assertThat(assignmentCommit.get())
                    .as("""
                        I-9: the assignment succeeded, so the agent must still have been active \
                        when it committed -- that is, it must have committed before the \
                        deactivation, not after it. An assignment that commits behind a \
                        committed deactivation has given work to an agent that was already out \
                        of the working registry, having evaluated D1 against a state that no \
                        longer held.""")
                    .isLessThan(deactivationCommit.get());

        } else {

            assertThat(assignmentFailure.get())
                    .as("the only legal refusal here is the inactive-agent conflict")
                    .isInstanceOf(InactiveAgentCannotReceiveTasksException.class);

            assertThat(agentIdOf(taskId))
                    .as("a refused assignment must leave the task unassigned")
                    .isNull();
        }

        assertThat(isAgentActive(agentId)).isFalse();
    }

    // ------------------------------------------------------------------
    // I-10 -- the project's lifecycle against an agent change
    // ------------------------------------------------------------------

    /**
     * I-10, and the one that justifies the least obvious line of the
     * implementation: <strong>the agent path locks a project row.</strong>
     *
     * <p>Nothing about giving a task to an agent mentions projects, so the lock
     * looks gratuitous until this interleaving. It is there because of ADR-010 D2:
     * whether the task is frozen depends on {@code Project.status}, so that state
     * is read under L2 and held to commit. Without it the freezing rule is
     * evaluated against a state a concurrent archive is already changing, which is
     * TD-25 reappearing on a path invented after it was closed.
     *
     * <p>Legal outcomes are the two of ADR-006 §4, restated for this path: either
     * the agent change commits before the archive, or it is refused because the
     * task is frozen.
     *
     * <p><strong>Mutation.</strong> Delete the {@code requireProjectForDecision}
     * call from {@code assignToAgent}. The rule still runs -- the entity still
     * reads {@code project.isArchived()} -- but now off an unlocked read, so the
     * archive commits inside the window and the agent change commits behind it.
     * This test is the only one that fails.
     */
    @Test
    void anArchiveCannotCommitBetweenAnAgentChangesDecisionAndItsCommit() throws Exception {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        Long agentId = agentRepository.saveAndFlush(new Agent("Backend", "Engineer", "jvm")).getId();
        Long taskId = taskRepository.saveAndFlush(
                new Task("Wire the planner", null, TaskStatus.OPEN, "HIGH")).getId();

        newTransaction().execute(status ->
                taskService.assignToProject(taskId, projectId, taskPrecondition(taskId)));

        // Fixture, asserted rather than assumed: this whole interleaving is about a
        // lock that is only taken when the task HAS a project, so a setup that
        // quietly did not assign one would turn the test into a confusing failure
        // about commit order instead of a clear one about its own premise.
        assertThat(projectIdOf(taskId))
                .as("fixture: the task must really be in the project before the race")
                .isEqualTo(projectId);

        Precondition taskAsRead = taskPrecondition(taskId);
        Precondition projectAsRead = projectPrecondition(projectId);

        CountDownLatch agentChangeHasDecided = new CountDownLatch(1);
        CountDownLatch archiveHasCommitted = new CountDownLatch(1);

        AtomicInteger agentChangeCommit = new AtomicInteger();
        AtomicInteger archiveCommit = new AtomicInteger();
        AtomicReference<Throwable> agentChangeFailure = new AtomicReference<>();

        Future<?> changingAgent = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    stampCommitOrder(agentChangeCommit);
                    taskService.assignToAgent(taskId, agentId, taskAsRead);
                    agentChangeHasDecided.countDown();
                    awaitAtMost(archiveHasCommitted);
                    return null;
                });
            } catch (Throwable failure) {
                agentChangeFailure.set(unwrap(failure));
            }
        });

        Future<?> archiving = threads.submit(() -> {
            awaitOrFail(agentChangeHasDecided, "the agent change to decide against an active project");
            newTransaction().execute(status -> {
                stampCommitOrder(archiveCommit);
                return projectService.archive(projectId, projectAsRead);
            });
            archiveHasCommitted.countDown();
        });

        changingAgent.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        archiving.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (agentChangeFailure.get() == null) {

            assertThat(agentIdOf(taskId)).isEqualTo(agentId);

            assertThat(agentChangeCommit.get())
                    .as("""
                        I-10: the agent change succeeded, so the project must still have been \
                        ACTIVE when it committed. A write that commits behind a committed \
                        archive has re-staffed a task inside a project that was already out of \
                        the working registry -- ADR-006 §2 evaluated against a state that had \
                        moved. Locking the project row from this path is what makes that \
                        unreachable, and it is the only reason this path touches projects at \
                        all.""")
                    .isLessThan(archiveCommit.get());

        } else {

            assertThat(agentChangeFailure.get())
                    .as("the only legal refusal here is the frozen task")
                    .isInstanceOf(ArchivedProjectTaskIsImmutableException.class);

            assertThat(agentIdOf(taskId))
                    .as("a refused agent change must leave the task as it was")
                    .isNull();
        }

        assertThat(statusOfProject(projectId)).isEqualTo("ARCHIVED");
    }

    // --- helpers -----------------------------------------------------------

    private Precondition taskPrecondition(Long id) {
        return Preconditions.at(taskRepository.findById(id).orElseThrow().getVersion());
    }

    private Precondition projectPrecondition(Long id) {
        return Preconditions.at(projectRepository.findById(id).orElseThrow().getVersion());
    }

    private Precondition agentPrecondition(Long id) {
        return Preconditions.at(agentRepository.findById(id).orElseThrow().getVersion());
    }

    private Long agentIdOf(Long taskId) {
        return jdbc.queryForObject("SELECT agent_id FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private Long projectIdOf(Long taskId) {
        return jdbc.queryForObject("SELECT project_id FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private long versionOf(Long taskId) {
        return jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private String statusOfProject(Long id) {
        return jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, id);
    }

    private boolean isAgentActive(Long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT active FROM agents WHERE id = ?", Boolean.class, id));
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Stamps this transaction with the next value of a shared counter, from an
     * {@code afterCommit} callback. Commit order is then a definite comparison
     * rather than two timestamps that happen to differ.
     */
    private void stampCommitOrder(AtomicInteger slot) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        slot.set(commitSequence.incrementAndGet());
                    }
                });
    }

    /**
     * Waits for the other thread to have <em>decided</em>, and fails if it has
     * not. Distinct from {@link #awaitAtMost} on purpose, and the distinction is
     * not cosmetic -- getting it wrong is what made
     * {@code anArchiveCannotCommitBetweenAnAgentChangesDecisionAndItsCommit}
     * flaky before it was fixed.
     *
     * <p>{@code awaitAtMost} exists for one situation only: waiting on a thread
     * that is parked on a row lock and therefore <em>cannot</em> signal. Giving up
     * and committing is the mechanism there. Everywhere else, a latch that does
     * not fire means the interleaving the test describes never happened, and
     * proceeding anyway produces an answer about a different schedule than the one
     * being asserted -- in both directions: a green that proves nothing, or a red
     * that blames the implementation for a slow machine.
     */
    private static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            if (!latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for " + what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + what, interrupted);
        }
    }

    private static void awaitAtMost(CountDownLatch latch) {
        try {
            latch.await(LOCK_OBSERVATION_WINDOW, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Spring wraps what a transaction callback throws; the assertions want the cause. */
    private static Throwable unwrap(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PreconditionFailedException
                    || cause instanceof InactiveAgentCannotReceiveTasksException
                    || cause instanceof ArchivedProjectTaskIsImmutableException) {
                return cause;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return failure;
    }
}
