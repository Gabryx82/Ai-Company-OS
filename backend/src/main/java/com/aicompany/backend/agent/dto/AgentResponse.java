package com.aicompany.backend.agent.dto;

import com.aicompany.backend.agent.model.Agent;

import java.time.Instant;

/**
 * Output contract for an agent, so the JPA entity is not the public API shape.
 *
 * <p>{@code status} is <strong>derived</strong> from {@code active} and exists
 * nowhere in the database. It is here so that a client sees one vocabulary for
 * "is this in the working registry" across the whole API, while the database
 * keeps one representation of that state rather than two that can drift.
 * ADR-008 §2 and §3; the divergence itself is TD-31.
 *
 * <p>{@code active} stays alongside it. Removing it would break every existing
 * client for a rename, and adding a field is reversible in a way that removing
 * one is not.
 *
 * <p>{@code INACTIVE} and not {@code ARCHIVED}: a project put away and an agent
 * switched off are not the same thing, and using one word for both would be
 * formal consistency against meaning.
 */
public record AgentResponse(
        Long id,
        String name,
        String role,
        String specialization,
        boolean active,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    public static AgentResponse from(Agent agent) {
        return new AgentResponse(
                agent.getId(),
                agent.getName(),
                agent.getRole(),
                agent.getSpecialization(),
                agent.isActive(),
                agent.isActive() ? "ACTIVE" : "INACTIVE",
                agent.getCreatedAt(),
                agent.getUpdatedAt()
        );
    }
}
