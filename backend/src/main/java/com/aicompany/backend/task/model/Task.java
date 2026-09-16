package com.aicompany.backend.task.model;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import com.aicompany.backend.task.exception.ArchivedProjectTaskIsImmutableException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.Objects;

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

    /**
     * The row version of ADR-009: a counter the persistence layer increments on
     * every UPDATE of <em>this</em> row, and nothing else touches (rule P3).
     *
     * <p>It is what gives the entity-tag a value, and it is deliberately not the
     * detector. JPA's own optimistic check compares the version loaded into the
     * persistence context with the one in the database at flush, and every write
     * path loads this entity under a pessimistic lock -- so a transaction that
     * waited re-reads the newest committed row, the two versions agree, and
     * nothing is ever raised. The comparison that detects a stale caller is
     * {@link com.aicompany.backend.api.Precondition}, made under that same lock.
     *
     * <p>{@code OPTIMISTIC_FORCE_INCREMENT} is never used against it: writing a
     * related row must not move this counter, or two operations the domain does
     * not consider to be in conflict would start refusing each other.
     */
    @Version
    @Column(nullable = false)
    private long version;

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
     * <p>Three rules, in this order, and the order is the decision:
     *
     * <ol>
     *   <li><strong>Asking for the project the task is already in changes
     *       nothing</strong>, so there is nothing to refuse -- even when that
     *       project is archived. Freezing is about mutations, and ADR-005 §5
     *       already settled that a PUT declaring a state that is already true is
     *       right. This branch is what makes the idempotent call a 200 and the
     *       move a 409 (ADR-006 §2).</li>
     *   <li><strong>A task inside an archived project does not move.</strong> A
     *       container out of the working registry that still lets its contents
     *       leave is not out of anything (ADR-006 §2). Reversible by the caller:
     *       restore that project and the same request passes.</li>
     *   <li><strong>An archived project receives no new work</strong> -- the rule
     *       ADR-005 §3 introduced, unchanged.</li>
     * </ol>
     *
     * <p>Source before destination, so that when both projects are archived the
     * refusal is deterministic and names the one the caller is moving out of.
     *
     * <p>Identity is compared by id rather than by reference: the two instances
     * can come from different lookups inside one transaction. An unsaved target
     * has no id and is never "the one it is already in" -- without that guard two
     * null ids would compare equal and quietly skip both refusals below.
     *
     * <p>The rules live here rather than in the service for the same reason the
     * project transitions do: a later entry point -- an importer, a planner, an
     * agent -- cannot forget a rule it has no way to bypass, and {@code project}
     * has no setter.
     *
     * <p>What this cannot do on its own is guarantee that {@code this.project} is
     * still true. That is the caller's job, and it is rule L0 of ADR-006 §4: the
     * task row is locked before its association is read. Without that lock this
     * method happily evaluates every rule above against a project the task left
     * some time ago.
     */
    public void assignTo(Project target) {

        if (project != null && target.getId() != null
                && Objects.equals(project.getId(), target.getId())) {
            return;
        }

        if (project != null && project.isArchived()) {
            throw new ArchivedProjectTaskIsImmutableException(project.getId());
        }

        if (target.isArchived()) {
            throw new ArchivedProjectCannotReceiveTasksException(target.getId());
        }

        this.project = target;
    }

    public Long getId() {
        return id;
    }

    /** The current row version. See {@link #version}. */
    public long getVersion() {
        return version;
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
