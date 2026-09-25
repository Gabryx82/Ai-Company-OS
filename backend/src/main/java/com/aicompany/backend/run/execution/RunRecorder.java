package com.aicompany.backend.run.execution;

import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.model.RunStatus;
import com.aicompany.backend.run.model.TaskRun;
import com.aicompany.backend.run.repository.TaskRunRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;

/**
 * The executor's writes to a run, each in its own short transaction.
 *
 * <p>Short on purpose: the engine call happens <em>between</em> them, outside any
 * transaction. Holding a database transaction -- and a row lock -- open for the
 * tens of seconds a local model takes would pin a connection per running run and
 * block anything that wanted the row.
 *
 * <p>Not a client write path, so outside the precondition protocol (ADR-009 P4 is
 * about callers who may be stale; the executor is the only writer of these
 * columns and takes the run row lock for each step). It is not a {@code Service}
 * for that reason, and {@code PreconditionCoverageTest} does not list it.
 */
@Component
@Transactional
public class RunRecorder {

    private final TaskRunRepository runs;

    private final com.aicompany.backend.cost.CostService costs;

    public RunRecorder(TaskRunRepository runs, com.aicompany.backend.cost.CostService costs) {
        this.runs = runs;
        this.costs = costs;
    }

    /** What the executor needs to call the engine, captured when the run started. */
    public record StartedRun(Long runId, String model, String system, String user, String correlationId,
                             Long taskId, Long agentId) {
    }

    /**
     * {@code QUEUED → RUNNING}, or nothing -- a run that is gone, already taken by
     * another executor, or already failed by the startup recovery is not run.
     */
    public Optional<StartedRun> markRunning(Long runId) {
        Optional<TaskRun> locked = runs.findByIdForUpdate(runId);
        if (locked.isEmpty() || !locked.get().start()) {
            return Optional.empty();
        }
        TaskRun run = locked.get();
        return Optional.of(new StartedRun(run.getId(), run.getRequestedModel(), run.getSystemPrompt(),
                run.getUserPrompt(), run.getCorrelationId(), run.getTaskId(), run.getAgentId()));
    }

    public void succeed(Long runId, EngineClient.Completion completion) {
        runs.findByIdForUpdate(runId).ifPresent(run -> {
            run.succeed(completion.output(), completion.finishReason(), completion.model(), completion.inputTokens(),
                    completion.outputTokens(), completion.latencyMs());
            // PHASE 24 (ADR-031): the cost at the price of this moment; unknown stays null.
            costs.costOf(run.getRequestedModel() != null ? run.getRequestedModel() : completion.model(),
                    completion.inputTokens(), completion.outputTokens()).ifPresent(run::recordCost);
        });
    }

    public void fail(Long runId, String failureType, String failureDetail) {
        runs.findByIdForUpdate(runId).ifPresent(run -> run.fail(failureType, failureDetail));
    }

    /** Startup recovery: every unfinished run belongs to a process that no longer exists. */
    public int failUnfinished(String failureType, String failureDetail) {
        int failed = 0;
        for (TaskRun run : runs.findAllByStatusIn(EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING))) {
            TaskRun locked = runs.findByIdForUpdate(run.getId()).orElse(null);
            if (locked != null && locked.fail(failureType, failureDetail)) {
                failed++;
            }
        }
        return failed;
    }
}
