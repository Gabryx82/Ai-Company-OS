package com.aicompany.backend.task.exception;

import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.model.TaskTransition;

import java.util.Locale;

/**
 * The transition is not an edge from the state the task is in (ADR-014). A 409,
 * like its project and agent twins: the refusal is about the current state, and
 * a caller that re-reads the task learns what it can do instead.
 */
public class IllegalTaskStateTransitionException extends RuntimeException {

    public IllegalTaskStateTransitionException(TaskStatus current, TaskTransition transition) {
        super("A task that is " + current + " cannot " + transition.name().toLowerCase(Locale.ROOT)
                + "; that transition goes from " + transition.from() + " to " + transition.to());
    }
}
