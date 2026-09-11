package com.aicompany.backend.project.model;

/**
 * Lifecycle state of a {@link Project}.
 *
 * <p>The set is deliberately closed and small. A project is either part of the
 * working registry or it is out of the way; there is no third state until a
 * concrete requirement asks for one, and adding one means a migration, because
 * the database enforces the same set with a check constraint.
 *
 * <p>There is no {@code DELETED} member on purpose: the Company OS does not
 * delete projects, it archives them. See ADR-004.
 */
public enum ProjectStatus {

    /** Visible in the registry and able to receive work. */
    ACTIVE,

    /** Kept for history and reference, excluded from day-to-day listings. */
    ARCHIVED
}
