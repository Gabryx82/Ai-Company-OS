package com.aicompany.backend.agent.model;

import com.aicompany.backend.agent.exception.IllegalAgentStateTransitionException;
import com.aicompany.backend.agent.exception.InactiveAgentIsImmutableException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * <p><strong>Why the lifecycle is a boolean here and an enum on Project.</strong>
 * Not because agents deserve less. Unifying them means backfilling {@code active}
 * into a status column and then dropping {@code active} -- lossless, but an
 * irreversible migration, and the autonomous charter puts those behind a human
 * decision whenever a reasonable alternative exists. One does: every rule below
 * works identically on a boolean. The divergence is recorded as TD-31 and hidden
 * from callers rather than from the record -- the response exposes a derived
 * status, so a client sees one vocabulary while the database keeps one state.
 * ADR-008 §2.
 *
 * <p>Columns mirror {@code V1__create_agents_and_tasks.sql} plus
 * {@code V4__add_agent_registry_columns.sql} exactly: Hibernate runs in validate
 * mode, so a divergence fails startup instead of altering the schema.
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

    @Column(nullable = false)
    private boolean active;

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
        this.active = true;
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

        if (!active) {
            throw new InactiveAgentIsImmutableException();
        }

        this.name = name;
        this.role = role;
        this.specialization = specialization;
    }

    /** Takes the agent out of the working registry. This is what replaces a delete. */
    public void deactivate() {
        requireActive(true);
        this.active = false;
    }

    /** Brings a deactivated agent back into the working registry. */
    public void activate() {
        requireActive(false);
        this.active = true;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * A repeated transition is refused rather than absorbed, for the reason
     * ADR-004 §4 gave: it is almost always a caller mistake -- a double click, a
     * blind retry, a stale local state -- and turning it into a silent no-op
     * hides the bug and makes the state machine unverifiable.
     */
    private void requireActive(boolean expected) {
        if (active != expected) {
            throw new IllegalAgentStateTransitionException(active);
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
