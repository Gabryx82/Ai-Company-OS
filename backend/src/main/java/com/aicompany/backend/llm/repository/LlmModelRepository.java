package com.aicompany.backend.llm.repository;

import com.aicompany.backend.llm.model.LlmModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LlmModelRepository extends JpaRepository<LlmModel, Long> {

    Optional<LlmModel> findByKey(String key);

    boolean existsByKey(String key);

    List<LlmModel> findAllByOrderByProviderKeyAscKeyAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from LlmModel m where m.key = :key")
    Optional<LlmModel> findByKeyForUpdate(@Param("key") String key);
}
