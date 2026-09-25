-- PHASE 24 (ADR-031, closes TD-40). What a run costs, and how much a
-- pay-per-token provider may spend in a month. Additive only.

-- USD per million tokens; NULL means "not known yet". A local or subscription
-- model costs nothing per run and needs no price.
ALTER TABLE llm_models ADD COLUMN input_price_per_mtok  NUMERIC(12, 4);
ALTER TABLE llm_models ADD COLUMN output_price_per_mtok NUMERIC(12, 4);

-- The cost of a finished run, computed when it finished with the price of that
-- moment; NULL for runs that predate this column or whose price was unknown.
ALTER TABLE task_runs ADD COLUMN cost_usd NUMERIC(14, 6);

CREATE TABLE cost_budgets (
    id                BIGSERIAL     PRIMARY KEY,
    provider_key      VARCHAR(64)   NOT NULL,
    monthly_limit_usd NUMERIC(12, 2) NOT NULL,
    alert_percent     INTEGER       NOT NULL DEFAULT 80,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT cost_budgets_limit_check CHECK (monthly_limit_usd >= 0),
    CONSTRAINT cost_budgets_alert_check CHECK (alert_percent BETWEEN 1 AND 100)
);

CREATE UNIQUE INDEX cost_budgets_provider_idx ON cost_budgets (provider_key);
