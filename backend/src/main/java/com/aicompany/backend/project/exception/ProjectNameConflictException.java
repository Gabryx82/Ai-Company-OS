package com.aicompany.backend.project.exception;

/**
 * Raised when a project name is already taken, compared the way the database
 * compares it — {@code lower(name)}. Mapped to 409.
 *
 * <p>Two paths lead here and they must stay indistinguishable to a client: the
 * ordinary pre-check in the service, and the unique index rejecting a write that
 * slipped past it. Only a violation of {@code projects_name_unique_idx} is
 * translated into this exception; any other integrity failure is left alone.
 */
public class ProjectNameConflictException extends RuntimeException {

    public ProjectNameConflictException(String name) {
        super("A project named '" + name + "' already exists");
    }
}
