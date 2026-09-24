package com.aicompany.backend.harness.repository;

import com.aicompany.backend.harness.model.HarnessResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HarnessResourceRepository extends JpaRepository<HarnessResource, Long> {

    Optional<HarnessResource> findByKey(String key);

    boolean existsByKey(String key);

    List<HarnessResource> findAllByOrderByKindAscNameAsc();
}
