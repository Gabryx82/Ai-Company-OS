-- TASK-019 (ADR-016). One execution of a task by an agent, through the AI Engine.
--
-- Additive by construction: a new table, no existing row read or written.
--
-- The control plane owns the canonical state of a run (ADR-001, data ownership
-- boundary); the engine keeps nothing. So everything needed to understand a run
-- afterwards is here: who asked, which agent, which model was asked for and which
-- one answered, the exact prompts sent, the output or the failure.
--
-- agent_id is the agent AT THE TIME of the run, deliberately not read through
-- tasks.agent_id: a task can be reassigned afterwards, and the run must keep
-- saying who did it.
--
-- Foreign keys without ON DELETE, like every other one in the stream: nothing
-- here is deleted, and a cascade would be a domain decision taken by a flag.
CREATE TABLE task_runs (
    id              BIGSERIAL     PRIMARY KEY,
    task_id         BIGINT        NOT NULL REFERENCES tasks (id),
    agent_id        BIGINT        NOT NULL REFERENCES agents (id),
    status          VARCHAR(32)   NOT NULL,
    requested_model VARCHAR(200),
    served_model    VARCHAR(200),
    system_prompt   TEXT          NOT NULL,
    user_prompt     TEXT          NOT NULL,
    output          TEXT,
    finish_reason   VARCHAR(16),
    failure_type    VARCHAR(200),
    failure_detail  VARCHAR(2000),
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    latency_ms      BIGINT,
    correlation_id  VARCHAR(64)   NOT NULL,
    requested_by    VARCHAR(120)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,

    -- The closed vocabulary of RunStatus, as for every lifecycle column here.
    CONSTRAINT task_runs_status_check
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),

    CONSTRAINT task_runs_finish_reason_check
        CHECK (finish_reason IS NULL OR finish_reason IN ('stop', 'length', 'refusal')),

    -- The outcome columns agree with the status: a success has an output and no
    -- failure, a failure has a failure type, an unfinished run has neither. A row
    -- that says SUCCEEDED with no output is a lie the database refuses to store.
    CONSTRAINT task_runs_outcome_check CHECK (
        (status = 'SUCCEEDED' AND output IS NOT NULL AND finish_reason IS NOT NULL AND failure_type IS NULL
            AND finished_at IS NOT NULL)
        OR (status = 'FAILED' AND failure_type IS NOT NULL AND output IS NULL AND finished_at IS NOT NULL)
        OR (status IN ('QUEUED', 'RUNNING') AND output IS NULL AND failure_type IS NULL AND finished_at IS NULL)
    )
);

CREATE INDEX task_runs_task_id_idx ON task_runs (task_id, id DESC);

-- At most one unfinished run per task. The service already serialises creation
-- on the task row (L0); this is the net under it, not the mechanism.
CREATE UNIQUE INDEX task_runs_one_active_per_task_idx
    ON task_runs (task_id) WHERE status IN ('QUEUED', 'RUNNING');
