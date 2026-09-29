package com.aicompany.backend.blueprint.model;

/**
 * What an edge of the Blueprint means (ADR-033 §2). Closed, enforced twice: here
 * and by {@code blueprint_edges_relation_check}.
 */
public enum RelationType {
    IMPORTS, USES, CALLS, EXTENDS, IMPLEMENTS, READS, WRITES, CREATES, CONTAINS, BELONGS_TO, VALIDATES, EMITS,
    SUBSCRIBES, MAPS_TO, ENDPOINT_TO_SERVICE, SERVICE_TO_REPOSITORY, ENTITY_TO_TABLE, TEST_COVERS, CUSTOM
}
