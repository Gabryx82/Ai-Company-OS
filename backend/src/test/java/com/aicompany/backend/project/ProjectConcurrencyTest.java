package com.aicompany.backend.project;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.PreconditionFailedException;
import com.aicompany.backend.project.exception.IllegalProjectStateTransitionException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.project.service.ProjectService;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import com.aicompany.backend.task.exception.ArchivedProjectTaskIsImmutableException;
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
 * <p>All four were written before the implementation and were seen to fail
 * without it: nothing serialised a project's lifecycle state, so every race
 * below resolved the wrong way. They are the executable form of ADR-006 §4.
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
 * lock the other one is waiting for. Before the protocol existed nothing blocked
 * and every latch fired; with it, the window is the expected path in the tests
 * where one transaction is meant to be parked behind another's lock.
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
        Long taskId = taskRepository.saveAndFlush(new Task("Wire the planner", null, TaskStatus.OPEN, "HIGH")).getId();

        // Both callers read before they act, which is what the protocol asks of a
        // client and what makes the interleaving below a race between two informed
        // writers rather than between two guesses. Neither tag goes stale here: the
        // assignment writes the task row, the archive writes the project row, and a
        // version counts its own row only (rule P3).
        Precondition taskAsRead = taskPrecondition(taskId);
        Precondition projectAsRead = projectPrecondition(projectId);

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
                    taskService.assignToProject(taskId, projectId, taskAsRead);

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
                projectService.archive(projectId, projectAsRead);
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
        Long taskId = taskRepository.saveAndFlush(new Task("Wire the planner", null, TaskStatus.OPEN, "HIGH")).getId();

        Precondition taskAsRead = taskPrecondition(taskId);
        Precondition projectAsRead = projectPrecondition(projectId);

        CountDownLatch archiveHasCommitted = new CountDownLatch(1);

        AtomicInteger assignmentCommit = new AtomicInteger();
        AtomicInteger archiveCommit = new AtomicInteger();
        AtomicReference<Throwable> assignmentFailure = new AtomicReference<>();

        Future<?> archiving = threads.submit(() -> {
            newTransaction().execute(status -> {
                stampCommitOrder(archiveCommit);
                projectService.archive(projectId, projectAsRead);
                return null;
            });
            archiveHasCommitted.countDown();
        });

        Future<?> assigning = threads.submit(() -> {
            awaitOrFail(archiveHasCommitted, "the archive to commit");
            try {
                newTransaction().execute(status -> {
                    stampCommitOrder(assignmentCommit);
                    taskService.assignToProject(taskId, projectId, taskAsRead);
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
     * first to commit and then re-reads the row it was blocked on. Exactly one
     * success, exactly one refusal.
     *
     * <h2>What the refusal is, since TASK-008</h2>
     *
     * <p>It used to be the illegal transition of ADR-004 §4, and it is now the
     * precondition of ADR-009. Both callers read the project at the same version
     * and declared it; the winner's commit moved that version, so the loser is
     * refused at rule P1 before {@code Project.archive()} is ever reached.
     *
     * <p>This is a real change to the contract and it is recorded in ADR-009 §8.
     * It loses nothing: the illegal transition is still what a caller gets when it
     * is <em>up to date</em> and asks for a transition the state forbids -- see
     * {@code ProjectLifecycleTest} and {@code ProjectApiTest}, which exercise it
     * sequentially. What changes is which of the two a concurrent loser sees, and
     * 412 is the more truthful of the pair: this caller did not ask for an
     * impossible transition, it asked for a possible one against a state that had
     * already moved, and it cannot know whether it would still want to.
     *
     * <p>The lock is still what this test guards. Take L1 away and the two
     * transactions both pass the precondition on a row neither has locked, and the
     * second flush fails as an optimistic-locking failure instead -- which is
     * neither of the two outcomes asserted below.
     */
    @Test
    void twoConcurrentArchivesProduceOneSuccessAndOneRefusal() throws Exception {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();

        // Both callers read the same state and say so. Neither is guessing; one of
        // them is simply going to be overtaken.
        Precondition bothRead = projectPrecondition(projectId);

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
                    projectService.archive(projectId, bothRead);
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
                    projectService.archive(projectId, bothRead);
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

        assertThat(succeeded)
                .as("""
                    Two concurrent archives that both report success mean nothing serialised \
                    the transition -- the state machine cannot be verified by anybody, \
                    including its own tests (TD-19, lifecycle). L1 is what makes this one.""")
                .isEqualTo(1);

        assertThat(firstFailure.get() != null ? firstFailure.get() : secondFailure.get())
                .as("""
                    The loser read the project at a version the winner has since moved, so it \
                    is refused at the precondition rather than at the transition: it asked for \
                    something possible against a state that no longer exists. ADR-009 section 8.""")
                .isInstanceOf(PreconditionFailedException.class);

        assertThat(statusOf(projectId)).isEqualTo("ARCHIVED");
        assertThat(firstCommit.get() + secondCommit.get())
                .as("exactly one of the two transactions committed")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // TD-30 -- the protocol serialises the project, not the task.
    // ------------------------------------------------------------------

    /**
     * The edge case TD-30 points at, pushed one step further than TD-30 states it.
     *
     * <p>A task T sits in project A. One transaction starts moving it to B, reads
     * its source as A, finds A ACTIVE, and stops short of committing. Meanwhile
     * somebody else moves T into C and commits, and C is then archived and that
     * commits too. Only then does the first transaction commit.
     *
     * <p>At the moment that write lands, T belongs to C and C is ARCHIVED -- and
     * the frozen rule was never evaluated against C, because the transaction that
     * is writing looked at A. The guard is not bypassed by force; it is bypassed
     * by <em>staleness</em>.
     *
     * <p>This is not the same thing TD-30 currently describes. TD-30 says two
     * concurrent reassignments of the same task are last-write-wins, and calls
     * that a race between callers with a valid end state. Here the end state is
     * <em>not</em> valid under TASK-004's own rules: a write committed against a
     * task whose project was archived, which invariant I-3 forbids outright.
     *
     * <p>With L0 in place the interleaving stops being reachable: the second
     * reassignment blocks on the task row until the stale transaction commits,
     * and then re-reads. The assertion is therefore written on the invariant
     * rather than on the schedule -- <strong>no write commits behind the archive
     * of the project the task belongs to</strong> -- which is falsifiable in both
     * worlds. Before L0 the stale write commits third, after the archive. With
     * L0 it commits first, and the outcome is an ordinary sequence of writes
     * each of which saw fresh state.
     *
     * <p>The bounded wait is the expected path here, not a fallback: once the
     * second reassignment is parked behind the task lock it cannot signal, and
     * the stale transaction going ahead to commit is what releases it.
     */
    @Test
    void aStaleReassignmentMustNotCommitAgainstATaskThatMeanwhileMovedIntoAnArchivedProject() throws Exception {

        Long projectA = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        Long projectB = projectRepository.saveAndFlush(new Project("Planner", null)).getId();
        Long projectC = projectRepository.saveAndFlush(new Project("Model Gateway", null)).getId();

        Long taskId = taskRepository.saveAndFlush(new Task("Wire the planner", null, TaskStatus.OPEN, "HIGH")).getId();
        newTransaction().execute(status ->
                taskService.assignToProject(taskId, projectA, taskPrecondition(taskId)));

        // What the stale writer knows. Everything it decides is decided against this.
        Precondition whatTheStaleWriterRead = taskPrecondition(taskId);

        CountDownLatch staleWriterHasDecided = new CountDownLatch(1);
        CountDownLatch theWorldHasMovedOn = new CountDownLatch(1);
        AtomicReference<Throwable> staleWriterFailure = new AtomicReference<>();
        AtomicInteger staleWriterCommit = new AtomicInteger();
        AtomicInteger archiveCommit = new AtomicInteger();

        Future<?> staleWriter = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    stampCommitOrder(staleWriterCommit);

                    // Reads T, sees its project is A, sees A is ACTIVE, decides.
                    // Everything this transaction knows about the world is fixed here.
                    taskService.assignToProject(taskId, projectB, whatTheStaleWriterRead);

                    staleWriterHasDecided.countDown();
                    awaitAtMost(theWorldHasMovedOn);
                    return null;
                });
            } catch (Throwable failure) {
                staleWriterFailure.set(unwrap(failure));
            }
        });

        awaitOrFail(staleWriterHasDecided, "the stale writer to decide against project A");

        // The world tries to move on underneath it, and finds out that it cannot.
        //
        // This is the step TASK-008 changed. The move to C parks behind the stale
        // writer's L0 lock; when the stale writer commits and lets it through, the
        // tag it read before waiting no longer describes the row, and it is refused.
        // That is the protocol working: the world was overtaken and is told so
        // rather than writing over a change it never saw.
        Precondition whatTheWorldReadFirst = taskPrecondition(taskId);
        AtomicReference<Throwable> worldRefusedOnce = new AtomicReference<>();
        try {
            newTransaction().execute(status ->
                    taskService.assignToProject(taskId, projectC, whatTheWorldReadFirst));
        } catch (Throwable failure) {
            worldRefusedOnce.set(unwrap(failure));
        }

        assertThat(worldRefusedOnce.get())
                .as("""
                    The world read the task, then waited on the stale writer's lock. By the \
                    time it was let through, its tag described a row that had moved. Rule P1 \
                    refuses it there -- and this is exactly the report TD-30 said was \
                    missing.""")
                .isInstanceOf(PreconditionFailedException.class);

        // So it does what a client is supposed to do: read again, decide again.
        newTransaction().execute(status ->
                taskService.assignToProject(taskId, projectC, taskPrecondition(taskId)));
        newTransaction().execute(status -> {
            stampCommitOrder(archiveCommit);
            return projectService.archive(projectC, projectPrecondition(projectC));
        });

        Long projectAfterTheWorldMovedOn = assignedProjectIdOf(taskId);
        String statusAfterTheWorldMovedOn = statusOf(projectC);

        theWorldHasMovedOn.countDown();
        staleWriter.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(projectAfterTheWorldMovedOn)
                .as("fixture: the task must really have been moved into C")
                .isEqualTo(projectC);

        assertThat(statusAfterTheWorldMovedOn)
                .as("fixture: C must really have been archived")
                .isEqualTo("ARCHIVED");

        assertThat(assignedProjectIdOf(taskId))
                .as("""
                    I-3: a task whose project is ARCHIVED is frozen for writes. At the instant \
                    this write committed, the task belonged to C and C was archived, so the \
                    write had to be refused -- and the task had to stay in C. A transaction \
                    that evaluated the frozen rule against the project the task used to be in \
                    has not obeyed the rule, it has outrun it. Locking the project rows cannot \
                    catch this: the stale writer holds the rows it read, and C is not one of \
                    them.""")
                .isEqualTo(projectC);

        if (staleWriterFailure.get() == null) {
            assertThat(staleWriterCommit.get())
                    .as("""
                        I-11: the stale write committed, so it must have committed while the                         task still belonged to the project it had read -- that is, before the                         archive. Committing behind a committed archive means it wrote against                         a task that at that instant belonged to an archived project, having                         evaluated the frozen rule against a project the task had already                         left. Locking the project rows cannot catch that; locking the task                         row first (L0) can.""")
                    .isLessThan(archiveCommit.get());
        } else {
            assertThat(staleWriterFailure.get())
                    .as("the only legal refusal here is the frozen-task conflict")
                    .isInstanceOf(ArchivedProjectTaskIsImmutableException.class);
        }
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
    /**
     * The precondition a caller would hold after reading the resource. Taken at
     * the point in the story where that caller would have read it -- which is the
     * whole difference between a fresh tag and a stale one.
     */
    private Precondition taskPrecondition(Long id) {
        return Preconditions.at(taskRepository.findById(id).orElseThrow().getVersion());
    }

    private Precondition projectPrecondition(Long id) {
        return Preconditions.at(projectRepository.findById(id).orElseThrow().getVersion());
    }

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
