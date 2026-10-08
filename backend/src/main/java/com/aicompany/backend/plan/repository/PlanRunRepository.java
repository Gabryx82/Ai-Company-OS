package com.aicompany.backend.plan.repository;

import com.aicompany.backend.plan.model.PlanRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlanRunRepository extends JpaRepository<PlanRun, Long> {

    List<PlanRun> findTop10ByProjectIdOrderByIdDesc(Long projectId);

    Optional<PlanRun> findFirstByProjectIdAndStatus(Long projectId, PlanRun.Status status);

    List<PlanRun> findByStatus(PlanRun.Status status);

    /** PHASE 32 (TD-43): a cancellation and the end of the generation take the run in turn. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PlanRun r where r.id = :id")
    Optional<PlanRun> findByIdForUpdate(@Param("id") Long id);
}
