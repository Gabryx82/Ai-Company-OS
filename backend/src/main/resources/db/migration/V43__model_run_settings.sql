-- PHASE 35 (ADR-041 §11). How each model runs, in the catalog: the context window asked
-- of Ollama (num_ctx), how long Ollama keeps the model loaded after an answer (keep_alive),
-- and the quantization recommended for this machine (informational: the model file decides).
--
-- Per model, not per role: a num_ctx that changes between two requests to the same model
-- makes Ollama reload it. Values measured or prudent for 9-13 GB of free RAM (ADR-034):
-- the 9B plans too and keeps 16K (its context cache from 4K to 16K costs 0.15-0.4 GB);
-- the small and general models 8K; the two whose cache is heavy (DeepSeek, Llama 3.2) 4K.
-- The 9B and Gemma, which the agents use in a row, stay loaded five minutes; the small
-- models two; the deprecated and the candidate that does not fit, one.
--
-- Additive: nullable columns; values only where nothing is set yet (a model the operator
-- tuned stays as it is), and only for the catalog's local models.

ALTER TABLE llm_models
    ADD COLUMN num_ctx      INTEGER,
    ADD COLUMN keep_alive   VARCHAR(8),
    ADD COLUMN quantization VARCHAR(32);
ALTER TABLE llm_models ADD CONSTRAINT llm_models_num_ctx_check
    CHECK (num_ctx IS NULL OR num_ctx BETWEEN 512 AND 1048576);
ALTER TABLE llm_models ADD CONSTRAINT llm_models_keep_alive_check
    CHECK (keep_alive IS NULL OR keep_alive ~ '^(0|-1|[1-9][0-9]{0,5}[smh]?)$');
ALTER TABLE llm_models ADD CONSTRAINT llm_models_quantization_check
    CHECK (quantization IS NULL OR quantization ~ '^[A-Za-z0-9_]{1,32}$');

UPDATE llm_models SET num_ctx = 16384, keep_alive = '5m', quantization = 'Q4_K_M'
    WHERE key = 'ollama:qwen3.5:9b' AND num_ctx IS NULL AND keep_alive IS NULL AND quantization IS NULL;
UPDATE llm_models SET num_ctx = 8192, keep_alive = '2m', quantization = 'Q4_K_M'
    WHERE key IN ('ollama:qwen3.5:4b', 'ollama:lfm2.5:8b')
      AND num_ctx IS NULL AND keep_alive IS NULL AND quantization IS NULL;
UPDATE llm_models SET num_ctx = 8192, keep_alive = '5m', quantization = 'Q4_K_M'
    WHERE key = 'ollama:gemma4:e4b' AND num_ctx IS NULL AND keep_alive IS NULL AND quantization IS NULL;
UPDATE llm_models SET num_ctx = 4096, keep_alive = '1m', quantization = 'Q4_0'
    WHERE key = 'ollama:deepseek-coder-v2:16b' AND num_ctx IS NULL AND keep_alive IS NULL AND quantization IS NULL;
UPDATE llm_models SET num_ctx = 4096, keep_alive = '1m', quantization = 'Q4_K_M'
    WHERE key = 'ollama:llama3.2:3b' AND num_ctx IS NULL AND keep_alive IS NULL AND quantization IS NULL;
UPDATE llm_models SET num_ctx = 4096, keep_alive = '1m', quantization = 'IQ3_XXS'
    WHERE key = 'ollama:qwen3.8-gsq:27b-iq3xxs' AND num_ctx IS NULL AND keep_alive IS NULL AND quantization IS NULL;
