package com.aicompany.backend.task.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Input contract for {@code PUT /api/tasks/{id}/project}.
 *
 * <p>A single required field. {@code projectId} is not nullable here even though
 * the column is: clearing the association is not part of this contract, because
 * an endpoint that can be added later without breaking anybody should not be
 * guessed at now, whereas one that ships and then has to be withdrawn breaks
 * every client that found it. See ADR-005 §5.
 */
public record TaskProjectAssignmentRequest(

        @NotNull(message = "projectId is required")
        @Positive(message = "projectId must be a positive identifier")
        Long projectId
) {
}
