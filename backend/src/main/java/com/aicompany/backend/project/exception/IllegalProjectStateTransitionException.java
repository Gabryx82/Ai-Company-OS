package com.aicompany.backend.project.exception;

import com.aicompany.backend.project.model.ProjectStatus;

/**
 * Raised when a lifecycle transition is not legal from the current state, for
 * example archiving a project that is already archived. Mapped to 409.
 *
 * <p>The alternative — treating a repeated archive as a silent no-op — hides a
 * client bug and makes the state machine untestable, so the illegal transition
 * is reported instead.
 */
public class IllegalProjectStateTransitionException extends RuntimeException {

    public IllegalProjectStateTransitionException(ProjectStatus from, ProjectStatus to) {
        super("A project cannot go from " + from + " to " + to);
    }
}
