package com.aicompany.backend.agent.exception;

/**
 * Editing an agent that is out of the working registry. A conflict rather than a
 * 403: the refusal is about the state of the resource, not about who is asking,
 * and the caller can lift it themselves by activating the agent (ADR-004 §8).
 */
public class InactiveAgentIsImmutableException extends RuntimeException {
    public InactiveAgentIsImmutableException() {
        super("An inactive agent cannot be edited; activate it first");
    }
}
