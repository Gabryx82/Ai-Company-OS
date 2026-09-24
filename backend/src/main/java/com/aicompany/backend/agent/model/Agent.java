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

    /**
     * The AI Engine model this agent runs on ({@code "ollama:llama3.2:3b"}), or
     * {@code null} for the engine's default. From {@code V11} (TASK-020). A
     * descriptive field like the others: part of {@link #updateDetails}, and like
     * them frozen while the agent is inactive.
     */
    @Column(length = 200)
    private String model;

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

    // --- prompt engineering and hierarchy (V18, ADR-023) --------------------------

    /** The agent this one specialises, or {@code null} for a top-level agent. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(length = 120)
    private String domain;

    @Column(name = "system_prompt", length = 8000)
    private String systemPrompt;

    @Column(length = 4000)
    private String responsibilities;

    @Column(length = 4000)
    private String limits;

    @Column(name = "output_format", length = 2000)
    private String outputFormat;

    /** One directive per line, in the order they apply. */
    @Column(length = 8000)
    private String directives;

    @Column(name = "context_policy", length = 2000)
    private String contextPolicy;

    // --- binding and origin (V21, ADR-025) ------------------------------------------

    @Column(length = 2000)
    private String description;

    /** One capability per line. */
    @Column(length = 2000)
    private String capabilities;

    /**
     * Where the agent works: {@code "engine"} or an execution target of
     * {@code catalog/execution-targets.json}. {@code null} (rows older than V21)
     * means the engine.
     */
    @Column(name = "execution_target", length = 64)
    private String executionTarget;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentOrigin origin = AgentOrigin.USER;

    /** The configuration the agent was born with (seed or template), as JSON; {@code null} for USER agents. */
    @Column(length = 16000)
    private String baseline;

    @Column(name = "customized_at")
    private Instant customizedAt;

    @Column(name = "customized_by", length = 64)
    private String customizedBy;

    protected Agent() {
        // for JPA
    }

    public Agent(String name, String role, String specialization) {
        this(name, role, specialization, null);
    }

    public Agent(String name, String role, String specialization, String model) {
        this.name = name;
        this.role = role;
        this.specialization = specialization;
        this.model = model;
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
    public void updateDetails(String name, String role, String specialization, String model) {

        if (status != AgentStatus.ACTIVE) {
            throw new InactiveAgentIsImmutableException();
        }

        this.name = name;
        this.role = role;
        this.specialization = specialization;
        this.model = model;
    }

    /**
     * The agent's prompt engineering and its place in the hierarchy (ADR-023).
     * Frozen while inactive, like the details.
     */
    public void configureProfile(Long parentId, String domain, String systemPrompt, String responsibilities,
                                 String limits, String outputFormat, String directives, String contextPolicy) {
        if (status != AgentStatus.ACTIVE) {
            throw new InactiveAgentIsImmutableException();
        }
        this.parentId = parentId;
        this.domain = blankToNull(domain);
        this.systemPrompt = blankToNull(systemPrompt);
        this.responsibilities = blankToNull(responsibilities);
        this.limits = blankToNull(limits);
        this.outputFormat = blankToNull(outputFormat);
        this.directives = blankToNull(directives);
        this.contextPolicy = blankToNull(contextPolicy);
    }

    /**
     * Description, capabilities and binding (ADR-025). Frozen while inactive,
     * like the rest of the configuration. The model is validated against the
     * target by the service, which knows the catalogs.
     */
    public void configureBinding(String description, String capabilities, String model, String executionTarget) {
        if (status != AgentStatus.ACTIVE) {
            throw new InactiveAgentIsImmutableException();
        }
        this.description = blankToNull(description);
        this.capabilities = blankToNull(capabilities);
        this.model = blankToNull(model);
        this.executionTarget = blankToNull(executionTarget);
    }

    /** Records who last changed a SEED or TEMPLATE agent, and when. */
    public void markCustomized(String by) {
        this.customizedAt = Instant.now();
        this.customizedBy = by;
    }

    /** Where this agent came from, and the configuration it came with. */
    public void recordOrigin(AgentOrigin origin, String baseline) {
        this.origin = origin;
        this.baseline = baseline;
    }

    public String getDescription() { return description; }
    public String getCapabilities() { return capabilities; }
    /** The execution target key; {@code "engine"} when none was ever set. */
    public String getExecutionTarget() { return executionTarget == null ? "engine" : executionTarget; }
    public AgentOrigin getOrigin() { return origin; }
    public String getBaseline() { return baseline; }
    public Instant getCustomizedAt() { return customizedAt; }
    public String getCustomizedBy() { return customizedBy; }

    /** Whether the operator gave this agent any prompt engineering of its own. */
    public boolean hasProfile() {
        return systemPrompt != null || responsibilities != null || limits != null || outputFormat != null
                || directives != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public Long getParentId() { return parentId; }
    public String getDomain() { return domain; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getResponsibilities() { return responsibilities; }
    public String getLimits() { return limits; }
    public String getOutputFormat() { return outputFormat; }
    public String getDirectives() { return directives; }
    public String getContextPolicy() { return contextPolicy; }

    /** The engine model id, or {@code null} for the engine's default. */
    public String getModel() {
        return model;
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
