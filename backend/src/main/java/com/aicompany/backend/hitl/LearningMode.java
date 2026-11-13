package com.aicompany.backend.hitl;

import com.aicompany.backend.project.model.AutonomyLevel;

/**
 * PHASE 37 (ADR-044): how a task is handed over, as far as learning goes. The code is
 * the agent's to write in every mode; what changes is who puts it in the files, and
 * what the operator is left to do with it.
 */
public enum LearningMode {

    /**
     * The agent writes a lesson -- the code, explained line by line -- and does not touch
     * the project's files: the operator copies each step where it belongs, and learns it.
     */
    TEACH,

    /**
     * The operator cannot follow now: the agent implements the task by itself, and leaves
     * a lesson on what it built, for the operator to go through after.
     */
    RECAP,

    /** No lesson. */
    NONE;

    /** What a handoff asks when the operator does not say: the project's level decides. */
    public static LearningMode defaultFor(AutonomyLevel level) {
        return switch (level) {
            case GUIDED -> TEACH;
            case SUPERVISED -> RECAP;
            case DELEGATED, FINAL_REVIEW -> NONE;
        };
    }
}
