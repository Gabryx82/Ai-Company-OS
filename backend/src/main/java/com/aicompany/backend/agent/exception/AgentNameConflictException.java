package com.aicompany.backend.agent.exception;

/**
 * The agent name is already taken, ignoring case. Raised both by the readable
 * pre-check and by the translation of the unique index violation, so the API
 * contract is the same whichever of the two caught it (ADR-004 §5).
 */
public class AgentNameConflictException extends RuntimeException {
    public AgentNameConflictException(String name) {
        super("An agent named '" + name + "' already exists");
    }
}
