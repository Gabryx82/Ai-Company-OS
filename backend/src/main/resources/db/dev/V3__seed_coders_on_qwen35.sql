-- PHASE 29 (ADR-034). The two seed agents that wrote code and SQL on DeepSeek Coder
-- V2 16B move to Qwen 3.5 9B. Measured on the operator's machine (2026-09-30, Ollama
-- 0.33.3, same prompts, temperature 0): code 6/6 against 4/6, reasoning 4/4 against
-- 0/4, structured output 5/5 against 3/5, and tool calling, which DeepSeek does not
-- have. DeepSeek stays installed as the rollback (catalog: DEPRECATED, V27).
--
-- Only an agent still on its initial model moves, and its baseline moves with it, so
-- the console keeps showing it as not modified. An agent the operator bound to
-- another model keeps it. version is bumped: a console holding the old ETag must
-- reload before it saves.

UPDATE agents SET
    model      = 'ollama:qwen3.5:9b',
    baseline   = replace(baseline, '"model":"ollama:deepseek-coder-v2:16b"', '"model":"ollama:qwen3.5:9b"'),
    version    = version + 1,
    updated_at = now()
WHERE origin = 'SEED'
  AND name IN ('Frontend Developer', 'Database Specialist')
  AND model = 'ollama:deepseek-coder-v2:16b'
  AND baseline LIKE '%"model":"ollama:deepseek-coder-v2:16b"%';
