package com.aicompany.backend.blueprint.repository;

import com.aicompany.backend.blueprint.model.BlueprintEdge;
import com.aicompany.backend.blueprint.model.EdgeOrigin;
import com.aicompany.backend.blueprint.model.RelationType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BlueprintEdgeRepository extends JpaRepository<BlueprintEdge, Long> {

    List<BlueprintEdge> findAllByProjectIdOrderByIdAsc(Long projectId);

    List<BlueprintEdge> findAllByProjectIdAndOrigin(Long projectId, EdgeOrigin origin);

    Optional<BlueprintEdge> findByIdAndProjectId(Long id, Long projectId);

    @Query("select e from BlueprintEdge e where e.sourceNodeId = :nodeId or e.targetNodeId = :nodeId order by e.id")
    List<BlueprintEdge> findAllTouching(@Param("nodeId") Long nodeId);

    Optional<BlueprintEdge> findBySourceNodeIdAndTargetNodeIdAndRelation(Long sourceNodeId, Long targetNodeId,
                                                                       RelationType relation);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from BlueprintEdge e where e.id = :id and e.projectId = :projectId")
    Optional<BlueprintEdge> findByIdForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);
}
