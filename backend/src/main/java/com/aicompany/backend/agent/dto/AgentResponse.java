package com.aicompany.backend.agent.dto;

import com.aicompany.backend.agent.model.Agent;

/**
 * Output contract for an agent, so the JPA entity is not the public API shape.
 */
public record AgentResponse(
        Long id,
        String name,
        String role,
        String specialization,
        boolean active
) {

    public static AgentResponse from(Agent agent) {
        return new AgentResponse(
                agent.getId(),
                agent.getName(),
                agent.getRole(),
                agent.getSpecialization(),
                agent.isActive()
        );
    }
}
