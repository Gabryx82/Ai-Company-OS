package com.aicompany.backend.agent.repository;

import com.aicompany.backend.agent.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {

}