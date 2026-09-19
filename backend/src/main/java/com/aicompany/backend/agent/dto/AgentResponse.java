package com.aicompany.backend.agent.dto;

import com.aicompany.backend.agent.model.Agent;

import java.time.Instant;

/**
 * Output contract for an agent, so the JPA entity is not the public API shape.
 *
 * <p><strong>This record did not change when TD-31 did.</strong> What changed is
 * which of its two lifecycle fields is real: {@code status} used to be derived
 * from {@code active} and to exist nowhere in the database, and since {@code V8}
 * it is the column, while {@code active} is the derived one. The JSON is
 * identical either way, which is the point -- TD-31 was a divergence between two
 * registries inside the database, not a promise to callers, and closing it must
 * not cost a client anything. ADR-012 §4.
 *
 * <p>{@code active} stays for the reason it was kept in the first place:
 * removing it would break every existing client for a rename, and adding a field
 * is reversible in a way that removing one is not. Dropping it is a separate
 * decision nobody has taken.
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
                agent.getStatus().name(),
                agent.getCreatedAt(),
                agent.getUpdatedAt()
        );
    }
}
