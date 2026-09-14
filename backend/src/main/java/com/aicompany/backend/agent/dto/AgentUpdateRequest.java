package com.aicompany.backend.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Same fields as creation, and deliberately not the lifecycle. */
public record AgentUpdateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String role,
        @NotBlank @Size(max = 255) String specialization
) {
}
