package com.aicompany.backend.blueprint.repository;

import com.aicompany.backend.blueprint.model.BlueprintLayout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BlueprintLayoutRepository extends JpaRepository<BlueprintLayout, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from BlueprintLayout l where l.projectId = :projectId")
    Optional<BlueprintLayout> findForUpdate(@Param("projectId") Long projectId);
}
