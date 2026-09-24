package com.aicompany.backend.run.repository;

import com.aicompany.backend.run.model.RunStatus;
import com.aicompany.backend.run.model.TaskRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    List<TaskRun> findAllByTaskIdOrderByIdDesc(Long taskId);

    /** PHASE 10: a draft plan's task that was ever run is work, and the draft is no longer replaceable. */
    boolean existsByTaskId(Long taskId);

    boolean existsByTaskIdAndStatusIn(Long taskId, Collection<RunStatus> statuses);

    List<TaskRun> findAllByStatusIn(Collection<RunStatus> statuses);

    /**
     * The run row, exclusively. Every executor transition takes it, so two
     * executors -- or an executor and the startup recovery -- cannot both move the
     * same run. Only run rows are locked on this path, never a task: the lock
     * graph gains a class with no arc into the others (ADR-016 §5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TaskRun r WHERE r.id = :id")
    Optional<TaskRun> findByIdForUpdate(@Param("id") Long id);
}
