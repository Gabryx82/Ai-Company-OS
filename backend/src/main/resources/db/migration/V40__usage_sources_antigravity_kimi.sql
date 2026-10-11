-- PHASE 33 (ADR-038). Two more places a usage figure can come from -- the model quotas
-- an Antigravity app keeps in its state, and Kimi Code's own answer -- and a window that
-- is not one length but one per model (Antigravity resets every model on its own).
--
-- Additive: both checks only widen; every row valid before stays valid. The plans
-- themselves come from catalog/quota-plans.json at startup, as before.
ALTER TABLE quota_plans DROP CONSTRAINT quota_plans_source_check;
ALTER TABLE quota_plans ADD CONSTRAINT quota_plans_source_check
    CHECK (source IN ('CODEX_LOCAL', 'CLAUDE_CODE_LOCAL', 'ENGINE_RUNS', 'MANUAL', 'ANTIGRAVITY_LOCAL', 'KIMI_CODE_API'));

ALTER TABLE quota_plans DROP CONSTRAINT quota_plans_window_check;
ALTER TABLE quota_plans ADD CONSTRAINT quota_plans_window_check
    CHECK (window_kind IN ('ROLLING_5H', 'DAILY', 'WEEKLY', 'MONTHLY', 'PER_MODEL'));
