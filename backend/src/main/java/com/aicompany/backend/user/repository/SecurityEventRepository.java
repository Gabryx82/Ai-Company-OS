package com.aicompany.backend.user.repository;

import com.aicompany.backend.user.model.SecurityEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {

    List<SecurityEvent> findAllByOrderByOccurredAtDescIdDesc(Pageable page);
}
