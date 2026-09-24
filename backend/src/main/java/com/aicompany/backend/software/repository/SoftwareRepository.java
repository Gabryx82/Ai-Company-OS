package com.aicompany.backend.software.repository;

import com.aicompany.backend.software.model.Software;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SoftwareRepository extends JpaRepository<Software, Long> {

    Optional<Software> findByKey(String key);

    boolean existsByKey(String key);

    List<Software> findAllByOrderByCategoryAscNameAsc();

    /** The write path's read, under the row lock of ADR-009 P1. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Software s where s.key = :key")
    Optional<Software> findByKeyForUpdate(@Param("key") String key);
}
