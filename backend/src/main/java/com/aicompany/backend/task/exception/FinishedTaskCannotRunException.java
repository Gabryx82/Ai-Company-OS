package com.aicompany.backend.task.exception;

/**
 * A run on a task that is {@code DONE} (ADR-016 §2). The work is finished; asking
 * an agent to do it again is either a mistake or a reopening, and a reopening is
 * its own edge ({@code POST /api/tasks/{id}/reopen}), taken on purpose.
 */
public class FinishedTaskCannotRunException extends RuntimeException {

    public FinishedTaskCannotRunException(Long taskId) {
        super("Task " + taskId + " is DONE; reopen it before running it again");
    }
}
