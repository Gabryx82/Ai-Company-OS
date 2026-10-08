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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A block of the Project Blueprint (ADR-033): one technical component of a
 * project. Columns mirror {@code V26}.
 *
 * <p>The three things this row keeps apart are the point of it: what the code
 * says ({@code detected}, a JSON snapshot refreshed by every scan), what the
 * operator said instead ({@code overrides}, the names of the fields a scan must
 * no longer touch), and the effective values the Blueprint shows. The JSON
 * columns are text here; the service reads and writes them.
 */
@Entity
@Table(name = "blueprint_nodes")
public class BlueprintNode {

    /** The fields of a detected block that an operator can override (ADR-033 §3). */
    public static final Set<String> OVERRIDABLE = Set.of("name", "kind", "description", "pseudocode");

    // PHASE 32 (TD-60): ids in blocks of 50 from the column's own sequence, so a scan's inserts are batched.
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "blueprint_nodes_id")
    @SequenceGenerator(name = "blueprint_nodes_id", sequenceName = "blueprint_nodes_seq", allocationSize = 50)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "node_key", nullable = false, length = 512, updatable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NodeKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private NodeOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NodeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "path_state", nullable = false, length = 24)
    private PathState pathState;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "file_path", length = 1000)
    private String filePath;

    @Column(length = 32)
    private String language;

    @Column(length = 4000)
    private String description;

    @Column(length = 8000)
    private String pseudocode;

    @Column(length = 4000)
    private String notes;

    /** JSON array of strings. */
    @Column(length = 1000)
    private String tags;

    /** JSON {@link CodeStructure}: effective. */
    @Column(columnDefinition = "text")
    private String structure;

    /** JSON snapshot of what the last scan read: name, kind, description, pseudocode. */
    @Column(columnDefinition = "text")
    private String detected;

    /** Comma-separated names from {@link #OVERRIDABLE}. */
    @Column(length = 255)
    private String overrides;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false)
    private boolean expanded;

    @Column(name = "last_scan_id")
    private Long lastScanId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BlueprintNode() {
    }

    public BlueprintNode(Long projectId, String key, NodeKind kind, NodeOrigin origin, NodeStatus status,
                         PathState pathState, String name) {
        this.projectId = projectId;
        this.key = key;
        this.kind = kind;
        this.origin = origin;
        this.status = status;
        this.pathState = pathState;
        this.name = name;
    }

    public boolean isDetected() {
        return origin == NodeOrigin.DETECTED;
    }

    public Set<String> overriddenFields() {
        if (overrides == null || overrides.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(overrides.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isOverridden(String field) {
        return overriddenFields().contains(field);
    }

    public void setOverriddenFields(Set<String> fields) {
        this.overrides = fields.isEmpty() ? null : fields.stream().sorted().collect(Collectors.joining(","));
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
    public String getKey() { return key; }
    public NodeKind getKind() { return kind; }
    public NodeOrigin getOrigin() { return origin; }
    public NodeStatus getStatus() { return status; }
    public PathState getPathState() { return pathState; }
    public String getName() { return name; }
    public String getFilePath() { return filePath; }
    public String getLanguage() { return language; }
    public String getDescription() { return description; }
    public String getPseudocode() { return pseudocode; }
    public String getNotes() { return notes; }
    public String getTags() { return tags; }
    public String getStructure() { return structure; }
    public String getDetected() { return detected; }
    public Integer getStartLine() { return startLine; }
    public Integer getEndLine() { return endLine; }
    public Long getParentId() { return parentId; }
    public boolean isExpanded() { return expanded; }
    public Long getLastScanId() { return lastScanId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void setKind(NodeKind kind) { this.kind = kind; }
    public void setStatus(NodeStatus status) { this.status = status; }
    public void setPathState(PathState pathState) { this.pathState = pathState; }
    public void setName(String name) { this.name = name; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public void setLanguage(String language) { this.language = language; }
    public void setDescription(String description) { this.description = description; }
    public void setPseudocode(String pseudocode) { this.pseudocode = pseudocode; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setTags(String tags) { this.tags = tags; }
    public void setStructure(String structure) { this.structure = structure; }
    public void setDetected(String detected) { this.detected = detected; }
    public void setLines(Integer startLine, Integer endLine) { this.startLine = startLine; this.endLine = endLine; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }
    public void setLastScanId(Long lastScanId) { this.lastScanId = lastScanId; }
}
