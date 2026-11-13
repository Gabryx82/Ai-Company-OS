-- PHASE 35 (ADR-041). What the agents consume, measured before anything is optimised.
--
-- A run keeps what the provider reported about the call -- for Ollama its total,
-- load, prompt evaluation and generation durations -- and the context window the
-- call asked for (num_ctx, a setting the engine sent). Tokens were already kept
-- (input_tokens, output_tokens). NULL means "not reported": nothing is estimated.
--
-- A handoff keeps the size in characters of its package, and one row per file the
-- package asks the agent to read, with its size when the handoff was written (NULL:
-- the file was not there). Handoffs written before this migration have no size and
-- no rows: they were not measured.
--
-- Additive: nullable columns, one new table whose rows go with their handoff.

ALTER TABLE task_runs
    ADD COLUMN provider_total_ms BIGINT,
    ADD COLUMN load_ms           BIGINT,
    ADD COLUMN prompt_eval_ms    BIGINT,
    ADD COLUMN eval_ms           BIGINT,
    ADD COLUMN context_window    INTEGER;
ALTER TABLE task_runs ADD CONSTRAINT task_runs_measures_check CHECK (
    (provider_total_ms IS NULL OR provider_total_ms >= 0)
    AND (load_ms IS NULL OR load_ms >= 0)
    AND (prompt_eval_ms IS NULL OR prompt_eval_ms >= 0)
    AND (eval_ms IS NULL OR eval_ms >= 0)
    AND (context_window IS NULL OR context_window > 0));

ALTER TABLE task_handoffs ADD COLUMN package_chars INTEGER;
ALTER TABLE task_handoffs ADD CONSTRAINT task_handoffs_package_chars_check
    CHECK (package_chars IS NULL OR package_chars >= 0);

CREATE TABLE task_handoff_reads (
    handoff_id  BIGINT        NOT NULL REFERENCES task_handoffs (id) ON DELETE CASCADE,
    position    SMALLINT      NOT NULL,
    kind        VARCHAR(16)   NOT NULL,
    path        VARCHAR(1000) NOT NULL,
    required    BOOLEAN       NOT NULL,
    chars       INTEGER,

    CONSTRAINT task_handoff_reads_pkey PRIMARY KEY (handoff_id, position),
    CONSTRAINT task_handoff_reads_position_check CHECK (position >= 0),
    CONSTRAINT task_handoff_reads_kind_check
        CHECK (kind IN ('GOVERNANCE', 'TASK', 'PHASE', 'CONTEXT_MAP', 'SKILL', 'KNOWLEDGE')),
    CONSTRAINT task_handoff_reads_chars_check CHECK (chars IS NULL OR chars >= 0)
);
