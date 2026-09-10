package com.aicompany.backend.task.repository;

import com.aicompany.backend.task.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {
}