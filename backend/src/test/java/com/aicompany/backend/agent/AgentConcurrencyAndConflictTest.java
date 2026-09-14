package com.aicompany.backend.agent;

import com.aicompany.backend.agent.exception.AgentNameConflictException;
import com.aicompany.backend.agent.exception.IllegalAgentStateTransitionException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.agent.service.AgentService;
import com.aicompany.backend.support.AbstractPostgresTest;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two guarantees that are not visible from a single request: the lifecycle
 * transition is serialised, and the uniqueness of a name is the database's job
 * rather than the service's.
 */
class AgentConcurrencyAndConflictTest extends AbstractPostgresTest {

    private static final long LOCK_OBSERVATION_WINDOW = TimeUnit.SECONDS.toMillis(3);
    private static final long TEST_TIMEOUT_SECONDS = 30;

    @Autowired
    private AgentService service;

    @Autowired
    private AgentRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService threads;

    @BeforeEach
    void startFromAnEmptyRegistry() {
        repository.deleteAll();
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    /**
     * AC-10, invariant I-3 -- rule L1 on a third entity.
     *
     * <p>Two deactivations overlap: both read the agent as active, because
     * neither has committed, and both go on to write. ADR-004 §4 says the second
     * is a caller mistake and must surface as a conflict; without the exclusive
     * lock both report success, which is TD-19 again.
     *
     * <p>The protocol was applied here because L7 makes it universal, not because
     * agents are a contention hotspot. This is what that clause buys: the third
     * entity arrives already serialised, instead of arriving with the same defect
     * and waiting for somebody to notice.
     */
    @Test
    void twoConcurrentDeactivationsProduceOneSuccessAndOneConflict() throws Exception {

        Long agentId = repository.saveAndFlush(new Agent("Code Architect", "Engineer", "x")).getId();

        CountDownLatch firstHasDecided = new CountDownLatch(1);
        CountDownLatch secondHasFinished = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Future<?> first = threads.submit(() -> {
            try {
                newTransaction().execute(status -> {
                    service.deactivate(agentId);
                    firstHasDecided.countDown();
                    awaitAtMost(secondHasFinished);
                    return null;
                });
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });

        Future<?> second = threads.submit(() -> {
            awaitAtMost(firstHasDecided);
            try {
                newTransaction().execute(status -> service.deactivate(agentId));
            } catch (Throwable failure) {
                secondFailure.set(failure);
            }
            secondHasFinished.countDown();
        });

        first.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        second.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        long succeeded = (firstFailure.get() == null ? 1 : 0) + (secondFailure.get() == null ? 1 : 0);

        assertThat(succeeded)
                .as("""
                    ADR-004 section 4: deactivating an already inactive agent is a caller \
                    mistake and must be a conflict. Two concurrent deactivations that both \
                    report success mean nothing serialised the transition -- the same defect \
                    TD-19 recorded for projects, on a third entity.""")
                .isEqualTo(1);

        assertThat(firstFailure.get() != null ? firstFailure.get() : secondFailure.get())
                .isInstanceOf(IllegalAgentStateTransitionException.class);

        assertThat(jdbc.queryForObject("SELECT active FROM agents WHERE id = ?", Boolean.class, agentId))
                .isFalse();
    }

    /**
     * AC-11, invariant I-5. The service's existence check produces a readable
     * conflict in the ordinary case; it is the index that makes uniqueness true,
     * and the translation is what keeps the contract identical either way
     * (ADR-004 §5).
     */
    @Test
    void theUniqueIndexIsWhatEnforcesTheName() {

        repository.saveAndFlush(new Agent("Code Architect", "Engineer", "x"));

        // Straight past the service, to the constraint itself.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO agents (name, role, specialization, active) VALUES (?, ?, ?, TRUE)",
                "CODE ARCHITECT", "Engineer", "x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(AgentRepository.NAME_UNIQUE_INDEX);
    }

    /**
     * AC-11, the other half. Only a violation of the name index becomes a name
     * conflict; anything else propagates untouched, so the first foreign key or
     * NOT NULL column a later task adds does not surface to a client as "that
     * name is taken" (ADR-004 §5, rilievo F-2).
     */
    @Test
    void anyOtherIntegrityViolationIsNotMaskedAsADuplicateName() {

        AgentRepository doubled = mock(AgentRepository.class);
        AgentService isolated = new AgentService(doubled);

        DataIntegrityViolationException unrelated = new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("violates foreign key constraint",
                        new SQLException("agents_owner_fk"), "agents_owner_fk"));

        when(doubled.existsByNormalisedName(anyString())).thenReturn(false);
        when(doubled.saveAndFlush(any(Agent.class))).thenThrow(unrelated);

        assertThatThrownBy(() -> isolated.create("Code Architect", "Engineer", "x"))
                .isSameAs(unrelated)
                .isNotInstanceOf(AgentNameConflictException.class);
    }

    // --- harness -----------------------------------------------------------

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void awaitAtMost(CountDownLatch latch) {
        try {
            latch.await(LOCK_OBSERVATION_WINDOW, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the other transaction", interrupted);
        }
    }
}
