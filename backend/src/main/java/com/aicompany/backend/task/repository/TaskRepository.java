package com.aicompany.backend.task.repository;

import com.aicompany.backend.task.model.Task;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * The task row, locked exclusively. Rule L0 of ADR-006 section 4.
     *
     * <p>Every transaction that mutates an existing task takes this before it
     * reads the task's association, and holds it to commit. Without it the
     * protocol protects the rows a decision <em>reads</em> but not the input that
     * decides which rows those are: a transaction can read "the task is in A, A
     * is ACTIVE", stay open while somebody moves the task into C and archives C,
     * and then commit its write against a task that belongs to an archived
     * project. No project lock catches that -- the stale writer holds exactly
     * {A, B} and the archive touches C, disjoint sets that never meet.
     *
     * <p><strong>No join fetch here, deliberately.</strong> Two reasons, and the
     * second one is not negotiable: the association must be read from the row
     * this query locked rather than from a snapshot taken before the lock, and
     * PostgreSQL refuses {@code FOR UPDATE} applied to the nullable side of an
     * outer join, which is what {@code LEFT JOIN FETCH t.project} would produce.
     * The source project is resolved afterwards, under its own shared lock (L2).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);

    /**
     * The tasks of one project, oldest first.
     *
     * <p>Both associations are fetched rather than lazy because every row is
     * about to be mapped to a response that carries both identifiers: without it
     * this is two queries per task. {@code left join} keeps the semantics of an
     * inner join for the project -- the filter already excludes null ones -- while
     * staying correct if the filter ever moves, and it is the only correct join
     * for the agent, which is null on most rows.
     */
    @Query("SELECT t FROM Task t LEFT JOIN FETCH t.project p LEFT JOIN FETCH t.agent "
            + "WHERE p.id = :projectId ORDER BY t.id ASC")
    List<Task> findAllByProjectId(@Param("projectId") Long projectId);

    /**
     * The tasks of one agent, oldest first.
     *
     * <p>An inactive agent answers this normally. Deactivation takes an agent out
     * of the working registry; it does not make the work it did unreadable, and
     * reads are not what any rule here restricts (rule L6).
     */
    @Query("SELECT t FROM Task t LEFT JOIN FETCH t.project LEFT JOIN FETCH t.agent a "
            + "WHERE a.id = :agentId ORDER BY t.id ASC")
    List<Task> findAllByAgentId(@Param("agentId") Long agentId);

    /**
     * Every task, oldest first, with both associations resolved in the same query.
     *
     * <p>Renamed from {@code findAllWithProject} by TASK-009 rather than quietly
     * widened: a name that says "with project" while fetching two associations is
     * a comment that has stopped being true.
     */
    @Query("SELECT t FROM Task t LEFT JOIN FETCH t.project LEFT JOIN FETCH t.agent ORDER BY t.id ASC")
    List<Task> findAllWithAssociations();

    /**
     * One task with both associations resolved, for the single read.
     *
     * <p>{@code findById} would work and would cost two extra queries: the record
     * carries both identifiers, and reading them off lazy proxies means loading
     * each one. That was a LOW finding of the TASK-008 review, closed here because
     * the second association made it twice as expensive.
     */
    @Query("SELECT t FROM Task t LEFT JOIN FETCH t.project LEFT JOIN FETCH t.agent WHERE t.id = :id")
    Optional<Task> findByIdWithAssociations(@Param("id") Long id);
}
