-- PHASE 32 (TD-66). A model has one role, and may serve others as well.
--
-- Since V27 (ADR-034) no ACTIVE model has the role CODER: DeepSeek Coder was
-- superseded by qwen3.5:9b, measured better at code (6/6 against 4/6), whose role
-- is PLANNER. Code work therefore reached qwen3.5:9b only as a "fallback" to
-- PLANNER. A second column says which other roles a model serves, so the routing
-- finds it as a CODER model and says so.
--
-- Additive: a nullable column. The one row written is the measured case, and only
-- where it still is what V27 left (an operator who re-classified it keeps theirs).
ALTER TABLE llm_models ADD COLUMN also_serves VARCHAR(200);

UPDATE llm_models SET also_serves = 'CODER'
WHERE key = 'ollama:qwen3.5:9b' AND role = 'PLANNER' AND also_serves IS NULL;
