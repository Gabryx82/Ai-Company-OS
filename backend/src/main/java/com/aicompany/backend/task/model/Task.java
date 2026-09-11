package com.aicompany.backend.task.model;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 5000)
    private String description;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String priority;

    /**
     * The project this task belongs to, or {@code null} for a task that has not
     * been assigned to one.
     *
     * <p>Nullable on purpose and only for this phase: tasks created before the
     * relation existed have no correct project to point at, and the column keeps
     * that fact truthfully instead of hiding it behind a placeholder row. See
     * ADR-005 §1.
     *
     * <p>Unidirectional. {@code Project} has no collection of tasks, because a
     * mapped collection is the thing that makes cascading look like a
     * configuration flag rather than the domain decision it is -- and what
     * archiving a project does to its tasks is explicitly not decided yet
     * (ADR-005 §4).
     *
     * <p>Lazy, with {@code spring.jpa.open-in-view=false}: nothing outside a
     * transaction may touch this reference, which is why the service maps tasks
     * to their response shape while the session is still open.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    public Task() {
    }

    public Task(String title,
                String description,
                String status,
                String priority) {

        this.title = title;
        this.description = description;
        this.status = status;
        this.priority = priority;
    }

    /**
     * Puts this task in a project, or moves it to a different one.
     *
     * <p>An archived project cannot receive tasks. Archiving means "out of the
     * working registry" (ADR-004 §3), and a container that is out of the registry
     * but still accepting new work is not out of anything. The rule lives here
     * rather than in the service for the same reason the project transitions do:
     * a later entry point -- an importer, a planner, an agent -- cannot forget a
     * rule it has no way to bypass, and {@code project} has no setter.
     *
     * <p>What this deliberately does not do is react to a project being archived
     * <em>after</em> the assignment. Tasks already attached to a project are left
     * exactly as they are, see ADR-005 §4.
     */
    public void assignTo(Project project) {

        if (project.isArchived()) {
            throw new ArchivedProjectCannotReceiveTasksException(project.getId());
        }

        this.project = project;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public String getPriority() {
        return priority;
    }

    /**
     * The associated project, or {@code null}. Lazily loaded: call it inside a
     * transaction.
     */
    public Project getProject() {
        return project;
    }

    /**
     * Identifier of the associated project, or {@code null}. Initialises the lazy
     * reference, so it carries the same rule as {@link #getProject()}.
     */
    public Long getProjectId() {
        return project == null ? null : project.getId();
    }
}
