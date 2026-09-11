package com.aicompany.backend.task.exception;

/**
 * Raised when something tries to attach a task to an archived project. Mapped to
 * 409.
 *
 * <p>Same status, and same reasoning, as the refusals on the project side: the
 * request is turned down because of the state of the resource, not because of
 * who is asking, and the caller can lift the refusal itself by restoring the
 * project and repeating the request. See ADR-005 §3.
 */
public class ArchivedProjectCannotReceiveTasksException extends RuntimeException {

    public ArchivedProjectCannotReceiveTasksException(Long projectId) {
        super("Project " + projectId + " is archived and cannot receive tasks: restore it first");
    }
}
