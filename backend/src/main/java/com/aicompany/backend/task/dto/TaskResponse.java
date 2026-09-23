package com.aicompany.backend.task.dto;

import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;

/**
 * Output contract for a task, so the JPA entity is not the public API shape.
 *
 * <p>{@code projectId} is null for a task that belongs to no project, which is
 * the state every task created before the relation existed is in. The project is
 * exposed as an identifier rather than as a nested object: a task listing should
 * not drag a second entity along, and the client that needs the project can ask
 * {@code GET /api/projects/{id}} for it.
 *
 * <p>{@code agentId} is null for a task nobody has been given yet, and it is
 * deliberately the <em>only</em> thing this record says about the agent. No
 * derived {@code agentActive} or {@code agentStatus}: a task pointing at an
 * inactive agent is a legal state (ADR-010 D3), and whether the agent is active
 * is a question {@code GET /api/agents/{id}} already answers. Publishing a field
 * is irreversible; not publishing one is not (ADR-006 §3).
 *
 * <p>{@code status} is the enum rather than a string, and the JSON is unchanged
 * by that: Jackson writes an enum as its name, which is exactly the text the
 * column holds and the text clients already receive. What changes is that the
 * shape can no longer carry a value outside the vocabulary (ADR-011 §6).
 *
 * <p>Built by the service, inside the transaction, because reading
 * {@code projectId} initialises a lazy reference.
 */
public record TaskResponse(
        Long id,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        Long projectId,
        Long agentId
) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getProjectId(),
                task.getAgentId()
        );
    }
}
