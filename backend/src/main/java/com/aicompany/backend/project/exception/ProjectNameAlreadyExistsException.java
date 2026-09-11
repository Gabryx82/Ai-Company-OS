package com.aicompany.backend.project.exception;

/**
 * Raised when a project name is already taken, ignoring case. Mapped to 409.
 */
public class ProjectNameAlreadyExistsException extends RuntimeException {

    public ProjectNameAlreadyExistsException(String name) {
        super("A project named '" + name + "' already exists");
    }
}
