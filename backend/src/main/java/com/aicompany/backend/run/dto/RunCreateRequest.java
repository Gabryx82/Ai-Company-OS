package com.aicompany.backend.run.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Input contract for {@code POST /api/tasks/{id}/runs}. The body is optional; so
 * is the one field.
 *
 * <p>{@code model} is an engine model id ({@code "ollama:llama3.2:3b"},
 * {@code "anthropic:claude-opus-5"}). Absent means: the agent's configured model
 * if it has one, otherwise the engine's default. Which models exist is the
 * engine's to say -- the control plane checks only the shape, and an unknown one
 * comes back as a failed run with the engine's {@code unknown-model} type.
 */
public record RunCreateRequest(
        @Size(max = 200, message = "model must be at most 200 characters")
        @Pattern(regexp = "^[a-z0-9-]+(:[A-Za-z0-9._:/-]+)?$",
                message = "model must be '<provider>' or '<provider>:<model>'")
        String model
) {
}
