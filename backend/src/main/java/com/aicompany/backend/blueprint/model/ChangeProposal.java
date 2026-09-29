package com.aicompany.backend.blueprint.model;

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
 * A Proposed Code Change (ADR-033 §7): what the Blueprint would change in a real
 * file, as a diff a person reads. Accepting it creates a task in the project --
 * the existing governance workflow changes the code, never this row.
 */
@Entity
@Table(name = "blueprint_change_proposals")
public class ChangeProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "edge_id")
    private Long edgeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private ProposalKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProposalStatus status = ProposalStatus.PROPOSED;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 2000)
    private String reason;

    @Column(length = 2000)
    private String consequences;

    @Column(nullable = false, columnDefinition = "text")
    private String diff;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "proposed_by", nullable = false, updatable = false)
    private String proposedBy;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ChangeProposal() {
    }

    public ChangeProposal(Long projectId, Long nodeId, Long edgeId, ProposalKind kind, String filePath, String title,
                          String reason, String consequences, String diff, String proposedBy) {
        this.projectId = projectId;
        this.nodeId = nodeId;
        this.edgeId = edgeId;
        this.kind = kind;
        this.filePath = filePath;
        this.title = title;
        this.reason = reason;
        this.consequences = consequences;
        this.diff = diff;
        this.proposedBy = proposedBy;
    }

    public void decide(ProposalStatus outcome, String by, Long taskId) {
        this.status = outcome;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.taskId = taskId;
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

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getNodeId() { return nodeId; }
    public Long getEdgeId() { return edgeId; }
    public ProposalKind getKind() { return kind; }
    public ProposalStatus getStatus() { return status; }
    public String getFilePath() { return filePath; }
    public String getTitle() { return title; }
    public String getReason() { return reason; }
    public String getConsequences() { return consequences; }
    public String getDiff() { return diff; }
    public Long getTaskId() { return taskId; }
    public String getProposedBy() { return proposedBy; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
