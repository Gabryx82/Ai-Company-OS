package com.aicompany.backend.run.execution;

import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.RunFailures;
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
    private final com.aicompany.backend.task.repository.TaskRepository tasks;

    public RunRecorder(TaskRunRepository runs, com.aicompany.backend.cost.CostService costs,
                       com.aicompany.backend.task.repository.TaskRepository tasks) {
        this.runs = runs;
        this.costs = costs;
        this.tasks = tasks;
    }

    /**
     * PHASE 35: what a queued run asks for, read before it starts -- the model it names
     * (null: none was chosen) and its task's words, for the catalog's route.
     */
    public record Pending(String requestedModel, String work) {
    }

    /** Empty when the run is gone or no longer queued. */
    @Transactional(readOnly = true)
    public Optional<Pending> pending(Long runId) {
        return runs.findById(runId).filter(run -> run.getStatus() == RunStatus.QUEUED).flatMap(run -> tasks
                .findById(run.getTaskId()).map(task -> new Pending(run.getRequestedModel(),
                        task.getTitle() + " " + (task.getDescription() == null ? "" : task.getDescription()))));
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
            boolean noAnswer = (completion.output() == null || completion.output().isBlank())
                    && "length".equals(completion.finishReason());
            boolean answered;
            if (noAnswer) {
                // PHASE 32 (TD-65): out of tokens before the first word of the answer.
                answered = run.failSpent(RunFailures.NO_ANSWER, "Il modello ha usato tutti i " + completion.outputTokens()
                                + " token di output senza scrivere una risposta. Rilanciala con un modello che non "
                                + "ragiona così a lungo, o con un limite di token più alto.",
                        completion.finishReason(), completion.model(), completion.inputTokens(),
                        completion.outputTokens(), completion.latencyMs());
            } else {
                answered = run.succeed(completion.output(), completion.finishReason(), completion.model(),
                        completion.inputTokens(), completion.outputTokens(), completion.latencyMs());
            }
            // PHASE 35 (ADR-041): what the provider measured, as it reported it -- also on a run without an
            // answer, never on a run already finished (a cancellation is not rewritten by a late answer).
            EngineClient.Metrics measured = completion.metrics();
            if (answered && measured != null) {
                run.recordMeasures(measured.providerTotalMs(), measured.loadMs(), measured.promptEvalMs(),
                        measured.evalMs(), measured.contextWindow());
            }
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
