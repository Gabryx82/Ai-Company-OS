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
     * <p>The join is fetched rather than lazy because every row is about to be
     * mapped to a response that carries the project identifier: without it this
     * is one query per task. {@code left join} keeps the semantics of an inner
     * join here -- the filter already excludes null projects -- while staying
     * correct if the filter ever moves.
     */
    @Query("SELECT t FROM Task t LEFT JOIN FETCH t.project p WHERE p.id = :projectId ORDER BY t.id ASC")
    List<Task> findAllByProjectId(@Param("projectId") Long projectId);

    /** Every task, oldest first, with the project resolved in the same query. */
    @Query("SELECT t FROM Task t LEFT JOIN FETCH t.project ORDER BY t.id ASC")
    List<Task> findAllWithProject();
}
