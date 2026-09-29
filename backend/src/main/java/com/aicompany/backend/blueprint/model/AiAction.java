package com.aicompany.backend.blueprint.model;

/**
 * What an operator can ask an agent about a block (ADR-033 §8). Each becomes a
 * task with the component's context: execution goes through the orchestrator,
 * the cost governance and the review -- never a direct model call from here.
 */
public enum AiAction {
    EXPLAIN_COMPONENT, GENERATE_PSEUDOCODE, DETECT_MISSING_RELATIONS, EXPLAIN_DEPENDENCY, SUGGEST_REFACTOR,
    FIND_ARCHITECTURAL_ISSUE, GENERATE_DOCUMENTATION, CREATE_IMPLEMENTATION_TASK
}
