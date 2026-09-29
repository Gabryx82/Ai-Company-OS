package com.aicompany.backend.blueprint.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One reading of the code into the Blueprint (ADR-033 §4): what it found and what it changed. Append-only. */
@Entity
@Table(name = "blueprint_scans")
public class BlueprintScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(nullable = false)
    private int files;

    @Column(nullable = false)
    private boolean truncated;

    @Column(name = "nodes_detected", nullable = false)
    private int nodesDetected;

    @Column(name = "edges_detected", nullable = false)
    private int edgesDetected;

    @Column(name = "nodes_added", nullable = false)
    private int nodesAdded;

    @Column(name = "nodes_missing", nullable = false)
    private int nodesMissing;

    @Column(nullable = false)
    private int unresolved;

    /** For example "java 12, typescript 30". */
    @Column(nullable = false, length = 500)
    private String languages;

    protected BlueprintScan() {
    }

    public BlueprintScan(Long projectId, Instant startedAt, String requestedBy) {
        this.projectId = projectId;
        this.startedAt = startedAt;
        this.finishedAt = startedAt;
        this.requestedBy = requestedBy;
        this.languages = "";
    }

    public void finish(int files, boolean truncated, int nodesDetected, int edgesDetected, int nodesAdded,
                       int nodesMissing, int unresolved, String languages) {
        this.finishedAt = Instant.now();
        this.files = files;
        this.truncated = truncated;
        this.nodesDetected = nodesDetected;
        this.edgesDetected = edgesDetected;
        this.nodesAdded = nodesAdded;
        this.nodesMissing = nodesMissing;
        this.unresolved = unresolved;
        this.languages = languages.length() > 500 ? languages.substring(0, 500) : languages;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getRequestedBy() { return requestedBy; }
    public int getFiles() { return files; }
    public boolean isTruncated() { return truncated; }
    public int getNodesDetected() { return nodesDetected; }
    public int getEdgesDetected() { return edgesDetected; }
    public int getNodesAdded() { return nodesAdded; }
    public int getNodesMissing() { return nodesMissing; }
    public int getUnresolved() { return unresolved; }
    public String getLanguages() { return languages; }
}
