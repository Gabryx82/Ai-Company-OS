package com.aicompany.backend.run.model;

/**
 * The lifecycle of a run (ADR-016 §3). Unlike a task's, it is driven by the
 * executor and never by a client: {@code QUEUED → RUNNING → SUCCEEDED | FAILED},
 * and a finished run never changes again.
 */
public enum RunStatus {

    /** Recorded and committed; waiting for an executor thread. */
    QUEUED,

    /** An executor has taken it and the engine has been, or is being, called. */
    RUNNING,

    /** The engine answered with a completion. Says nothing about its quality: a human reviews it. */
    SUCCEEDED,

    /** The engine could not be reached, refused, failed, or the process died while it ran. */
    FAILED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED;
    }
}
