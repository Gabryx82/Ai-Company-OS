package com.aicompany.backend.agent.model;

/** Where an agent's configuration came from (ADR-025 §3). */
public enum AgentOrigin {
    /** The development seed. */
    SEED,
    /** Installed from an agent template of catalog/agent-templates.json. */
    TEMPLATE,
    /** Created by a person. */
    USER
}
