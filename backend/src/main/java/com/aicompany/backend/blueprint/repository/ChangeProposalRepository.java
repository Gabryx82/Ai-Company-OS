package com.aicompany.backend.blueprint.repository;

import com.aicompany.backend.blueprint.model.ChangeProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChangeProposalRepository extends JpaRepository<ChangeProposal, Long> {

    List<ChangeProposal> findAllByProjectIdOrderByIdDesc(Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ChangeProposal p where p.id = :id and p.projectId = :projectId")
    Optional<ChangeProposal> findByIdForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);
}
