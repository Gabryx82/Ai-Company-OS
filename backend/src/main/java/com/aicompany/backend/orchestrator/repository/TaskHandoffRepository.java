package com.aicompany.backend.orchestrator.repository;

import com.aicompany.backend.orchestrator.model.TaskHandoff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskHandoffRepository extends JpaRepository<TaskHandoff, Long> {

    List<TaskHandoff> findAllByTaskIdOrderByIdDesc(Long taskId);
}
