package com.aicompany.backend.task.exception;

/**
 * Raised when a write would move a task out of a project that is archived.
 *
 * <p>Archiving means "out of the working registry" (ADR-004 §3). ADR-005 §3
 * already stopped an archived project from <em>receiving</em> work; this is the
 * other half, decided in ADR-006 §2: it does not release the work it holds
 * either. Without it the archive is a barrier on the way in and an open door on
 * the way out.
 *
 * <p>Carries the identifier of the project the task is <em>in</em>, not the one
 * it was going to: that is the project a caller has to restore, and therefore
 * the part of the message it can act on. It is also what makes this distinct
 * from {@link ArchivedProjectCannotReceiveTasksException}, which names the
 * destination -- same status code, two different projects to restore.
 */
public class ArchivedProjectTaskIsImmutableException extends RuntimeException {

    public ArchivedProjectTaskIsImmutableException(Long projectId) {
        super("Task belongs to archived project " + projectId
                + " and cannot be modified; restore that project first");
    }
}
