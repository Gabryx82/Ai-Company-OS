package com.aicompany.backend.usage.model;

/** {@code quota_plans_window_check} (V13). */
public enum WindowKind {
    ROLLING_5H, DAILY, WEEKLY, MONTHLY,
    /** PHASE 33 (ADR-038): every model has its own quota and its own reset (Antigravity). */
    PER_MODEL
}
