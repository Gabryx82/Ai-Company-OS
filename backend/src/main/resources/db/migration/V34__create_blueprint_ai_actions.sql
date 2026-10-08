-- PHASE 32 (TD-63). An AI action of the Blueprint asks through a task; its outcome
-- (the run's output, or the Esito section an external agent writes in the task's
-- document) now comes back to the block it was asked about. This table is the link:
-- which block, which action, which task.
--
-- Additive: a new table. A block that is deleted takes its requests with it, like
-- its relations (ON DELETE CASCADE, the rule of V26 for the Blueprint's own rows);
-- a task that is deleted removes them explicitly (DeletionService), like every
-- other row that points at a task.
CREATE TABLE blueprint_ai_actions (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL REFERENCES projects (id),
    node_id       BIGINT       NOT NULL REFERENCES blueprint_nodes (id) ON DELETE CASCADE,
    task_id       BIGINT       NOT NULL REFERENCES tasks (id),
    action        VARCHAR(40)  NOT NULL,
    requested_by  VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT blueprint_ai_actions_action_check CHECK (action IN ('EXPLAIN_COMPONENT', 'GENERATE_PSEUDOCODE',
        'DETECT_MISSING_RELATIONS', 'EXPLAIN_DEPENDENCY', 'SUGGEST_REFACTOR', 'FIND_ARCHITECTURAL_ISSUE',
        'GENERATE_DOCUMENTATION', 'CREATE_IMPLEMENTATION_TASK'))
);

CREATE INDEX blueprint_ai_actions_node_idx ON blueprint_ai_actions (node_id, id);
CREATE INDEX blueprint_ai_actions_task_idx ON blueprint_ai_actions (task_id);
