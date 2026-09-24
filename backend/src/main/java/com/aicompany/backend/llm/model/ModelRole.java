package com.aicompany.backend.llm.model;

/**
 * What a model is for, from the orchestrator's point of view (ADR-018 §2).
 * {@code llm_models_role_check} (V13).
 */
public enum ModelRole { FAST, GENERAL, CODER, PLANNER, VISION, REASONING, PREMIUM, EMBEDDING, TEST }
