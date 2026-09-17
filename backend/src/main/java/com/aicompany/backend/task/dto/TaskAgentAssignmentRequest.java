package com.aicompany.backend.task.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Input contract for {@code PUT /api/tasks/{id}/agent}.
 *
 * <p>Deliberately the mirror of {@link TaskProjectAssignmentRequest}, down to
 * {@code agentId} not being nullable: clearing the assignment is not part of this
 * contract. The pressure to allow it is real here -- deactivating an agent makes
 * one want to <em>unassign</em> -- but the recovery path the domain actually has
 * is to <em>reassign</em>, and that one exists. A route that can be added
 * tomorrow without breaking anybody is not guessed at today (ADR-005 §5, TD-35).
 */
public record TaskAgentAssignmentRequest(
        @NotNull(message = "agentId is required")
        @Positive(message = "agentId must be a positive identifier")
        Long agentId
) {
}
