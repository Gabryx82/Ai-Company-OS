package com.aicompany.backend.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Input contract for creating an agent. A new agent is always active, so the
 * lifecycle is not settable here: it goes through the dedicated transitions.
 */
public record AgentCreateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String role,
        @NotBlank @Size(max = 255) String specialization
) {
}
