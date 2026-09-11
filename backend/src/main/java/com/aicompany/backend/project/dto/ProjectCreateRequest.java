package com.aicompany.backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Input contract for creating a project.
 *
 * <p>Neither the identifier nor the status is a component: the database assigns
 * the first and the domain assigns the second, so a client cannot create a
 * project that is already archived.
 */
public record ProjectCreateRequest(

        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description
) {
}
