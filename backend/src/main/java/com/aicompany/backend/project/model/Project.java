package com.aicompany.backend.project.model;

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
     */
    public void updateDetails(String name, String description) {
        this.name = name;
        this.description = description;
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
