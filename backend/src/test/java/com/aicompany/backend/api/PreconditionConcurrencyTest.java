package com.aicompany.backend.api;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.task.model.Task;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant I-4: the precondition is compared <em>under the row lock</em>.
 *
 * <p>This is the one property no sequential test can reach, and the reason rule
 * P1 fixes a position rather than a layer. Two callers read the same task, both
 * hold a tag that is still valid, and both decide to move it somewhere. A
 * comparison made before L0 would let both through -- each reads the version
 * nobody has changed yet -- and the second write would land on top of the first
 * with nobody told. That is a check-then-act, and swapping two lines is all it
 * takes to build one.
 *
 * <h2>Why this file could not be red on the baseline</h2>
 *
 * <p>It exercises an API that did not exist before the implementation, so it was
 * written with it rather than before it. That is a weaker starting point than the
 * rest of TASK-008, and the mutation below is what makes up for it: the assertion
 * has been seen to fail with the implementation present and one line moved, which
 * is the property being claimed rather than a proxy for it.
 *
 * <h2>How the interleaving is forced</h2>
 *
 * <p>Latches, never sleeps, with one bounded wait -- and that wait is the
 * mechanism rather than a fallback. "The other transaction is parked on a row
 * lock" has no positive signal: it can only be observed as "it has not reached
 * its next latch". So the first transaction waits a bounded time, concludes that,
 * and commits -- and its commit is exactly what releases the second one.
 */
class PreconditionConcurrencyTest extends AbstractPostgresTest {

    private static final long LOCK_OBSERVATION_WINDOW = TimeUnit.SECONDS.toMillis(3);
    private static final long TEST_TIMEOUT_SECONDS = 30;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService threads;

    @BeforeEach
    void startFromAnEmptyRegistry() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    /**
     * I-4, AC-4. Two reassignments of one task, both carrying the same valid tag.
     *
     * <p>Exactly one may succeed. The other must be refused at the precondition --
     * not because the tag was stale when it was sent, but because it had gone
     * stale by the time this transaction was allowed to look at the row. Only a
     * comparison that happens after the lock can notice that.
     *
     * <p><strong>The mutation that proves it</strong>, run on 2026-09-16. Move
     * {@code precondition.requireSatisfiedBy(task.getVersion())} in
     * {@code TaskService.assignToProject} above {@code findByIdForUpdate} and this
     * test goes red. <strong>Nothing else in the suite notices</strong> --
     * {@code PreconditionContractTest} stayed 17 green -- which is what makes this
     * test worth its runtime.
     *
     * <p>It goes red in a way worth recording, because it is not the way that was
     * predicted. The second caller compares against a version it read <em>before</em>
     * the lock, when it was still current, and passes -- and then the locked read
     * finds the row at a different version from the one already in its persistence
     * context and Hibernate raises {@code StaleObjectStateException}. So the row
     * survives the mutant: what is lost is the answer. The caller is told an
     * internal error instead of "you were overtaken, re-read and retry", which is
     * a 500 where a 412 belongs.
     *
     * <p>That is a second, smaller thing the row version buys, and it is worth
     * saying plainly because it is <em>not</em> the detection (ADR-009 §2.2 is
     * still right that the automatic check cannot detect anything on a correctly
     * ordered path): because the counter is a real JPA version, getting the order
     * wrong fails loudly rather than losing a write quietly.
     */
    @Test
    void twoConcurrentWritersWithTheSameTagProduceOneSuccessAndOneRefusal() throws Exception {

        Long destinationA = projectRepository.saveAndFlush(new Project("Planner", null)).getId();
        Long destinationB = projectRepository.saveAndFlush(new Project("Gateway", null)).getId();
        Long taskId = taskRepository.saveAndFlush(
                new Task("Wire the planner", null, "OPEN", "HIGH")).getId();

        // One read, two callers. Both tags are valid at this instant, and exactly
        // one of them is going to stop being valid without its holder knowing.
        Precondition bothRead = currentTaskPrecondition(taskId);

        CountDownLatch firstIsInsideTheLock = new CountDownLatch(1);
        CountDownLatch secondHasFinished = new CountDownLatch(1);

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Future<?> first = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    taskService.assignToProject(taskId, destinationA, bothRead);
                    firstIsInsideTheLock.countDown();

                    // Bounded: the second caller is parked on the task row and cannot
                    // signal. Going ahead to commit is what lets it through.
                    awaitAtMost(secondHasFinished);
                    return null;
                });
            } catch (Throwable failure) {
                firstFailure.set(unwrap(failure));
            }
        });

        Future<?> second = threads.submit(() -> {
            awaitOrFail(firstIsInsideTheLock, "the first writer to take the task row");
            try {
                newTransaction().execute(status ->
                        taskService.assignToProject(taskId, destinationB, bothRead));
            } catch (Throwable failure) {
                secondFailure.set(unwrap(failure));
            }
            secondHasFinished.countDown();
        });

        first.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        second.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        long succeeded = (firstFailure.get() == null ? 1 : 0) + (secondFailure.get() == null ? 1 : 0);

        assertThat(succeeded)
                .as("""
                    I-4: two writers holding the same tag, and both told they succeeded, means \
                    the comparison happened somewhere that did not hold the row -- a \
                    check-then-act. One of these two wrote over a change it never saw, which is \
                    the defect TD-30 named and the whole reason this protocol exists.""")
                .isEqualTo(1);

        assertThat(firstFailure.get() != null ? firstFailure.get() : secondFailure.get())
                .as("the caller that lost must be told it was overtaken, and told so precisely")
                .isInstanceOf(PreconditionFailedException.class);

        assertThat(assignedProjectIdOf(taskId))
                .as("the task must be where the writer that actually succeeded put it")
                .isEqualTo(firstFailure.get() == null ? destinationA : destinationB);

        assertThat(versionOf(taskId))
                .as("""
                    one write happened, so the counter moved by one. Two means the refused \
                    caller wrote anyway; zero means neither did and the success was a lie.""")
                .isEqualTo(1L);
    }

    // --- helpers -----------------------------------------------------------

    private Precondition currentTaskPrecondition(Long taskId) {
        return Preconditions.at(versionOf(taskId));
    }

    private long versionOf(Long taskId) {
        return jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private Long assignedProjectIdOf(Long taskId) {
        return jdbc.queryForObject("SELECT project_id FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
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
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            if (cause instanceof PreconditionFailedException) {
                return cause;
            }
            cause = cause.getCause();
        }
        return cause;
    }
}
