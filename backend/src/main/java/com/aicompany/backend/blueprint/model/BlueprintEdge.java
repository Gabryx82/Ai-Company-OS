package com.aicompany.backend.blueprint.model;

import com.aicompany.backend.persistence.DatabaseClock;
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
 * A directed relation between two blocks of the Blueprint (ADR-033): source
 * {@code relation} target, e.g. {@code TaskController USES TaskService}. A
 * detected edge carries the place in the code that produced it. Columns mirror
 * {@code V26}.
 */
@Entity
@Table(name = "blueprint_edges")
public class BlueprintEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "source_node_id", nullable = false, updatable = false)
    private Long sourceNodeId;

    @Column(name = "target_node_id", nullable = false, updatable = false)
    private Long targetNodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RelationType relation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EdgeOrigin origin;

    @Column(length = 255)
    private String label;

    @Column(length = 2000)
    private String description;

    @Column(name = "code_path", length = 1000)
    private String codePath;

    @Column(name = "code_line")
    private Integer codeLine;

    @Column(name = "code_excerpt", length = 500)
    private String codeExcerpt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BlueprintEdge() {
    }

    public BlueprintEdge(Long projectId, Long sourceNodeId, Long targetNodeId, RelationType relation, EdgeOrigin origin) {
        this.projectId = projectId;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.relation = relation;
        this.origin = origin;
    }

    @PrePersist
    void onCreate() {
        Instant now = DatabaseClock.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = DatabaseClock.now();
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getSourceNodeId() { return sourceNodeId; }
    public Long getTargetNodeId() { return targetNodeId; }
    public RelationType getRelation() { return relation; }
    public EdgeOrigin getOrigin() { return origin; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public String getCodePath() { return codePath; }
    public Integer getCodeLine() { return codeLine; }
    public String getCodeExcerpt() { return codeExcerpt; }
    public long getVersion() { return version; }

    public void setRelation(RelationType relation) { this.relation = relation; }
    public void setOrigin(EdgeOrigin origin) { this.origin = origin; }
    public void setLabel(String label) { this.label = label; }
    public void setDescription(String description) { this.description = description; }

    public void setCodeReference(String path, Integer line, String excerpt) {
        this.codePath = path;
        this.codeLine = line;
        this.codeExcerpt = excerpt == null ? null : excerpt.length() > 500 ? excerpt.substring(0, 500) : excerpt;
    }
}
