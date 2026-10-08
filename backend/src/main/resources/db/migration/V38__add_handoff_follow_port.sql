-- PHASE 32 (TD-49). A handoff to OpenCode opens its TUI with the local API on a
-- port AI Company OS chose: recorded here, so that the console can follow the
-- session there. NULL for every other handoff.
--
-- Additive: a nullable column.
ALTER TABLE task_handoffs ADD COLUMN follow_port INTEGER;
ALTER TABLE task_handoffs ADD CONSTRAINT task_handoffs_follow_port_check
    CHECK (follow_port IS NULL OR follow_port BETWEEN 1024 AND 65535);
