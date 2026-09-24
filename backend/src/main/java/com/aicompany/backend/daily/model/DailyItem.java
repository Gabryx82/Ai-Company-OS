package com.aicompany.backend.daily.model;

import com.aicompany.backend.task.model.Task;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One line of the operator's day (directive §18): a personal item, or a
 * reference to a project task. Columns mirror {@code V19}.
 */
@Entity
@Table(name = "daily_items")
public class DailyItem {

    public enum Status { TODO, DOING, DONE }

    public enum Priority { LOW, MEDIUM, HIGH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate day;

    @Column(length = 255)
    private String title;

    @Column(length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.TODO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Priority priority = Priority.MEDIUM;

    @Column(name = "due_time")
    private LocalTime dueTime;

    /** A reference, never a copy: title and status of the task are read from the task. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected DailyItem() {
    }

    public DailyItem(LocalDate day, String title, String notes, Priority priority, LocalTime dueTime, Task task,
                     int position) {
        this.day = day;
        this.title = blankToNull(title);
        this.notes = blankToNull(notes);
        this.priority = priority == null ? Priority.MEDIUM : priority;
        this.dueTime = dueTime;
        this.task = task;
        this.position = position;
    }

    public void update(LocalDate day, String title, String notes, Status status, Priority priority, LocalTime dueTime,
                       int position) {
        this.day = day;
        this.title = task == null ? blankToNull(title) : blankToNull(title);
        this.notes = blankToNull(notes);
        this.status = status;
        this.priority = priority;
        this.dueTime = dueTime;
        this.position = position;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
    public LocalDate getDay() { return day; }
    public String getTitle() { return title; }
    public String getNotes() { return notes; }
    public Status getStatus() { return status; }
    public Priority getPriority() { return priority; }
    public LocalTime getDueTime() { return dueTime; }
    public Task getTask() { return task; }
    public int getPosition() { return position; }
    public long getVersion() { return version; }
}
