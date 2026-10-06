-- PHASE 30 (ADR-035). Agents can be deleted for real; their runs stay.
--
-- A run says who did it (V10: agent_id is the agent AT THE TIME of the run).
-- Deleting the agent must not delete that history, and must not leave a
-- foreign key pointing at nothing. So a run of a deleted agent keeps the
-- agent's name, written at the moment of the delete, and its agent_id becomes
-- NULL. While the agent exists the name is read through the agent, and this
-- column stays NULL.
--
-- Additive and retro-compatible: a new nullable column, and a NOT NULL relaxed.
-- Every row that exists keeps its agent_id; code that always writes one keeps
-- working unchanged.

ALTER TABLE task_runs ADD COLUMN agent_name VARCHAR(255);
ALTER TABLE task_runs ALTER COLUMN agent_id DROP NOT NULL;

-- A run has an agent, or the name of the one it had: never neither.
ALTER TABLE task_runs ADD CONSTRAINT task_runs_agent_known
    CHECK (agent_id IS NOT NULL OR agent_name IS NOT NULL);
