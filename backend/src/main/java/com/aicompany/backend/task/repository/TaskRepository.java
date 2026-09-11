package com.aicompany.backend.task.repository;

import com.aicompany.backend.task.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

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
