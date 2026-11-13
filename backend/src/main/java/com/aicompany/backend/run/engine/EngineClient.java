package com.aicompany.backend.run.engine;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The control plane's side of the AI Engine contract v1 (ADR-015).
 *
 * <p>An interface so that the executor can be tested against a scripted engine
 * and the HTTP client against a real socket, separately: the first is about what
 * the control plane does with an answer, the second about how an answer is read.
 */
public interface EngineClient {

    /**
     * One completion. Returns only on success; every other outcome is an
     * {@link EngineFailure} whose type says which side of the boundary failed.
     */
    Completion complete(Request request);

    /** {@code GET /v1/models}: what the engine can serve right now (TASK-021). */
    ModelList models();

    /** What is sent. {@code model} null means the engine's own default. */
    record Request(String model, String system, String user, int maxTokens,
                   String correlationId, Map<String, String> metadata, String responseFormat,
                   String responseSchema, @Nullable Integer contextWindow, @Nullable String keepAlive) {

        /** A completion as before PHASE 35: the engine keeps its own window and keep-alive. */
        public Request(String model, String system, String user, int maxTokens,
                       String correlationId, Map<String, String> metadata, String responseFormat,
                       String responseSchema) {
            this(model, system, user, maxTokens, correlationId, metadata, responseFormat, responseSchema, null, null);
        }

        /**
         * PHASE 35 (ADR-041 §11): the same request with how its model runs, as the catalog
         * says -- the window asked of Ollama and how long it keeps the model loaded.
         */
        public Request withModelSettings(@Nullable Integer contextWindow, @Nullable String keepAlive) {
            return new Request(model, system, user, maxTokens, correlationId, metadata, responseFormat, responseSchema,
                    contextWindow, keepAlive);
        }

        /** A plain-text completion, as every request was before PHASE 10. */
        public Request(String model, String system, String user, int maxTokens,
                       String correlationId, Map<String, String> metadata) {
            this(model, system, user, maxTokens, correlationId, metadata, null, null);
        }

        /**
         * A JSON completion constrained by {@code responseSchema} (a JSON Schema,
         * as text) where the provider supports it (PHASE 10).
         */
        public Request(String model, String system, String user, int maxTokens,
                       String correlationId, Map<String, String> metadata, String responseFormat) {
            this(model, system, user, maxTokens, correlationId, metadata, responseFormat, null);
        }
    }

    record ModelInfo(String id, String provider, boolean available, String detail, boolean billed) {
    }

    record ModelList(@Nullable String defaultModel, java.util.List<ModelInfo> models) {
    }

    /**
     * What comes back, already mapped from the wire. {@code metrics} is what the
     * provider itself measured (PHASE 35), null when it measured nothing.
     */
    record Completion(String output, String finishReason, String model,
                      int inputTokens, int outputTokens, long latencyMs, @Nullable Metrics metrics) {

        /** A completion whose provider reported no measures of its own. */
        public Completion(String output, String finishReason, String model,
                          int inputTokens, int outputTokens, long latencyMs) {
            this(output, finishReason, model, inputTokens, outputTokens, latencyMs, null);
        }
    }

    /**
     * PHASE 35 (ADR-041): the provider's own measures of one call -- for Ollama its
     * {@code total_duration}, {@code load_duration}, {@code prompt_eval_duration} and
     * {@code eval_duration} in milliseconds -- and the context window the call asked
     * for ({@code num_ctx}), which is a setting, not a measure. A field the provider
     * did not report is null: nothing is estimated.
     */
    record Metrics(@Nullable Long providerTotalMs, @Nullable Long loadMs, @Nullable Long promptEvalMs,
                   @Nullable Long evalMs, @Nullable Integer contextWindow) {
    }
}
