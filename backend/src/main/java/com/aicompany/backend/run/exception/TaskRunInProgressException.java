package com.aicompany.backend.run.exception;

/**
 * The task already has a run that is queued or running (ADR-016 §2). One at a
 * time: two agents -- or the same one twice -- answering the same task at once
 * would give the operator two results to reconcile and no way to say which one
 * the task's state reflects.
 */
public class TaskRunInProgressException extends RuntimeException {

    public TaskRunInProgressException(Long taskId) {
        super("Task " + taskId + " already has a run in progress; wait for it to finish");
    }
}
