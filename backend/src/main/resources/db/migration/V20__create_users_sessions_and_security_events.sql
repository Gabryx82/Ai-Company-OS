-- PHASE 15 (ADR-024). People sign in; tokens in configuration remain for machines.
--
-- Passwords are stored only as BCrypt hashes. Session tokens are stored only as
-- SHA-256 digests: a copy of this table does not let anybody sign in.

CREATE TABLE app_users (
    id                   BIGSERIAL PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL,
    display_name         VARCHAR(120),
    password_hash        VARCHAR(100) NOT NULL,
    role                 VARCHAR(16)  NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_attempts      INTEGER      NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    password_changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at        TIMESTAMPTZ,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT app_users_role_check CHECK (role IN ('ADMIN', 'OPERATOR')),
    CONSTRAINT app_users_username_format CHECK (username ~ '^[a-z0-9][a-z0-9._-]{1,63}$')
);

CREATE UNIQUE INDEX app_users_username_key ON app_users (username);

CREATE TABLE auth_sessions (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    token_digest VARCHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    client       VARCHAR(200)
);

CREATE UNIQUE INDEX auth_sessions_token_digest_key ON auth_sessions (token_digest);
CREATE INDEX auth_sessions_user_idx ON auth_sessions (user_id);

CREATE TABLE security_events (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    type        VARCHAR(40)  NOT NULL,
    principal   VARCHAR(64),
    source      VARCHAR(64),
    detail      VARCHAR(500)
);

CREATE INDEX security_events_occurred_idx ON security_events (occurred_at DESC);
