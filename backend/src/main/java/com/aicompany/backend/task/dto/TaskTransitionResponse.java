package com.aicompany.backend.task.dto;

import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.model.TaskTransition;
import com.aicompany.backend.task.model.TaskTransitionRecord;

import java.time.Instant;

/**
 * One entry of a task's history (PHASE 32, TD-38): the edge taken, the states it
 * joined, who took it, through what ({@code API}, {@code RUN}, {@code HANDOFF},
 * {@code REVIEW}) and when.
 */
public record TaskTransitionResponse(Long id, Long taskId, TaskTransition transition, TaskStatus from, TaskStatus to,
                                     String actor, TaskTransitionRecord.Via via, Instant at) {

    public static TaskTransitionResponse from(TaskTransitionRecord record) {
        return new TaskTransitionResponse(record.getId(), record.getTaskId(), record.getTransition(),
                record.getFromStatus(), record.getToStatus(), record.getActor(), record.getVia(),
                record.getOccurredAt());
    }
}
