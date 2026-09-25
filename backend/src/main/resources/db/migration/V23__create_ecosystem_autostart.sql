-- PHASE 21 (ADR-028). Which services of the ecosystem start with AI Company OS,
-- and what happened the last time they were started. How each one is started
-- (executable, arguments, health URL) stays in the Software Hub catalog; this
-- table only says whether, in which order, how long to wait, and the outcome.

CREATE TABLE ecosystem_autostart (
    id                      BIGSERIAL    PRIMARY KEY,
    software_key            VARCHAR(64)  NOT NULL,
    autostart               BOOLEAN      NOT NULL DEFAULT FALSE,
    position                INTEGER      NOT NULL DEFAULT 0,
    startup_timeout_seconds INTEGER      NOT NULL DEFAULT 90,
    last_status             VARCHAR(24)  NOT NULL DEFAULT 'NEVER',
    last_message            VARCHAR(1000),
    last_attempt_at         TIMESTAMPTZ,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ecosystem_autostart_timeout_check CHECK (startup_timeout_seconds BETWEEN 5 AND 600),
    CONSTRAINT ecosystem_autostart_status_check CHECK (last_status IN
        ('NEVER', 'ALREADY_RUNNING', 'STARTING', 'RUNNING', 'FAILED'))
);

CREATE UNIQUE INDEX ecosystem_autostart_key_idx ON ecosystem_autostart (software_key);
