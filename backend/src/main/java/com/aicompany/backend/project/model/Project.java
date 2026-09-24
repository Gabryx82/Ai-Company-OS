package com.aicompany.backend.project.model;

import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.project.exception.IllegalProjectStateTransitionException;
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
 * A project: the logical container the rest of the Company OS will hang from.
 *
 * <p>This class owns its lifecycle rather than exposing a settable status. A
 * caller asks for a transition ({@link #archive()}, {@link #restore()}) and the
 * entity decides whether that transition is legal, so an invalid state can never
 * be reached through the service or the controller.
 *
 * <p>Columns mirror {@code V2__create_projects.sql} exactly: Hibernate runs in
 * validate mode, so a divergence fails the startup instead of altering the
 * schema.
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 2000)
    private String description;

    // Stored as text, never as an ordinal: reordering the enum must not silently
    // reinterpret existing rows, and the database check constraint reads names.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProjectStatus status;

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

    // --- the profile (V14, ADR-020 §2.1) --------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "project_type", length = 24)
    private ProjectType projectType;

    @Column(length = 2000)
    private String stack;

    @Column(name = "workspace_path", length = 1000)
    private String workspacePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_level", nullable = false, length = 24)
    private AutonomyLevel autonomyLevel = AutonomyLevel.GUIDED;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_status", nullable = false, length = 24)
    private PlanStatus planStatus = PlanStatus.NONE;

    protected Project() {
        // for JPA
    }

    public Project(String name, String description) {
        this.name = name;
        this.description = description;
        this.status = ProjectStatus.ACTIVE;
    }

    /**
     * Replaces the descriptive fields. Status is not part of this: lifecycle
     * changes go through the dedicated transitions.
     *
     * <p>Only an active project can be edited. An archived one is out of the
     * working registry and stays as it was until somebody restores it: see
     * ADR-004 §8. The rule lives here, next to the transitions, so no entry
     * point can forget it -- the same reason {@code status} has no setter.
     */
    public void updateDetails(String name, String description) {

        if (isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }

        this.name = name;
        this.description = description;
    }

    /**
     * Sets the profile (ADR-020). Frozen while archived, like the details
     * (ADR-004 §8): an archived project is out of the working registry.
     */
    public void configureProfile(ProjectType projectType, String stack, String workspacePath,
                                 AutonomyLevel autonomyLevel) {
        if (isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }
        this.projectType = projectType;
        this.stack = stack;
        this.workspacePath = workspacePath;
        this.autonomyLevel = autonomyLevel == null ? AutonomyLevel.GUIDED : autonomyLevel;
    }

    /** Where the plan stands (PHASE 10). Frozen while archived. */
    public void markPlan(PlanStatus planStatus) {
        if (isArchived()) {
            throw new ArchivedProjectIsImmutableException();
        }
        this.planStatus = planStatus;
    }

    public ProjectType getProjectType() {
        return projectType;
    }

    public String getStack() {
        return stack;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public PlanStatus getPlanStatus() {
        return planStatus;
    }

    /**
     * Takes the project out of the working registry. This is what replaces a
     * physical delete: the row, and everything that will later reference it,
     * stays.
     */
    public void archive() {
        requireStatus(ProjectStatus.ACTIVE, ProjectStatus.ARCHIVED);
        this.status = ProjectStatus.ARCHIVED;
    }

    /** Brings an archived project back into the working registry. */
    public void restore() {
        requireStatus(ProjectStatus.ARCHIVED, ProjectStatus.ACTIVE);
        this.status = ProjectStatus.ACTIVE;
    }

    public boolean isArchived() {
        return status == ProjectStatus.ARCHIVED;
    }

    private void requireStatus(ProjectStatus expected, ProjectStatus target) {
        if (status != expected) {
            throw new IllegalProjectStateTransitionException(status, target);
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

    public String getDescription() {
        return description;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
