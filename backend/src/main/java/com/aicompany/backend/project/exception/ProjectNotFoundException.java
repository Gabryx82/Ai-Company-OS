package com.aicompany.backend.project.exception;

/**
 * Raised when a project identifier does not resolve. Mapped to 404.
 */
public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(Long id) {
        super("No project with id " + id);
    }
}
