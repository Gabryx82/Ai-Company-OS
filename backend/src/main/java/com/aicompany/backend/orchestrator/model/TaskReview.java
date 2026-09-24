package com.aicompany.backend.orchestrator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The operator's verdict on an outcome (ADR-021 §5, ADR-022 §2). Append-only:
 * the history of human decisions is not rewritten. Columns mirror {@code V16}.
 */
@Entity
@Table(name = "task_reviews")
public class TaskReview {

    public enum Verdict { ACCEPTED, CHANGES_REQUESTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private Verdict verdict;

    @Column(length = 4000, updatable = false)
    private String note;

    @Column(name = "run_id", updatable = false)
    private Long runId;

    @Column(name = "handoff_id", updatable = false)
    private Long handoffId;

    @Column(nullable = false, length = 120, updatable = false)
    private String reviewer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TaskReview() {
    }

    public TaskReview(Long taskId, Verdict verdict, String note, Long runId, Long handoffId, String reviewer) {
        this.taskId = taskId;
        this.verdict = verdict;
        this.note = note;
        this.runId = runId;
        this.handoffId = handoffId;
        this.reviewer = reviewer;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public Verdict getVerdict() { return verdict; }
    public String getNote() { return note; }
    public Long getRunId() { return runId; }
    public Long getHandoffId() { return handoffId; }
    public String getReviewer() { return reviewer; }
    public Instant getCreatedAt() { return createdAt; }
}
