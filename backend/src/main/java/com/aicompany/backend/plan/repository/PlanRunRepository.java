package com.aicompany.backend.plan.repository;

import com.aicompany.backend.plan.model.PlanRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanRunRepository extends JpaRepository<PlanRun, Long> {

    List<PlanRun> findTop10ByProjectIdOrderByIdDesc(Long projectId);

    Optional<PlanRun> findFirstByProjectIdAndStatus(Long projectId, PlanRun.Status status);

    List<PlanRun> findByStatus(PlanRun.Status status);
}
