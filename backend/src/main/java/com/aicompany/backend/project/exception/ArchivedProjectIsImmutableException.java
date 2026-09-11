package com.aicompany.backend.project.exception;

/**
 * Raised when something tries to change the descriptive fields of an archived
 * project. Mapped to 409, like the illegal transitions it sits next to.
 *
 * <p>An archived project is out of the working registry, and what is out of the
 * registry does not change: restore it first. See ADR-004 §8.
 */
public class ArchivedProjectIsImmutableException extends RuntimeException {

    public ArchivedProjectIsImmutableException() {
        super("An archived project cannot be modified: restore it first");
    }
}
