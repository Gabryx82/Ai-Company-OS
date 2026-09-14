package com.aicompany.backend.agent.exception;

public class AgentNotFoundException extends RuntimeException {
    public AgentNotFoundException(Long id) {
        super("No agent with id " + id);
    }
}
