package com.aicompany.backend.llm.repository;

import com.aicompany.backend.llm.model.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelProviderRepository extends JpaRepository<ModelProvider, Long> {

    Optional<ModelProvider> findByKey(String key);

    boolean existsByKey(String key);

    List<ModelProvider> findAllByOrderByNameAsc();
}
