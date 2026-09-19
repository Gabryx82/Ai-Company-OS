package com.aicompany.backend.agent.model;

/**
 * Lifecycle state of an {@link Agent}.
 *
 * <p>The set is closed and small, and the database enforces the same set with
 * {@code agents_status_check} -- the two guards ADR-004 §2 established for
 * projects, applied here by TD-31 (ADR-012). Adding a value is therefore not an
 * application-only change: it needs a migration that rewrites the constraint.
 *
 * <p><strong>Why {@code INACTIVE} and not {@code ARCHIVED}.</strong> ADR-008 §2
 * settled this before the enum existed and the answer did not change: a project
 * put away and an agent switched off are not the same thing, and using one word
 * for both would be formal consistency against meaning. What TD-31 unified is
 * the <em>form</em> of the representation -- a closed enum backed by a check
 * constraint -- not the vocabulary.
 *
 * <p>There is no {@code DELETED} member, for the reason ADR-004 §3 gave: this
 * system deactivates agents, it does not delete them.
 *
 * <p>These two names are not new to callers. {@code AgentResponse.status} has
 * been publishing exactly this vocabulary since TASK-007, derived from the
 * boolean; TD-31 made it real in the database rather than computed on the way
 * out.
 */
public enum AgentStatus {

    /** In the working registry and able to receive work. */
    ACTIVE,

    /** Switched off: it receives no new work, and it can be switched back on. */
    INACTIVE
}
