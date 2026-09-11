package com.aicompany.backend.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Input contract for creating a task.
 *
 * <p>The identifier is deliberately absent: it is assigned by the database, so a
 * client cannot influence it through the request body.
 *
 * <p>{@code status} and {@code priority} are required free-form strings. They are
 * not enums yet: defining the allowed values and their transitions is a domain
 * decision left to a later task.
 *
 * <p>{@code projectId} is optional, and omitting it creates an unassigned task.
 * That is what keeps this an additive change: a client written against the
 * pre-relation contract keeps working unchanged. An unknown project is a 404 and
 * an archived one a 409 -- the task is not created in either case.
 */
public record TaskCreateRequest(

        @NotBlank(message = "title is required")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Size(max = 5000, message = "description must be at most 5000 characters")
        String description,

        @NotBlank(message = "status is required")
        @Size(max = 255, message = "status must be at most 255 characters")
        String status,

        @NotBlank(message = "priority is required")
        @Size(max = 255, message = "priority must be at most 255 characters")
        String priority,

        @Positive(message = "projectId must be a positive identifier")
        Long projectId
) {
}
