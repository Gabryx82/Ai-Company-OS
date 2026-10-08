-- PHASE 32 (TD-38). Who moved a task, when, and through what.
--
-- Additive by construction: a new table, no existing row read or written. The
-- history begins with this migration: transitions made before it were never
-- recorded and are not invented here.
--
-- One row per edge taken (ADR-014): the edge, the two states it joined, the
-- person, and the path it came through -- the lifecycle routes (API), a run's
-- launch (RUN), a handoff (HANDOFF) or a review (REVIEW). A refused transition
-- writes nothing: the row is written in the transaction that moves the task.
--
-- Foreign key without ON DELETE, like every other one in the stream: deleting a
-- task removes its history explicitly (DeletionService), the decision in code.
CREATE TABLE task_transitions (
    id           BIGSERIAL    PRIMARY KEY,
    task_id      BIGINT       NOT NULL REFERENCES tasks (id),
    transition   VARCHAR(16)  NOT NULL,
    from_status  VARCHAR(32)  NOT NULL,
    to_status    VARCHAR(32)  NOT NULL,
    actor        VARCHAR(120) NOT NULL,
    via          VARCHAR(16)  NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT task_transitions_transition_check
        CHECK (transition IN ('START', 'COMPLETE', 'STOP', 'REOPEN')),
    CONSTRAINT task_transitions_via_check
        CHECK (via IN ('API', 'RUN', 'HANDOFF', 'REVIEW'))
);

CREATE INDEX task_transitions_task_idx ON task_transitions (task_id, id);
