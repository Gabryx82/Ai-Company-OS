package com.aicompany.backend.task.exception;

/**
 * An assignment was aimed at an agent that is out of the working registry.
 *
 * <p>Refused, and the argument is <strong>not</strong> the one
 * {@link ArchivedProjectCannotReceiveTasksException} uses. That one is about
 * containment: a container put away must not grow. This one is about
 * responsibility -- work handed to an agent that is switched off is an obligation
 * nobody can discharge, and without queue semantics "assigned to someone
 * switched off" is indistinguishable from "forgotten". ADR-010 D1.
 *
 * <p>A conflict rather than a 403, for the reason ADR-004 §8 gave: the refusal is
 * about the state of the resource, not about who is asking, and the caller can
 * lift it themselves -- activate the agent and the same request succeeds.
 */
public class InactiveAgentCannotReceiveTasksException extends RuntimeException {

    public InactiveAgentCannotReceiveTasksException(Long agentId) {
        super("Agent " + agentId + " is inactive and cannot be given work");
    }
}
