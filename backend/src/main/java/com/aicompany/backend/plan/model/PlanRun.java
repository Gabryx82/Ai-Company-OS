package com.aicompany.backend.plan.model;

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
 * One planning attempt: a generation through the AI Engine or an import of a
 * {@code plan.json} an external agent wrote (ADR-021). Columns mirror {@code V15}.
 * Every attempt ends with a type -- the rule ADR-016 set for task runs.
 */
@Entity
@Table(name = "plan_runs")
public class PlanRun {

    public enum Source { ENGINE, IMPORT }

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RUNNING;

    @Column(name = "requested_model", length = 200)
    private String requestedModel;

    @Column(name = "served_model", length = 200)
    private String servedModel;

    @Column(columnDefinition = "TEXT")
    private String output;

    @Column(name = "failure_detail", length = 4000)
    private String failureDetail;

    private Integer phases;

    private Integer tasks;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    /** Which stage a generation is in ("Fase 2/4: task"), for the operator watching it. */
    @Column(length = 200)
    private String progress;

    @Column(name = "requested_by", nullable = false, length = 120)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected PlanRun() {
    }

    public PlanRun(Long projectId, Source source, String requestedModel, String requestedBy) {
        this.projectId = projectId;
        this.source = source;
        this.requestedModel = requestedModel;
        this.requestedBy = requestedBy;
    }

    public void progress(String progress) {
        this.progress = progress == null || progress.length() <= 200 ? progress : progress.substring(0, 200);
    }

    public void succeed(int phases, int tasks, String output, String servedModel, Integer inputTokens,
                        Integer outputTokens) {
        this.status = Status.SUCCEEDED;
        this.phases = phases;
        this.tasks = tasks;
        this.output = output;
        this.servedModel = servedModel;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.finishedAt = Instant.now();
    }

    public void fail(String detail, String output, String servedModel) {
        this.status = Status.FAILED;
        this.failureDetail = detail.length() > 4000 ? detail.substring(0, 4000) : detail;
        this.output = output;
        this.servedModel = servedModel;
        this.finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Source getSource() { return source; }
    public Status getStatus() { return status; }
    public String getRequestedModel() { return requestedModel; }
    public String getServedModel() { return servedModel; }
    public String getOutput() { return output; }
    public String getFailureDetail() { return failureDetail; }
    public Integer getPhases() { return phases; }
    public Integer getTasks() { return tasks; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public String getRequestedBy() { return requestedBy; }
    public String getProgress() { return progress; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
