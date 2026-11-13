package com.aicompany.backend.run.dto;

import com.aicompany.backend.run.model.RunStatus;
import com.aicompany.backend.run.model.TaskRun;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

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
        @Nullable Long agentId,
        RunStatus status,
        @Nullable String requestedModel,
        @Nullable String servedModel,
        String systemPrompt,
        String userPrompt,
        @Nullable String output,
        @Nullable String finishReason,
        @Nullable String failureType,
        @Nullable String failureDetail,
        @Nullable Integer inputTokens,
        @Nullable Integer outputTokens,
        @Nullable Long latencyMs,
        String correlationId,
        String requestedBy,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt,
        /** PHASE 30 (ADR-035): who did the run when that agent has since been deleted ({@code agentId} is then null). */
        @Nullable String deletedAgentName,
        /** PHASE 35 (ADR-041): the provider's own total duration of the call, as it reported it; null when it did not. */
        @Nullable Long providerTotalMs,
        /** PHASE 35: the time the provider spent loading the model, as it reported it. */
        @Nullable Long loadMs,
        /** PHASE 35: the time the provider spent reading the prompt, as it reported it. */
        @Nullable Long promptEvalMs,
        /** PHASE 35: the time the provider spent writing the answer, as it reported it. */
        @Nullable Long evalMs,
        /** PHASE 35: the context window the call asked for (Ollama's num_ctx) -- a setting, not a measure. */
        @Nullable Integer contextWindow
) {

    public static RunResponse from(TaskRun run) {
        return new RunResponse(run.getId(), run.getTaskId(), run.getAgentId(), run.getStatus(),
                run.getRequestedModel(), run.getServedModel(), run.getSystemPrompt(), run.getUserPrompt(),
                run.getOutput(), run.getFinishReason(), run.getFailureType(), run.getFailureDetail(),
                run.getInputTokens(), run.getOutputTokens(), run.getLatencyMs(), run.getCorrelationId(),
                run.getRequestedBy(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt(),
                run.getAgentName(), run.getProviderTotalMs(), run.getLoadMs(), run.getPromptEvalMs(), run.getEvalMs(),
                run.getContextWindow());
    }
}
