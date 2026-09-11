package com.aicompany.backend.task.exception;

/**
 * Raised when a task identifier does not resolve. Mapped to 404.
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(Long id) {
        super("No task with id " + id);
    }
}
