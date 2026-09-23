package com.aicompany.backend.task.model;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import com.aicompany.backend.task.exception.ArchivedProjectTaskIsImmutableException;
import com.aicompany.backend.task.exception.IllegalTaskStateTransitionException;
import com.aicompany.backend.task.exception.InactiveAgentCannotReceiveTasksException;
import com.aicompany.backend.task.exception.UnassignedTaskCannotStartException;
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

    /**
     * The lifecycle value of this task, from the closed vocabulary of
     * {@link TaskStatus}.
     *
     * <p>Typed rather than free text since TASK-010. It used to be a
     * {@code String} the database did not constrain, which meant
     * {@code "banana"} was a legal status and nothing in the system could rely
     * on the column meaning anything (ADR-011 §1).
     *
     * <p><strong>{@code STRING} and never {@code ORDINAL}.</strong> The
     * database check constraint compares against the names, so the column has to
     * hold them; and an ordinal would make reordering the members below rewrite
     * the meaning of every existing row without touching one.
     *
     * <p>No setter. Since TASK-015 the value moves only through {@link #apply},
     * along the edges {@link TaskTransition} declares (ADR-014) -- the vocabulary
     * of ADR-011 stays where it was, and the machine is a separate table. Until
     * then there was no path that changed it at all (TD-37).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    /**
     * From the closed vocabulary of {@link TaskPriority} since TASK-016 (TD-36),
     * guarded three times like {@link #status}: the request constraint, this
     * mapping, and {@code tasks_priority_check} from {@code V9}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskPriority priority;

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
     * The agent responsible for this task, or {@code null} for one nobody has
     * been given yet.
     *
     * <p>Nullable for the reason {@code project} is: every task that existed
     * before the relation has no agent, and there is no correct agent to point it
     * at (ADR-010 §5).
     *
     * <p>Unidirectional, like the project side and for the same reason -- a
     * mapped collection on {@code Agent} would turn what deactivation does to a
     * task into a cascade flag, and ADR-010 D3 is a domain decision, not a
     * configuration one.
     *
     * <p><strong>An inactive agent here is a legal state, and it has to be.</strong>
     * A task whose agent is switched off is not frozen: it stays writable so that
     * it can be given to somebody else, which is the whole recovery path the
     * relation exists for (ADR-010 D3). The alternative -- refusing to deactivate
     * an agent that still holds work -- would also have made the lock graph
     * cyclic, so the domain answer and the concurrency answer agree.
     *
     * <p>Lazy, with {@code spring.jpa.open-in-view=false}: nothing outside a
     * transaction may touch this reference.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Agent agent;

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
                TaskStatus status,
                TaskPriority priority) {

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

        requireNotFrozen(project);

        if (target.isArchived()) {
            throw new ArchivedProjectCannotReceiveTasksException(target.getId());
        }

        this.project = target;
    }

    /**
     * Puts this task in the hands of an agent, or moves it to a different one.
     *
     * <p>Three rules, in this order, and the order is the decision -- the same
     * shape {@link #assignTo(Project)} has, because the questions are the same
     * ones asked of a different relation:
     *
     * <ol>
     *   <li><strong>Asking for the agent the task already has changes
     *       nothing</strong>, so there is nothing to refuse -- not even once that
     *       agent has been switched off. ADR-005 §5 settled that a PUT declaring
     *       an end state that is already true has it right, and here the
     *       consequence is useful rather than merely consistent: a client
     *       re-stating what it just read is not punished for a deactivation that
     *       does not concern it.</li>
     *   <li><strong>A frozen task does not change hands.</strong> Same guard as
     *       the project side, called from the same method, because a container out
     *       of the working registry that still lets its contents be re-staffed is
     *       not out of anything (ADR-006 §2, ADR-010 D2).</li>
     *   <li><strong>An inactive agent receives no work</strong> -- and for its own
     *       reason, not by analogy with the archived project. See
     *       {@link InactiveAgentCannotReceiveTasksException}.</li>
     * </ol>
     *
     * <p>Task before destination, so that when both refusals apply the answer is
     * deterministic and names the task: it is frozen before anybody looks at who
     * it is being given to. ADR-006 §7 made the same choice for source before
     * destination.
     *
     * <p><strong>What is deliberately absent:</strong> no rule about the agent the
     * task is <em>leaving</em>. None depends on its state, which is what ADR-010
     * D3 decided, and it is why the service does not lock that row.
     *
     * <p>Like the project side, this cannot guarantee on its own that
     * {@code this.agent} and {@code this.project} are still true. That is rule L0:
     * the task row is locked before its associations are read.
     */
    public void assignTo(Agent target, Project projectItLivesIn) {

        if (agent != null && target.getId() != null
                && Objects.equals(agent.getId(), target.getId())) {
            return;
        }

        requireNotFrozen(projectItLivesIn);

        if (!target.isActive()) {
            throw new InactiveAgentCannotReceiveTasksException(target.getId());
        }

        this.agent = target;
    }

    /**
     * Replaces the details of this task: title, description, priority (TASK-016).
     *
     * <p>Not the status, and not the associations -- each has its own path with
     * its own rules. The one rule here is the freezing rule, and it is here because
     * ADR-006 §2 said "any future write to that task" and this is one: a task in an
     * archived project is read-only in every field, not only in the ones that
     * existed when the rule was written.
     */
    public void updateDetails(String title, String description, TaskPriority priority,
                              Project projectItLivesIn) {

        requireNotFrozen(projectItLivesIn);

        this.title = title;
        this.description = description;
        this.priority = priority;
    }

    /**
     * Moves this task along one edge of its lifecycle (ADR-014).
     *
     * <p>Three rules, in this order, and the order is the decision:
     *
     * <ol>
     *   <li><strong>A frozen task does not move</strong> -- ADR-006 §2, "any future
     *       write to that task", applied for the third time. First, because it is
     *       the refusal the caller can act on whatever else is wrong: restore the
     *       project and ask again.</li>
     *   <li><strong>The transition must be an edge from the current state.</strong>
     *       Not idempotent: a repeated {@code start} is a 409, like a repeated
     *       {@code archive} (ADR-004 §4). The caller asked to move and the task was
     *       not where it thought.</li>
     *   <li><strong>Starting needs somebody who can work</strong>: an agent, and an
     *       active one. On {@link TaskTransition#START} only -- leaving
     *       {@code IN_PROGRESS} never depends on the agent (ADR-010 D3).</li>
     * </ol>
     *
     * <p>{@code lockedAgent} is the task's own agent, handed in as the instance the
     * caller locked (rule L2), or {@code null} when the task has none or the edge
     * does not need it. Like {@link #assignTo(Agent, Project)}, this cannot know on
     * its own that {@code this.agent} is still true: that is rule L0.
     */
    public void apply(TaskTransition transition, Project projectItLivesIn, Agent lockedAgent) {

        requireNotFrozen(projectItLivesIn);

        if (status != transition.from()) {
            throw new IllegalTaskStateTransitionException(status, transition);
        }

        if (transition.requiresActiveAgent()) {
            if (lockedAgent == null) {
                throw new UnassignedTaskCannotStartException(id);
            }
            if (!lockedAgent.isActive()) {
                throw new InactiveAgentCannotReceiveTasksException(lockedAgent.getId());
            }
        }

        this.status = transition.to();
    }

    /**
     * The freezing rule of ADR-006 §2, in one place.
     *
     * <p>It used to live inline in {@link #assignTo(Project)}, where it read as a
     * rule about the association. It is not: it is a rule about <em>the task</em>,
     * and ADR-006 §2 wrote it in general terms -- "any future write to that task".
     * TASK-009 is the first time that sentence was tested, and it only held
     * because this method was extracted and the new path calls it. Nothing
     * extends the rule automatically; an {@code assignTo(Agent)} written without
     * this call would have punched a hole in ADR-006 §2 without turning anything
     * red.
     *
     * <p><strong>Why the project arrives as a parameter.</strong> Not because
     * reading {@code this.project} was wrong -- Hibernate keeps one instance per id
     * per session, so the proxy resolves to the row the caller locked either way,
     * and a test was briefly thought to prove otherwise before the real cause
     * turned out to be a mutation left un-reverted in the working tree. The reason
     * is narrower and it is about the reader: passing the locked instance makes the
     * rule read what the caller <em>decided</em> to lock rather than what the entity
     * happens to hold, and it means a caller cannot reach this rule without having
     * answered the question "where does that state come from". The alternative left
     * a lookup whose value went nowhere, which is a line the next person deletes.
     */
    private void requireNotFrozen(Project projectItLivesIn) {
        if (projectItLivesIn != null && projectItLivesIn.isArchived()) {
            throw new ArchivedProjectTaskIsImmutableException(projectItLivesIn.getId());
        }
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

    public TaskStatus getStatus() {
        return status;
    }

    public TaskPriority getPriority() {
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

    /**
     * The responsible agent, or {@code null}. Lazily loaded: call it inside a
     * transaction.
     */
    public Agent getAgent() {
        return agent;
    }

    /** Identifier of the responsible agent, or {@code null}. Same rule as above. */
    public Long getAgentId() {
        return agent == null ? null : agent.getId();
    }
}
