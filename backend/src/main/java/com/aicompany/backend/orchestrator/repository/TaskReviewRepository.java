package com.aicompany.backend.orchestrator.repository;

import com.aicompany.backend.orchestrator.model.TaskReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskReviewRepository extends JpaRepository<TaskReview, Long> {

    List<TaskReview> findAllByTaskIdOrderByIdDesc(Long taskId);
}
