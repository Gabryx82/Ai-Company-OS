package com.aicompany.backend.blueprint.model;

import com.aicompany.backend.persistence.DatabaseClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Where the blocks of a project's Blueprint sit on the canvas: one row per
 * project, apart from the nodes on purpose (ADR-033 §6) -- moving a block is not
 * an edit of the component. {@code positions} is JSON: node id to {x, y}.
 */
@Entity
@Table(name = "blueprint_layouts")
public class BlueprintLayout {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, columnDefinition = "text")
    private String positions;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BlueprintLayout() {
    }

    public BlueprintLayout(Long projectId, String positions) {
        this.projectId = projectId;
        this.positions = positions;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = DatabaseClock.now();
    }

    public Long getProjectId() { return projectId; }
    public String getPositions() { return positions; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void setPositions(String positions) { this.positions = positions; }
}
