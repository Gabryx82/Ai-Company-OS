package com.aicompany.backend.agent.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentService {

    private final AgentRepository repository;


    public AgentService(AgentRepository repository) {
        this.repository = repository;
    }


    public List<Agent> getAllAgents(){
        return repository.findAll();
    }
}