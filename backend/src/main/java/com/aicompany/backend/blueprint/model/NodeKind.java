package com.aicompany.backend.blueprint.model;

/**
 * What a block of the Blueprint is (ADR-033 §2). A closed vocabulary, enforced
 * twice: here and by {@code blueprint_nodes_kind_check}.
 */
public enum NodeKind {
    FILE, CLASS, INTERFACE, ENUM, FUNCTION, CONTROLLER, SERVICE, REPOSITORY, DTO, ENTITY, CONFIGURATION, DATABASE,
    ENDPOINT, COMPONENT, HOOK, STORE, TEST, SCRIPT, SNIPPET, DOCUMENT, CUSTOM
}
