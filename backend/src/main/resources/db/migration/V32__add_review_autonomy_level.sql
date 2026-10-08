-- PHASE 32 (TD-45). The Human-in-the-Loop level a review was given at.
--
-- Additive: a nullable column, no existing row rewritten. NULL means a review
-- recorded before this migration, or of a task outside any project: the level
-- it happened at is not known, and it is not guessed. The learning metrics
-- (ADR-022 §5) count only the reviews given at the project's current level.
ALTER TABLE task_reviews ADD COLUMN autonomy_level VARCHAR(24);

ALTER TABLE task_reviews ADD CONSTRAINT task_reviews_autonomy_level_check
    CHECK (autonomy_level IS NULL OR autonomy_level IN ('GUIDED', 'SUPERVISED', 'DELEGATED', 'FINAL_REVIEW'));
