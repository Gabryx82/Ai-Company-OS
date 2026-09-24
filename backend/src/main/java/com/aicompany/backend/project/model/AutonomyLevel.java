package com.aicompany.backend.project.model;

/**
 * How much the operator delegates on a project -- the progressive Human-in-the-Loop
 * of ADR-022, from learning by doing to final review only.
 * {@code projects_autonomy_level_check} (V14).
 */
public enum AutonomyLevel {
    /** The operator writes and learns; agents explain, ask and verify understanding. */
    GUIDED,
    /** The operator supervises and integrates; agents propose complete work with reasons. */
    SUPERVISED,
    /** Agents implement, test and document; the operator reviews and approves. */
    DELEGATED,
    /** Agents coordinate the work; the operator gives the final functional and visual review. */
    FINAL_REVIEW
}
