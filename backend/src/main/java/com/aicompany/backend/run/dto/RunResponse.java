package com.aicompany.backend.run.dto;

import com.aicompany.backend.run.model.RunStatus;
import com.aicompany.backend.run.model.TaskRun;

import java.time.Instant;

/**
 * Output contract for a run. Everything the record holds, prompts included: what
 * a model was asked is part of what it answered.
 *
 * <p>No entity-tag: a run is not writable by any client (ADR-016 §3), so there is
 * no precondition anybody could need.
 */
public record RunResponse(
        Long id,
        Long taskId,
        Long agentId,
        RunStatus status,
        String requestedModel,
        String servedModel,
        String systemPrompt,
        String userPrompt,
        String output,
        String finishReason,
        String failureType,
        String failureDetail,
        Integer inputTokens,
        Integer outputTokens,
        Long latencyMs,
        String correlationId,
        String requestedBy,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {

    public static RunResponse from(TaskRun run) {
        return new RunResponse(run.getId(), run.getTaskId(), run.getAgentId(), run.getStatus(),
                run.getRequestedModel(), run.getServedModel(), run.getSystemPrompt(), run.getUserPrompt(),
                run.getOutput(), run.getFinishReason(), run.getFailureType(), run.getFailureDetail(),
                run.getInputTokens(), run.getOutputTokens(), run.getLatencyMs(), run.getCorrelationId(),
                run.getRequestedBy(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());
    }
}
