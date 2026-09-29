package com.aicompany.backend.blueprint.repository;

import com.aicompany.backend.blueprint.model.BlueprintNode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BlueprintNodeRepository extends JpaRepository<BlueprintNode, Long> {

    List<BlueprintNode> findAllByProjectIdOrderByIdAsc(Long projectId);

    Optional<BlueprintNode> findByIdAndProjectId(Long id, Long projectId);

    List<BlueprintNode> findAllByParentId(Long parentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from BlueprintNode n where n.id = :id and n.projectId = :projectId")
    Optional<BlueprintNode> findByIdForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);
}
