package com.aicompany.backend.task.dto;

import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Input contract for creating a task.
 *
 * <p>The identifier is deliberately absent: it is assigned by the database, so a
 * client cannot influence it through the request body.
 *
 * <p>{@code status} must be one of {@link com.aicompany.backend.task.model.TaskStatus},
 * and it stays declared here as a {@code String} on purpose. Typing it as the
 * enum would turn an unknown value into an unreadable-body 400 that names no
 * field and asserts something untrue about the request; the constraint keeps it
 * a validation failure with {@code errors.status}. The reasoning is in
 * {@link InTaskStatusVocabulary} and ADR-011 §4.
 *
 * <p>{@code priority} has had its own closed vocabulary since TASK-016
 * ({@link TaskPriority}, TD-36), guarded the same way and for the same reason.
 *
 * <p>{@code agentId} is optional in the same way, and for the same reason
 * ADR-005 gave for the project: a rejected agent means no task at all, so a
 * client never has to clean up one that was created and then failed to be
 * staffed. An inactive agent is a 409 and an unknown one a 404, and in neither
 * case does the task come into existence.
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
        @InTaskStatusVocabulary
        String status,

        @NotBlank(message = "priority is required")
        @InTaskPriorityVocabulary
        String priority,

        @Positive(message = "projectId must be a positive identifier")
        Long projectId,
        @Positive(message = "agentId must be a positive identifier")
        Long agentId
) {

    /**
     * The status as a domain value.
     *
     * <p>The conversion lives here, next to the field and the constraint that
     * makes it safe, rather than in the controller or the service. It is the same
     * division {@code Precondition.fromHeader} draws: the wire format is
     * interpreted at the edge, and nothing below the edge sees a string it has to
     * trust.
     *
     * <p>It is safe because {@link InTaskStatusVocabulary} has already run --
     * {@code @Valid} on the controller parameter is what sequences the two. That
     * is a real dependency and not a comfortable assumption: remove the
     * constraint and this throws {@link IllegalArgumentException}, which the
     * advice reports as a 500, and {@code TaskStatusVocabularyTest} turns red
     * expecting a 400. Verified by mutation rather than argued
     * ({@code tasks/TASK-010/ARTIFACT.md} §5).
     */
    public TaskStatus statusValue() {
        return TaskStatus.valueOf(status);
    }

    /** The priority as a domain value; safe for the same reason as {@link #statusValue()}. */
    public TaskPriority priorityValue() {
        return TaskPriority.valueOf(priority);
    }
}
