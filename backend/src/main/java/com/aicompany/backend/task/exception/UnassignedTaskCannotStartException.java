package com.aicompany.backend.task.exception;

/**
 * Starting work that nobody holds (ADR-014 §3).
 *
 * <p>{@code IN_PROGRESS} means somebody is working on it now (ADR-011), and a task
 * with no agent has nobody. Distinct from the inactive-agent refusal because the
 * remedy is different: there, activate or reassign; here, assign.
 */
public class UnassignedTaskCannotStartException extends RuntimeException {

    public UnassignedTaskCannotStartException(Long taskId) {
        super("Task " + taskId + " has no agent; assign one before starting it");
    }
}
