package com.aicompany.backend.run.execution;

import com.aicompany.backend.cost.CostProblemException;
import com.aicompany.backend.cost.CostService;
import com.aicompany.backend.llm.service.LocalModelGate;
import com.aicompany.backend.llm.service.ModelRouter;
import com.aicompany.backend.llm.service.ModelSettings;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineFailure;
import com.aicompany.backend.run.engine.EngineProperties;
import com.aicompany.backend.run.engine.RunFailures;
import com.aicompany.backend.run.service.RunCancelled;
import com.aicompany.backend.run.service.RunQueued;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Optional;

/**
 * Takes committed runs to the engine, off the request thread (ADR-016 §3).
 *
 * <p>Every run ends. Whatever happens between {@code markRunning} and the answer --
 * an engine problem, an unreachable engine, a bug here -- the run is recorded as
 * {@code FAILED} with a type, and a run that never got a thread is failed too. A
 * run left {@code RUNNING} by a live process would be a lie with no end.
 */
@Component
public class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);

    private final RunRecorder recorder;
    private final EngineClient engine;
    private final EngineProperties properties;
    private final TaskExecutor executor;
    private final CostService costs;
    private final ModelRouter router;
    private final ModelSettings settings;
    private final LocalModelGate gate;
    private final com.aicompany.backend.llm.repository.LlmModelRepository catalog;
    private final TaskExecutor localExecutor;
    /** The run each executor thread is waiting on the engine for (TD-39). */
    private final Map<Long, Thread> working = new java.util.concurrent.ConcurrentHashMap<>();

    public RunDispatcher(RunRecorder recorder, EngineClient engine, EngineProperties properties,
                         @Qualifier("runExecutor") TaskExecutor executor, CostService costs, ModelRouter router,
                         ModelSettings settings, LocalModelGate gate,
                         com.aicompany.backend.llm.repository.LlmModelRepository catalog,
                         @Qualifier("localRunExecutor") TaskExecutor localExecutor) {
        this.localExecutor = localExecutor;
        this.router = router;
        this.settings = settings;
        this.gate = gate;
        this.catalog = catalog;
        this.recorder = recorder;
        this.engine = engine;
        this.properties = properties;
        this.executor = executor;
        this.costs = costs;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(RunQueued queued) {
        try {
            executor.execute(() -> execute(queued.runId()));
        } catch (TaskRejectedException rejected) {
            recorder.fail(queued.runId(), RunFailures.REJECTED,
                    "Every executor thread was busy and the queue was full; launch the run again later");
        }
    }

    /**
     * Startup recovery. A run that was queued or running when the previous
     * process stopped will never be finished by anybody: it is failed with a type
     * that says so, and the operator can launch it again.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        int failed = recorder.failUnfinished(RunFailures.INTERRUPTED,
                "The control plane stopped while this run was queued or running");
        if (failed > 0) {
            log.warn("Failed {} run(s) interrupted by the previous shutdown", failed);
        }
    }

    /**
     * PHASE 32 (TD-39): the run is already recorded as cancelled; the thread waiting on
     * the engine for it, if any, is interrupted -- the HTTP call gives up and closes
     * the connection. Only while that thread is still on that run.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelled(RunCancelled cancelled) {
        synchronized (working) {
            Thread thread = working.get(cancelled.runId());
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    void execute(Long runId) {
        Optional<RunRecorder.Pending> pending = recorder.pending(runId);
        if (pending.isEmpty()) {
            return; // gone, or already failed (cancelled, recovered) while queued
        }
        String model;
        try {
            model = modelFor(runId, pending.get());
        } catch (EngineFailure failure) {
            recorder.fail(runId, failure.type(), failure.detail());
            return;
        }
        if (model == null) {
            return; // refused by the budget, and recorded as such
        }
        // PHASE 35 (ADR-041 §12): a local model runs one at a time, on its own executor: the run
        // waits queued for its turn there, and this thread is free for the runs that do not wait.
        if (gate.isLocal(model)) {
            try {
                localExecutor.execute(() -> runInTurn(runId, model));
            } catch (TaskRejectedException rejected) {
                recorder.fail(runId, RunFailures.REJECTED,
                        "Too many runs were waiting for the local model; launch the run again later");
            }
            return;
        }
        runNow(runId, model);
    }

    /** A local run, in its turn -- the gate also waits for a plan generation on a local model. */
    private void runInTurn(Long runId, String model) {
        try (LocalModelGate.Permit turn = gate.enter(model)) {
            runNow(runId, model);
        } catch (EngineFailure interrupted) {
            recorder.fail(runId, interrupted.type(), interrupted.detail());
        }
    }

    private void runNow(Long runId, String model) {
        recorder.markRunning(runId).ifPresent(started -> {
            working.put(runId, Thread.currentThread());
            try {
                runOnEngine(runId, started, model);
            } finally {
                synchronized (working) {
                    working.remove(runId);
                    // a cancellation that arrived as the answer did must not reach the next run of this thread
                    Thread.interrupted();
                }
            }
        });
    }

    /**
     * The model a run uses: the one it names; otherwise (PHASE 35, ADR-041 §12) the one the
     * catalog routes its task to; otherwise the engine's default. A model nobody named
     * passes the same budget check as one a person names (PHASE 32, TD-54), and is sent by
     * name, so the model checked is the model used.
     */
    private String modelFor(Long runId, RunRecorder.Pending pending) {
        if (pending.requestedModel() != null) {
            return pending.requestedModel();
        }
        EngineClient.ModelList live = engine.models();
        java.util.Set<String> served = new java.util.HashSet<>();
        live.models().stream().filter(EngineClient.ModelInfo::available).forEach(m -> served.add(m.id()));
        Optional<ModelRouter.Pick> routed = router.pick(router.route(pending.work()).role(), catalog.findAll(),
                served::contains);
        String model = routed.map(p -> p.model().getKey()).orElse(live.defaultModel());
        try {
            costs.requireBudget(model);
        } catch (CostProblemException refused) {
            recorder.fail(runId, RunFailures.BUDGET, (routed.isPresent() ? "Il modello scelto per il ruolo della task ("
                    : "Il modello di default dell'AI Engine (") + model + ") è a consumo: " + refused.getMessage());
            return null;
        }
        return model;
    }

    private void runOnEngine(Long runId, RunRecorder.StartedRun started, String model) {
        try {
            // PHASE 35 (ADR-041 §11): with how the catalog says the model runs.
            EngineClient.Completion completion = engine.complete(settings.apply(new EngineClient.Request(
                    model, started.system(), started.user(), properties.getMaxTokens(),
                    started.correlationId(),
                    Map.of("run_id", String.valueOf(started.runId()),
                            "task_id", String.valueOf(started.taskId()),
                            "agent_id", String.valueOf(started.agentId())))));
            recorder.succeed(runId, completion);
        } catch (EngineFailure failure) {
            // an interruption is a cancellation already recorded: the flag is cleared so the
            // record can still reach the database, and it does not overwrite the run
            Thread.interrupted();
            recorder.fail(runId, failure.type(), failure.detail());
        } catch (RuntimeException unexpected) {
            log.error("Run {} (correlation {}) failed unexpectedly", runId, started.correlationId(), unexpected);
            recorder.fail(runId, RunFailures.INTERNAL, "The run failed inside the control plane; see its log");
        }
    }
}
