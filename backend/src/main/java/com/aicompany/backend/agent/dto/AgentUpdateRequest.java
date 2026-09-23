package com.aicompany.backend.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Same fields as creation, and deliberately not the lifecycle. */
public record AgentUpdateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String role,
        @NotBlank @Size(max = 255) String specialization,

        // TASK-020. A PUT replaces: omitting model sets it back to the engine's default.
        @Size(max = 200)
        @Pattern(regexp = "^[a-z0-9-]+(:[A-Za-z0-9._:/-]+)?$",
                message = "model must be '<provider>' or '<provider>:<model>'")
        String model
) {
}
