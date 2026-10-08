package com.aicompany.backend.task.model;

import com.aicompany.backend.persistence.DatabaseClock;
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
 * One edge of the lifecycle taken by a task (PHASE 32, TD-38): who moved it,
 * when, from which state to which, and through what. Append-only: the history
 * is not rewritten. Columns mirror {@code V31}.
 */
@Entity
@Table(name = "task_transitions")
public class TaskTransitionRecord {

    /** The path a transition came through. */
    public enum Via {
        /** The lifecycle routes: start, complete, stop, reopen. */
        API,
        /** Launching a run on an open task starts it (ADR-016 §2). */
        RUN,
        /** Handing an open task to an external tool starts it (ADR-021 §4). */
        HANDOFF,
        /** The operator's verdict completes or reopens it (ADR-021 §5). */
        REVIEW
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TaskTransition transition;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 32, updatable = false)
    private TaskStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32, updatable = false)
    private TaskStatus toStatus;

    @Column(nullable = false, length = 120, updatable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Via via;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = DatabaseClock.now();

    protected TaskTransitionRecord() {
    }

    public TaskTransitionRecord(Long taskId, TaskTransition transition, String actor, Via via) {
        this.taskId = taskId;
        this.transition = transition;
        this.fromStatus = transition.from();
        this.toStatus = transition.to();
        this.actor = actor;
        this.via = via;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public TaskTransition getTransition() { return transition; }
    public TaskStatus getFromStatus() { return fromStatus; }
    public TaskStatus getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public Via getVia() { return via; }
    public Instant getOccurredAt() { return occurredAt; }
}
