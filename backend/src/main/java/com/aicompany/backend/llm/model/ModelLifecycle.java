package com.aicompany.backend.llm.model;

/**
 * The directive's model-migration rule made visible in the data: a replacement
 * is a CANDIDATE until verified, the model it replaces is DEPRECATED only after
 * that, and RETIRED only when removed. {@code llm_models_lifecycle_check} (V13).
 */
public enum ModelLifecycle { ACTIVE, CANDIDATE, DEPRECATED, RETIRED }
