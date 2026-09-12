package com.aicompany.backend.project;

import com.aicompany.backend.project.exception.IllegalProjectStateTransitionException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.project.service.ProjectService;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * The project lock protocol of TASK-004, asserted against real PostgreSQL with
 * two threads and hand-controlled transactions.
 *
 * <p><strong>These tests are expected to fail on the current baseline.</strong>
 * They describe the behaviour ADR-006 §4 specifies and the code does not have
 * yet: nothing serialises a project's lifecycle state today, so both races
 * below resolve the wrong way.
 *
 * <h2>Why the transactions are opened by hand</h2>
 *
 * <p>A race needs two transactions to be in flight at the same time, with a
 * controlled interleaving between them. Annotating the test {@code @Transactional}
 * would confine everything to one transaction and make every assertion green for
 * the wrong reason, so each thread opens its own through a
 * {@link TransactionTemplate}. The services are called from inside it: their own
 * {@code @Transactional} joins the surrounding one (REQUIRED), which is precisely
 * what puts the commit boundary under the test's control -- the service still
 * performs its reads and its decision when it is called, and the write lands when
 * the test says so. That gap between the decision and the commit *is* TD-25.
 *
 * <h2>Why there are no sleeps</h2>
 *
 * <p>Every ordering constraint is a latch. The only timed wait is
 * {@link #LOCK_OBSERVATION_WINDOW}, and it is not a sleep standing in for
 * synchronisation: it is how a held lock becomes observable. "The other
 * transaction is blocked" has no positive signal -- it can only be seen as "the
 * other transaction has not reached its next latch" -- so the waiting thread
 * gives up after a bounded window and commits, which is exactly what releases the
 * lock the other one is waiting for. On the current baseline nothing blocks and
 * every latch fires, so today's run is fully deterministic and does not depend on
 * that window at all.
 *
 * <h2>How commit order is established</h2>
 *
 * <p>Not by wall-clock time. Each transaction registers an {@code afterCommit}
 * callback that stamps itself with the next value of a shared counter, so the
 * assertions compare a definite order rather than two timestamps that happen to
 * differ.
 */
class ProjectConcurrencyTest extends AbstractPostgresTest {

    /**
     * How long a thread waits for the other one before concluding it is blocked
     * on a row lock and going ahead with its own commit. Generous on purpose: it
     * is a safety net for the post-fix behaviour, never the mechanism that
     * orders anything.
     */
    private static final long LOCK_OBSERVATION_WINDOW = TimeUnit.SECONDS.toMillis(3);

    /** Ceiling for a whole interleaving; reaching it means the test itself is stuck. */
    private static final long TEST_TIMEOUT_SECONDS = 30;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TaskRepository taskRepository;

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
        threads = Executors.newFixedThreadPool(2);
        commitSequence = new AtomicInteger();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    // ------------------------------------------------------------------
    // AC-9 -- assign in flight, archive concurrent. Both interleavings.
    // ------------------------------------------------------------------

    /**
     * AC-9, the interleaving TD-25 describes, and the one that matters.
     *
     * <p>The assignment reads the project, finds it ACTIVE, decides -- and then,
     * before it commits, the project is archived by somebody else and that
     * archive commits first. The task ends up attached to a project that was
     * already ARCHIVED at the moment the assignment was written.
     *
     * <p>Invariant I-4 forbids exactly that: at the commit of an assignment, the
     * target project <em>was</em> ACTIVE -- not "had been checked". Under the
     * protocol the assignment holds {@code FOR SHARE} on the project row (L2, L3)
     * and the archive's {@code FOR UPDATE} (L1) cannot slip in front of it, so
     * only two outcomes remain legal:
     *
     * <ul>
     *   <li>the assignment commits first and the archive follows -- a project
     *       archived with one more task in it, which ADR-005 §4 allows;</li>
     *   <li>the archive got there first and the assignment is refused with a 409,
     *       leaving the task unassigned.</li>
     * </ul>
     */
    @Test
    void anArchiveCannotCommitBetweenAnAssignmentsDecisionAndItsCommit() throws Exception {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        Long taskId = taskRepository.saveAndFlush(new Task("Wire the planner", null, "OPEN", "HIGH")).getId();

        CountDownLatch assignmentHasDecided = new CountDownLatch(1);
        CountDownLatch archiveHasCommitted = new CountDownLatch(1);

        AtomicInteger assignmentCommit = new AtomicInteger();
        AtomicInteger archiveCommit = new AtomicInteger();
        AtomicReference<Throwable> assignmentFailure = new AtomicReference<>();

        Future<?> assigning = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    stampCommitOrder(assignmentCommit);

                    // Reads the project and decides. On the baseline this is an
                    // unlocked read; under the protocol it is SELECT ... FOR SHARE.
                    taskService.assignToProject(taskId, projectId);

                    assignmentHasDecided.countDown();

                    // The window TD-25 names. Bounded: if the archive is blocked
                    // behind our lock it will never signal, and going ahead to
                    // commit is what lets it through.
                    awaitAtMost(archiveHasCommitted);
                    return null;
                });
            } catch (Throwable failure) {
                assignmentFailure.set(unwrap(failure));
            }
        });

        Future<?> archiving = threads.submit(() -> {
            awaitAtMost(assignmentHasDecided);
            newTransaction().execute(status -> {
                stampCommitOrder(archiveCommit);
                projectService.archive(projectId);
                return null;
            });
            archiveHasCommitted.countDown();
        });

        assigning.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        archiving.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Long assignedProjectId = assignedProjectIdOf(taskId);

        if (assignmentFailure.get() == null) {

            assertThat(assignedProjectId)
                    .as("an assignment that reported success must have been written")
                    .isEqualTo(projectId);

            assertThat(assignmentCommit.get())
                    .as("""
                        I-4: the assignment succeeded, so the project must still have been \
                        ACTIVE when it committed -- that is, the assignment must have \
                        committed before the archive, not after it. An assignment that \
                        commits behind a committed archive has attached a task to a project \
                        that was already out of the working registry (TD-25).""")
                    .isLessThan(archiveCommit.get());

        } else {

            assertThat(assignmentFailure.get())
                    .as("the only legal refusal here is the archived-project conflict")
                    .isInstanceOf(ArchivedProjectCannotReceiveTasksException.class);

            assertThat(assignedProjectId)
                    .as("a refused assignment must leave the task unassigned")
                    .isNull();
        }

        assertThat(statusOf(projectId)).isEqualTo("ARCHIVED");
    }

    /**
     * AC-9, the other order: the archive commits <em>before</em> the assignment
     * begins its read, so there is no window to lose.
     *
     * <p>This is the control case, not a race. It is expected to pass on the
     * baseline too, and that is the point: it shows the harness can produce the
     * refusal, so a failure of the test above is about the interleaving rather
     * than about the fixture. Under the protocol the outcome is unchanged -- the
     * shared lock is taken on a row that already reads ARCHIVED.
     */
    @Test
    void anAssignmentThatBeginsAfterACommittedArchiveIsRefused() throws Exception {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        Long taskId = taskRepository.saveAndFlush(new Task("Wire the planner", null, "OPEN", "HIGH")).getId();

        CountDownLatch archiveHasCommitted = new CountDownLatch(1);

        AtomicInteger assignmentCommit = new AtomicInteger();
        AtomicInteger archiveCommit = new AtomicInteger();
        AtomicReference<Throwable> assignmentFailure = new AtomicReference<>();

        Future<?> archiving = threads.submit(() -> {
            newTransaction().execute(status -> {
                stampCommitOrder(archiveCommit);
                projectService.archive(projectId);
                return null;
            });
            archiveHasCommitted.countDown();
        });

        Future<?> assigning = threads.submit(() -> {
            awaitOrFail(archiveHasCommitted, "the archive to commit");
            try {
                newTransaction().execute(status -> {
                    stampCommitOrder(assignmentCommit);
                    taskService.assignToProject(taskId, projectId);
                    return null;
                });
            } catch (Throwable failure) {
                assignmentFailure.set(unwrap(failure));
            }
        });

        archiving.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assigning.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(archiveCommit.get())
                .as("the fixture must have archived first for this interleaving to mean anything")
                .isEqualTo(1);

        assertThat(assignmentFailure.get())
                .as("an archived project does not receive new work -- ADR-005 §3")
                .isInstanceOf(ArchivedProjectCannotReceiveTasksException.class);

        assertThat(assignedProjectIdOf(taskId))
                .as("a refused assignment must leave the task unassigned")
                .isNull();

        assertThat(statusOf(projectId)).isEqualTo("ARCHIVED");
    }

    // ------------------------------------------------------------------
    // AC-10 -- two concurrent archives.
    // ------------------------------------------------------------------

    /**
     * AC-10, the lifecycle component of TD-19.
     *
     * <p>Two archives of the same project overlap: both read it as ACTIVE,
     * because neither has committed yet, and both go on to write ARCHIVED. The
     * second one is a caller mistake that ADR-004 §4 says must surface as a 409 --
     * a repeated {@code archive} is a double click, a blind retry, a stale local
     * state, and turning it into a silent success makes the state machine
     * unverifiable.
     *
     * <p>Under L1 the second archive's {@code SELECT ... FOR UPDATE} waits for the
     * first to commit and then reads ARCHIVED, so {@code Project.archive()} raises
     * the illegal-transition exception it is supposed to raise. Exactly one
     * success, exactly one conflict.
     */
    @Test
    void twoConcurrentArchivesProduceOneSuccessAndOneConflict() throws Exception {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();

        CountDownLatch firstHasDecided = new CountDownLatch(1);
        CountDownLatch secondHasFinished = new CountDownLatch(1);

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicInteger firstCommit = new AtomicInteger();
        AtomicInteger secondCommit = new AtomicInteger();

        Future<?> first = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    stampCommitOrder(firstCommit);
                    projectService.archive(projectId);
                    firstHasDecided.countDown();
                    awaitAtMost(secondHasFinished);
                    return null;
                });
            } catch (Throwable failure) {
                firstFailure.set(unwrap(failure));
            }
        });

        Future<?> second = threads.submit(() -> {
            awaitAtMost(firstHasDecided);
            try {
                newTransaction().execute(status -> {
                    stampCommitOrder(secondCommit);
                    projectService.archive(projectId);
                    return null;
                });
            } catch (Throwable failure) {
                secondFailure.set(unwrap(failure));
            }
            secondHasFinished.countDown();
        });

        first.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        second.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        long succeeded = countOfNulls(firstFailure, secondFailure);
        long conflicted = countOfIllegalTransitions(firstFailure, secondFailure);

        assertThat(succeeded)
                .as("""
                    ADR-004 section 4: archiving an already archived project is a caller mistake \
                    and must be a 409. Two concurrent archives that both report success \
                    mean nothing serialised the transition -- the state machine cannot be \
                    verified by anybody, including its own tests (TD-19, lifecycle).""")
                .isEqualTo(1);

        assertThat(conflicted)
                .as("the archive that lost the race must have raised the illegal transition")
                .isEqualTo(1);

        assertThat(statusOf(projectId)).isEqualTo("ARCHIVED");
        assertThat(firstCommit.get() + secondCommit.get())
                .as("exactly one of the two transactions committed")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /**
     * A transaction of this thread's own. Spring binds transactions to threads,
     * so two of these running on two threads are genuinely independent.
     */
    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Stamps the surrounding transaction with its position in the global commit
     * order, from inside {@code afterCommit}. A rolled back transaction leaves
     * its slot at zero, which is how the assertions tell "committed second" from
     * "never committed".
     */
    private void stampCommitOrder(AtomicInteger slot) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                slot.set(commitSequence.incrementAndGet());
            }
        });
    }

    /**
     * Waits for a signal that may legitimately never come, because the other
     * thread is parked on a row lock. Returning without it is not a failure: it
     * is the observation, and committing afterwards is what releases the lock.
     */
    private static void awaitAtMost(CountDownLatch latch) {
        try {
            latch.await(LOCK_OBSERVATION_WINDOW, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the other transaction", interrupted);
        }
    }

    /** Waits for a signal the interleaving depends on. Not arriving is a broken fixture. */
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

    /** The exception the service raised, not the wrapper the executor added. */
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.ExecutionException ? failure.getCause() : failure;
    }

    /**
     * Read straight through JDBC: the final state has to come from the rows, not
     * from a persistence context that might still be holding the objects the test
     * itself wrote.
     */
    private Long assignedProjectIdOf(Long taskId) {
        return jdbc.queryForObject("SELECT project_id FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private String statusOf(Long projectId) {
        return jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, projectId);
    }

    @SafeVarargs
    private static long countOfNulls(AtomicReference<Throwable>... outcomes) {
        return java.util.Arrays.stream(outcomes).filter(outcome -> outcome.get() == null).count();
    }

    @SafeVarargs
    private static long countOfIllegalTransitions(AtomicReference<Throwable>... outcomes) {
        return java.util.Arrays.stream(outcomes)
                .map(AtomicReference::get)
                .filter(IllegalProjectStateTransitionException.class::isInstance)
                .count();
    }
}
