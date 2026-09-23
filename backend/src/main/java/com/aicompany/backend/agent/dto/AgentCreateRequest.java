package com.aicompany.backend.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Input contract for creating an agent. A new agent is always active, so the
 * lifecycle is not settable here: it goes through the dedicated transitions.
 */
public record AgentCreateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String role,
        @NotBlank @Size(max = 255) String specialization,

        // TASK-020: optional. Absent means the engine's default model. The shape is
        // checked here; whether the model exists is the engine's to say, at run time.
        @Size(max = 200)
        @Pattern(regexp = "^[a-z0-9-]+(:[A-Za-z0-9._:/-]+)?$",
                message = "model must be '<provider>' or '<provider>:<model>'")
        String model
) {
}
