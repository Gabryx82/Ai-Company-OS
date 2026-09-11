package com.aicompany.backend.task.dto;

import com.aicompany.backend.task.model.Task;

/**
 * Output contract for a task, so the JPA entity is not the public API shape.
 */
public record TaskResponse(
        Long id,
        String title,
        String description,
        String status,
        String priority
) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority()
        );
    }
}
