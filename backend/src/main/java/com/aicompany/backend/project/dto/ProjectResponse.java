package com.aicompany.backend.project.dto;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;

import java.time.Instant;

/**
 * Output contract for a project, so the JPA entity is not the public API shape.
 */
public record ProjectResponse(
        Long id,
        String name,
        String description,
        ProjectStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
