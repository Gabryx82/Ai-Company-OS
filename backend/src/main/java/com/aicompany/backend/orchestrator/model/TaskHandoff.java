package com.aicompany.backend.orchestrator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A task handed to an external agent (ADR-021 §4): which tool, which file, which
 * prompt, which command line. Append-only. Columns mirror {@code V16}.
 */
@Entity
@Table(name = "task_handoffs")
public class TaskHandoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(nullable = false, length = 64, updatable = false)
    private String target;

    @Column(name = "document_path", nullable = false, length = 500, updatable = false)
    private String documentPath;

    @Column(nullable = false, length = 2000, updatable = false)
    private String prompt;

    @Column(length = 4000, updatable = false)
    private String command;

    @Column(name = "requested_by", nullable = false, length = 120, updatable = false)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TaskHandoff() {
    }

    public TaskHandoff(Long taskId, String target, String documentPath, String prompt, String command,
                       String requestedBy) {
        this.taskId = taskId;
        this.target = target;
        this.documentPath = documentPath;
        this.prompt = prompt;
        this.command = command == null || command.length() <= 4000 ? command : command.substring(0, 4000);
        this.requestedBy = requestedBy;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public String getTarget() { return target; }
    public String getDocumentPath() { return documentPath; }
    public String getPrompt() { return prompt; }
    public String getCommand() { return command; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
