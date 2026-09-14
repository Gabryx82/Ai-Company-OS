package com.aicompany.backend.agent.exception;

public class IllegalAgentStateTransitionException extends RuntimeException {
    public IllegalAgentStateTransitionException(boolean currentlyActive) {
        super("The agent is already " + (currentlyActive ? "active" : "inactive"));
    }
}
