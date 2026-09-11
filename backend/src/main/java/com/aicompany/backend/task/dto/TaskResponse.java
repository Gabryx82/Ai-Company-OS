package com.aicompany.backend.task.dto;

import com.aicompany.backend.task.model.Task;

/**
 * Output contract for a task, so the JPA entity is not the public API shape.
 *
 * <p>{@code projectId} is null for a task that belongs to no project, which is
 * the state every task created before the relation existed is in. The project is
 * exposed as an identifier rather than as a nested object: a task listing should
 * not drag a second entity along, and the client that needs the project can ask
 * {@code GET /api/projects/{id}} for it.
 *
 * <p>Built by the service, inside the transaction, because reading
 * {@code projectId} initialises a lazy reference.
 */
public record TaskResponse(
        Long id,
        String title,
        String description,
        String status,
        String priority,
        Long projectId
) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getProjectId()
        );
    }
}
