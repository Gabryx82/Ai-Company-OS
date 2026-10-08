package com.aicompany.backend.task.repository;

import com.aicompany.backend.task.model.TaskTransitionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskTransitionRepository extends JpaRepository<TaskTransitionRecord, Long> {

    /** The history of a task, the most recent first -- the order of its runs. */
    List<TaskTransitionRecord> findAllByTaskIdOrderByIdDesc(Long taskId);

    /** Whether the task ever left the state it was created in (plan replacement, ADR-021 §2). */
    boolean existsByTaskId(Long taskId);
}
