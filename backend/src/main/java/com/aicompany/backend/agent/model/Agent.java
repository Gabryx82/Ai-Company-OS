package com.aicompany.backend.agent.model;

import com.aicompany.backend.agent.exception.IllegalAgentStateTransitionException;
import com.aicompany.backend.agent.exception.InactiveAgentIsImmutableException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * An agent: a worker the Company OS can put to a task.
 *
 * <p>Like {@link com.aicompany.backend.project.model.Project}, it owns its
 * lifecycle rather than exposing a settable flag. A caller asks for a transition
 * and the entity decides whether that transition is legal, so an invalid state
 * cannot be reached through the service or the controller.
 *
 * <p><strong>The lifecycle is an {@link AgentStatus}, as it is on Project.</strong>
 * It used to be a {@code boolean active}, and ADR-008 §2 kept it that way
 * deliberately: unifying meant dropping a column, which the autonomous charter
 * puts behind a human decision whenever a reasonable alternative exists, and one
 * did. That decision was taken on 2026-09-19 and {@code V8} carried it out --
 * TD-31, ADR-012. The backfill was a bijection, so nothing was lost.
 *
 * <p><strong>{@code isActive()} survives as a derived reader</strong>, and the
 * direction of the derivation is the whole point of the change: the database now
 * holds the status and the boolean is computed, where before the database held
 * the boolean and the status was computed. The API shape did not move.
 *
 * <p>Columns mirror {@code V1__create_agents_and_tasks.sql} plus
 * {@code V4__add_agent_registry_columns.sql} and {@code V8__unify_agent_lifecycle.sql}
 * exactly: Hibernate runs in validate mode, so a divergence fails startup instead
 * of altering the schema.
 */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String specialization;

    /**
     * The lifecycle state. {@code STRING} and never {@code ORDINAL}: the check
     * constraint {@code agents_status_check} compares against the names, so the
     * column has to hold them, and an ordinal would let reordering
     * {@link AgentStatus} rewrite the meaning of every row without touching one.
     *
     * <p>No setter, like every other lifecycle in this codebase: a caller asks
     * for a transition and the entity decides whether it is legal.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The row version of ADR-009: a counter the persistence layer increments on
     * every UPDATE of <em>this</em> row, and nothing else touches (rule P3).
     *
     * <p>It is what gives the entity-tag a value, and it is deliberately not the
     * detector. JPA's own optimistic check compares the version loaded into the
     * persistence context with the one in the database at flush, and every write
     * path loads this entity under a pessimistic lock -- so a transaction that
     * waited re-reads the newest committed row, the two versions agree, and
     * nothing is ever raised. The comparison that detects a stale caller is
     * {@link com.aicompany.backend.api.Precondition}, made under that same lock.
     *
     * <p>{@code OPTIMISTIC_FORCE_INCREMENT} is never used against it: writing a
     * related row must not move this counter, or two operations the domain does
     * not consider to be in conflict would start refusing each other.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Agent() {
        // for JPA
    }

    public Agent(String name, String role, String specialization) {
        this.name = name;
        this.role = role;
        this.specialization = specialization;
        this.status = AgentStatus.ACTIVE;
    }

    /**
     * Replaces the descriptive fields. The lifecycle is not part of this: it goes
     * through the dedicated transitions.
     *
     * <p>Only an active agent can be edited, for the reason ADR-004 §8 gave for
     * archived projects: something taken out of the working registry that still
     * accepts edits is not out of anything, and it goes on holding its name in
     * the unique index while doing so. Restore it first.
     */
    public void updateDetails(String name, String role, String specialization) {

        if (status != AgentStatus.ACTIVE) {
            throw new InactiveAgentIsImmutableException();
        }

        this.name = name;
        this.role = role;
        this.specialization = specialization;
    }

    /** Takes the agent out of the working registry. This is what replaces a delete. */
    public void deactivate() {
        requireStatus(AgentStatus.ACTIVE);
        this.status = AgentStatus.INACTIVE;
    }

    /** Brings a deactivated agent back into the working registry. */
    public void activate() {
        requireStatus(AgentStatus.INACTIVE);
        this.status = AgentStatus.ACTIVE;
    }

    /** The lifecycle state, as the database holds it. */
    public AgentStatus getStatus() {
        return status;
    }

    /**
     * Whether this agent is in the working registry.
     *
     * <p>Derived from {@link #status} since {@code V8}. Kept because it is what
     * every rule elsewhere actually asks -- {@code Task.assignTo} wants to know
     * whether work can be given, not which of two names the column holds -- and
     * because removing it would have rippled a rename through call sites that
     * TD-31 does not concern. ADR-012 §4.
     */
    public boolean isActive() {
        return status == AgentStatus.ACTIVE;
    }

    /**
     * A repeated transition is refused rather than absorbed, for the reason
     * ADR-004 §4 gave: it is almost always a caller mistake -- a double click, a
     * blind retry, a stale local state -- and turning it into a silent no-op
     * hides the bug and makes the state machine unverifiable.
     */
    private void requireStatus(AgentStatus expected) {
        if (status != expected) {
            throw new IllegalAgentStateTransitionException(isActive());
        }
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    /** The current row version. See {@link #version}. */
    public long getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public String getSpecialization() {
        return specialization;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
