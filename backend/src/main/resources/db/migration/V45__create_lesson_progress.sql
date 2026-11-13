-- PHASE 37 (ADR-044). Learning that is done, not read.
--
-- A lesson is a file an agent writes in the project (`lessons/<TASK>.lezione.md`):
-- the code, explained line by line, that the operator copies into the right files,
-- with a question to answer before and exercises after. The file is the lesson; what
-- the operator did with it is kept here, one row per item of a lesson -- the
-- prediction, each step, each exercise -- so that the answers the operator gives
-- pass through the control plane at last (ADR-022 §5 had left them out: they lived
-- in the chat of whichever tool the operator worked in).
--
-- A concept a finished lesson says was learnt becomes a card that comes back for
-- review after 1, 3, 7, 16 and 35 days (`box` 1..5), sooner when it was not known.
--
-- A handoff says how it asked the agent to work: teaching (the lesson, the operator
-- copies), recap (the agent works alone and leaves the lesson), or neither.
--
-- Additive: two tables whose rows go with their project, one nullable column.

CREATE TABLE lesson_progress (
    project_id       BIGINT        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    lesson_path      VARCHAR(500)  NOT NULL,
    item_kind        VARCHAR(16)   NOT NULL,
    item_index       SMALLINT      NOT NULL,
    attempts         INTEGER       NOT NULL DEFAULT 0,
    first_try_right  BOOLEAN,
    done             BOOLEAN       NOT NULL DEFAULT FALSE,
    answer           VARCHAR(2000),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       VARCHAR(64),

    CONSTRAINT lesson_progress_pkey PRIMARY KEY (project_id, lesson_path, item_kind, item_index),
    CONSTRAINT lesson_progress_kind_check CHECK (item_kind IN ('PREDICTION', 'STEP', 'EXERCISE')),
    CONSTRAINT lesson_progress_index_check CHECK (item_index >= 0),
    CONSTRAINT lesson_progress_attempts_check CHECK (attempts >= 0)
);

CREATE TABLE learning_concepts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id    BIGINT        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    lesson_path   VARCHAR(500)  NOT NULL,
    name          VARCHAR(200)  NOT NULL,
    explanation   VARCHAR(2000) NOT NULL,
    box           SMALLINT      NOT NULL DEFAULT 1,
    due_on        DATE          NOT NULL,
    reviews       INTEGER       NOT NULL DEFAULT 0,
    last_known    BOOLEAN,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    reviewed_at   TIMESTAMPTZ,

    CONSTRAINT learning_concepts_unique UNIQUE (project_id, lesson_path, name),
    CONSTRAINT learning_concepts_box_check CHECK (box BETWEEN 1 AND 5),
    CONSTRAINT learning_concepts_reviews_check CHECK (reviews >= 0)
);
CREATE INDEX learning_concepts_due_idx ON learning_concepts (due_on);

ALTER TABLE task_handoffs ADD COLUMN learning_mode VARCHAR(16);
ALTER TABLE task_handoffs ADD CONSTRAINT task_handoffs_learning_mode_check
    CHECK (learning_mode IS NULL OR learning_mode IN ('TEACH', 'RECAP', 'NONE'));
