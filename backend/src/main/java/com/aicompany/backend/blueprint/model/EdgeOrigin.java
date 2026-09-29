package com.aicompany.backend.blueprint.model;

/**
 * Where a relation comes from (ADR-033 §3): derived from the code, added by the
 * operator, or suggested and not yet confirmed.
 */
public enum EdgeOrigin { DETECTED, MANUAL, PROPOSED }
