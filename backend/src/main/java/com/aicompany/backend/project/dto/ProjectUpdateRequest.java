package com.aicompany.backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Input contract for updating the descriptive fields of a project.
 *
 * <p>A full replacement of the mutable fields, not a patch: an absent
 * {@code description} clears it. Status is absent on purpose — lifecycle changes
 * go through {@code /archive} and {@code /restore}, where the legality of the
 * transition can be checked.
 */
public record ProjectUpdateRequest(

        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description
) {
}
