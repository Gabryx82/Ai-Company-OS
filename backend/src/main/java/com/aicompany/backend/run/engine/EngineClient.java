package com.aicompany.backend.run.engine;

import java.util.Map;

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
                   String correlationId, Map<String, String> metadata) {
    }

    record ModelInfo(String id, String provider, boolean available, String detail, boolean billed) {
    }

    record ModelList(String defaultModel, java.util.List<ModelInfo> models) {
    }

    /** What comes back, already mapped from the wire. */
    record Completion(String output, String finishReason, String model,
                      int inputTokens, int outputTokens, long latencyMs) {
    }
}
