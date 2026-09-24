-- PHASE 16 (ADR-025). Agent -> Model -> Provider -> Execution Target, made
-- explicit, and where an agent's configuration came from.
--
-- Additive only: every column is nullable or has a default, no row is rewritten.

-- The agent's description and capabilities (directive §4), next to the prompt
-- engineering of V18.
ALTER TABLE agents ADD COLUMN description  VARCHAR(2000);
ALTER TABLE agents ADD COLUMN capabilities VARCHAR(2000);

-- Where the agent works: 'engine' (NULL means the same, for rows that predate
-- this column) or the key of an execution target of catalog/execution-targets.json
-- such as 'claude-code' or 'codex'. The model (V11) stays the model; the provider
-- is the model's, from the catalog: four concepts, four places.
ALTER TABLE agents ADD COLUMN execution_target VARCHAR(64);
ALTER TABLE agents ADD CONSTRAINT agents_execution_target_format
    CHECK (execution_target IS NULL OR execution_target ~ '^[a-z0-9][a-z0-9-]{1,63}$');

-- SEED (the development seed), TEMPLATE (installed from an agent template) or
-- USER (created by a person). baseline is the configuration the agent was born
-- with, as JSON, so the console can say field by field what a person changed.
ALTER TABLE agents ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE agents ADD CONSTRAINT agents_origin_check CHECK (origin IN ('SEED', 'TEMPLATE', 'USER'));
ALTER TABLE agents ADD COLUMN baseline      VARCHAR(16000);
ALTER TABLE agents ADD COLUMN customized_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE agents ADD COLUMN customized_by VARCHAR(64);
