package com.aicompany.backend.orchestrator.model;

import com.aicompany.backend.persistence.DatabaseClock;
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
    private Instant createdAt = DatabaseClock.now();

    /** PHASE 32 (TD-49): the port of the local API of the OpenCode this handoff opened. */
    @Column(name = "follow_port", updatable = false)
    private Integer followPort;

    /** PHASE 35 (ADR-041): the characters of the package this handoff wrote; null before the measures existed. */
    @Column(name = "package_chars", updatable = false)
    private Integer packageChars;

    /** PHASE 37 (ADR-044): how the handoff asked the agent to work, as far as learning goes; null before V45. */
    @Column(name = "learning_mode", length = 16, updatable = false)
    private String learningMode;

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

    public Integer getFollowPort() {
        return followPort;
    }

    public Integer getPackageChars() {
        return packageChars;
    }

    public String getLearningMode() {
        return learningMode;
    }

    /** Before it is saved: the lesson the handoff asked for, if any. */
    public TaskHandoff teaching(com.aicompany.backend.hitl.LearningMode mode) {
        this.learningMode = mode.name();
        return this;
    }

    /** Before it is saved: the package this handoff wrote has this many characters. */
    public TaskHandoff measured(int packageChars) {
        this.packageChars = packageChars;
        return this;
    }

    /** Before it is saved: the handoff opened OpenCode with its API on this port. */
    public TaskHandoff followedOn(Integer port) {
        this.followPort = port;
        return this;
    }
}
