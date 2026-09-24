package com.aicompany.backend.project.model;

/** Where the implementation plan of a project stands. {@code projects_plan_status_check} (V14). */
public enum PlanStatus {
    /** No plan yet. */
    NONE,
    /** A plan was generated or imported and waits for the operator. */
    DRAFT,
    /** The operator approved the plan; its approved phases may run. */
    APPROVED
}
