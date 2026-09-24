package com.aicompany.backend.plan.repository;

import com.aicompany.backend.plan.model.ProjectPhase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectPhaseRepository extends JpaRepository<ProjectPhase, Long> {

    @Query("select p from ProjectPhase p where p.project.id = :projectId order by p.number")
    List<ProjectPhase> findByProject(@Param("projectId") Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectPhase p where p.id = :id")
    Optional<ProjectPhase> findByIdForUpdate(@Param("id") Long id);
}
