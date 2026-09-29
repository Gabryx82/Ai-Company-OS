package com.aicompany.backend.blueprint.repository;

import com.aicompany.backend.blueprint.model.BlueprintScan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BlueprintScanRepository extends JpaRepository<BlueprintScan, Long> {

    List<BlueprintScan> findTop10ByProjectIdOrderByIdDesc(Long projectId);

    Optional<BlueprintScan> findFirstByProjectIdOrderByIdDesc(Long projectId);
}
