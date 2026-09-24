package com.aicompany.backend.usage.repository;

import com.aicompany.backend.usage.model.QuotaPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuotaPlanRepository extends JpaRepository<QuotaPlan, Long> {

    Optional<QuotaPlan> findByKey(String key);

    boolean existsByKey(String key);

    List<QuotaPlan> findAllByOrderBySubjectAscKeyAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QuotaPlan q where q.key = :key")
    Optional<QuotaPlan> findByKeyForUpdate(@Param("key") String key);
}
