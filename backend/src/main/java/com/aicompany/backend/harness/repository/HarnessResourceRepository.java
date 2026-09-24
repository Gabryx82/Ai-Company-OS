package com.aicompany.backend.harness.repository;

import com.aicompany.backend.harness.model.HarnessResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HarnessResourceRepository extends JpaRepository<HarnessResource, Long> {

    Optional<HarnessResource> findByKey(String key);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from HarnessResource r where r.key = :key")
    Optional<HarnessResource> findByKeyForUpdate(@org.springframework.data.repository.query.Param("key") String key);

    boolean existsByKey(String key);

    List<HarnessResource> findAllByOrderByKindAscNameAsc();
}
