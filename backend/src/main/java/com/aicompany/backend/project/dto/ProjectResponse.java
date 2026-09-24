package com.aicompany.backend.project.dto;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.AutonomyLevel;
import com.aicompany.backend.project.model.PlanStatus;
import com.aicompany.backend.project.model.ProjectStatus;
import com.aicompany.backend.project.model.ProjectType;

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
        Instant updatedAt,
        // PHASE 9 (ADR-020): the profile. Additive fields; nothing above changed.
        ProjectType projectType,
        String stack,
        String workspacePath,
        AutonomyLevel autonomyLevel,
        PlanStatus planStatus
) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                project.getProjectType(),
                project.getStack(),
                project.getWorkspacePath(),
                project.getAutonomyLevel(),
                project.getPlanStatus()
        );
    }
}
