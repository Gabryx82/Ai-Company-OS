package com.aicompany.backend.run.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * One execution of a task by an agent, through the AI Engine (ADR-016).
 *
 * <p>The task and the agent are held as identifiers, not as associations: a run
 * is a record of what happened, never navigated to change anything, and the
 * agent is the one who ran it -- not whoever holds the task today.
 *
 * <p>The transitions are methods, like every lifecycle in this codebase, and each
 * checks where it starts from. The database checks the same thing a second way
 * ({@code task_runs_outcome_check}): the outcome columns must agree with the
 * status.
 */
@Entity
@Table(name = "task_runs")
public class TaskRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private Long agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunStatus status;

    @Column(name = "requested_model", length = 200, updatable = false)
    private String requestedModel;

    @Column(name = "served_model", length = 200)
    private String servedModel;

    @Column(name = "system_prompt", nullable = false, updatable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "user_prompt", nullable = false, updatable = false, columnDefinition = "text")
    private String userPrompt;

    @Column(columnDefinition = "text")
    private String output;

    @Column(name = "finish_reason", length = 16)
    private String finishReason;

    @Column(name = "failure_type", length = 200)
    private String failureType;

    @Column(name = "failure_detail", length = 2000)
    private String failureDetail;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "correlation_id", nullable = false, length = 64, updatable = false)
    private String correlationId;

    @Column(name = "requested_by", nullable = false, length = 120, updatable = false)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected TaskRun() {
        // for JPA
    }

    public TaskRun(Long taskId, Long agentId, String requestedModel, String systemPrompt, String userPrompt,
                   String correlationId, String requestedBy) {
        this.taskId = taskId;
        this.agentId = agentId;
        this.requestedModel = requestedModel;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.correlationId = correlationId;
        this.requestedBy = requestedBy;
        this.status = RunStatus.QUEUED;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** {@code QUEUED → RUNNING}. Returns false, and changes nothing, from any other state. */
    public boolean start() {
        if (status != RunStatus.QUEUED) {
            return false;
        }
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
        return true;
    }

    /** {@code RUNNING → SUCCEEDED}. */
    public boolean succeed(String output, String finishReason, String servedModel,
                           int inputTokens, int outputTokens, long latencyMs) {
        if (status != RunStatus.RUNNING) {
            return false;
        }
        this.status = RunStatus.SUCCEEDED;
        this.output = output;
        this.finishReason = finishReason;
        this.servedModel = servedModel;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.finishedAt = Instant.now();
        return true;
    }

    /** {@code QUEUED | RUNNING → FAILED}. A finished run is never rewritten. */
    public boolean fail(String failureType, String failureDetail) {
        if (status.isFinished()) {
            return false;
        }
        this.status = RunStatus.FAILED;
        this.failureType = failureType;
        this.failureDetail = failureDetail == null ? null
                : failureDetail.length() > 2000 ? failureDetail.substring(0, 2000) : failureDetail;
        this.finishedAt = Instant.now();
        return true;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public Long getAgentId() { return agentId; }
    public RunStatus getStatus() { return status; }
    public String getRequestedModel() { return requestedModel; }
    public String getServedModel() { return servedModel; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public String getOutput() { return output; }
    public String getFinishReason() { return finishReason; }
    public String getFailureType() { return failureType; }
    public String getFailureDetail() { return failureDetail; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Long getLatencyMs() { return latencyMs; }
    public String getCorrelationId() { return correlationId; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getVersion() { return version; }
}
