package com.aicompany.backend.run.execution;

import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineFailure;
import com.aicompany.backend.run.engine.EngineProperties;
import com.aicompany.backend.run.engine.RunFailures;
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

    public RunDispatcher(RunRecorder recorder, EngineClient engine, EngineProperties properties,
                         @Qualifier("runExecutor") TaskExecutor executor) {
        this.recorder = recorder;
        this.engine = engine;
        this.properties = properties;
        this.executor = executor;
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

    void execute(Long runId) {
        recorder.markRunning(runId).ifPresent(started -> {
            try {
                EngineClient.Completion completion = engine.complete(new EngineClient.Request(
                        started.model(), started.system(), started.user(), properties.getMaxTokens(),
                        started.correlationId(),
                        Map.of("run_id", String.valueOf(started.runId()),
                                "task_id", String.valueOf(started.taskId()),
                                "agent_id", String.valueOf(started.agentId()))));
                recorder.succeed(runId, completion);
            } catch (EngineFailure failure) {
                recorder.fail(runId, failure.type(), failure.detail());
            } catch (RuntimeException unexpected) {
                log.error("Run {} (correlation {}) failed unexpectedly", runId, started.correlationId(), unexpected);
                recorder.fail(runId, RunFailures.INTERNAL, "The run failed inside the control plane; see its log");
            }
        });
    }
}
