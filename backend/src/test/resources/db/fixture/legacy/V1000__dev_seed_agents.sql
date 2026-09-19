-- Test fixture, not a product migration.
--
-- Plays the role of the pre-TASK-001A seed: a versioned migration numbered 1000
-- inside the *schema* stream, with a plain INSERT and no idempotency guard. Used
-- to rebuild a legacy development database so the tests can reproduce the
-- failure reported as R1 and then verify the documented recovery.
--
-- What this fixture reproduces is the STRUCTURAL mistake -- seed data carrying a
-- schema version number, numbered high enough to block later schema migrations.
-- It is not a byte-for-byte copy of the historical file and cannot be: version
-- 1000 sorts after every real migration, so this statement executes against the
-- schema at its head, and TD-31's V8 replaced agents.active with agents.status.
-- A frozen copy of the old bytes would simply fail to run.

INSERT INTO agents (name, role, specialization, status) VALUES
    ('Code Architect',      'Software Engineer', 'Backend architecture and system design', 'ACTIVE'),
    ('Frontend Developer',  'Frontend Engineer', 'React TypeScript UI development',        'ACTIVE'),
    ('Database Specialist', 'Database Engineer', 'PostgreSQL and data modeling',           'ACTIVE');
