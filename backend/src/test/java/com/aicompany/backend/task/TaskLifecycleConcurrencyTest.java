package com.aicompany.backend.task;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.PreconditionFailedException;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.model.TaskTransition;
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
 * The lifecycle under a forced interleaving, not a hoped-for one.
 *
 * <p>Written after a mutation survived: the first version of this check fired two
 * HTTP calls at a barrier and asserted {200, 412}, and it stayed green with the
 * row lock <em>removed</em> -- the calls simply never overlapped. A concurrency
 * test that does not control the schedule proves the serial case twice. This one
 * holds the first caller inside its transaction, after the lock, until the second
 * has had its chance: the technique of {@code PreconditionConcurrencyTest}.
 */
class TaskLifecycleConcurrencyTest extends AbstractPostgresTest {

    private static final long LOCK_OBSERVATION_WINDOW = TimeUnit.SECONDS.toMillis(3);
    private static final long TEST_TIMEOUT_SECONDS = 30;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService threads;

    @BeforeEach
    void startFromAnEmptyRegistry() {
        taskRepository.deleteAll();
        agentRepository.deleteAll();
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    /**
     * Two completions of one task, holding the same valid tag. Exactly one
     * happens; the other is told it was overtaken -- a 412, not a 409 about a task
     * that is already DONE, and not a 500.
     *
     * <p>Mutation M7 (row lock removed) turns this red: the second caller reads the
     * uncommitted-around row at the old version, passes the precondition, and
     * fails at flush with {@code StaleObjectStateException} instead.
     */
    @Test
    void twoCompletionsWithOneTagAreOneCompletionAndOneStaleRefusal() throws Exception {

        Long agent = agentRepository.saveAndFlush(new Agent("Backend", "Engineer", "jvm")).getId();
        Long taskId = taskRepository.saveAndFlush(new Task("Race", null, TaskStatus.IN_PROGRESS, TaskPriority.HIGH)).getId();
        jdbc.update("UPDATE tasks SET agent_id = ? WHERE id = ?", agent, taskId);

        long before = versionOf(taskId);
        var bothRead = Preconditions.at(before);

        CountDownLatch firstIsInsideTheLock = new CountDownLatch(1);
        CountDownLatch secondHasFinished = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Future<?> first = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    taskService.transition(taskId, TaskTransition.COMPLETE, bothRead);
                    firstIsInsideTheLock.countDown();
                    awaitAtMost(secondHasFinished);
                    return null;
                });
            } catch (Throwable failure) {
                firstFailure.set(unwrap(failure));
            }
        });

        Future<?> second = threads.submit(() -> {
            awaitOrFail(firstIsInsideTheLock);
            try {
                newTransaction().execute(status ->
                        taskService.transition(taskId, TaskTransition.COMPLETE, bothRead));
            } catch (Throwable failure) {
                secondFailure.set(unwrap(failure));
            }
            secondHasFinished.countDown();
        });

        first.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        second.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(firstFailure.get()).as("the first caller holds the lock and must succeed").isNull();
        assertThat(secondFailure.get())
                .as("the second caller was overtaken, and must be told precisely that")
                .isInstanceOf(PreconditionFailedException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("DONE");
        assertThat(versionOf(taskId)).as("one write, one increment").isEqualTo(before + 1);
    }

    // --- helpers -----------------------------------------------------------

    private long versionOf(Long taskId) {
        return jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the first caller to take the task row");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Bounded on purpose: the second caller is parked on the row lock and cannot signal. */
    private static void awaitAtMost(CountDownLatch latch) {
        try {
            latch.await(LOCK_OBSERVATION_WINDOW, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

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
