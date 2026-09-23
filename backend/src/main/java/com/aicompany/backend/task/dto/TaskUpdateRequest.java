package com.aicompany.backend.task.dto;

import com.aicompany.backend.task.model.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Input contract for {@code PUT /api/tasks/{id}}: the details of a task, replaced
 * as a whole.
 *
 * <p>The details and nothing else. {@code status} is absent because the lifecycle
 * moves along edges (ADR-014) and a PUT would let a caller name a destination;
 * {@code projectId} and {@code agentId} are absent because each association is its
 * own sub-resource with its own rules. A client that sends any of them is not
 * refused -- unknown properties are ignored, as they are on every request of this
 * API -- but nothing it sends there is read. A test asserts it.
 *
 * <p>Same constraints as {@link TaskCreateRequest}, field for field, so that a
 * value accepted at creation is accepted at update and the other way round.
 */
public record TaskUpdateRequest(

        @NotBlank(message = "title is required")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Size(max = 5000, message = "description must be at most 5000 characters")
        String description,

        @NotBlank(message = "priority is required")
        @InTaskPriorityVocabulary
        String priority
) {

    /** Safe after {@code @Valid}, like {@link TaskCreateRequest#statusValue()}. */
    public TaskPriority priorityValue() {
        return TaskPriority.valueOf(priority);
    }
}
