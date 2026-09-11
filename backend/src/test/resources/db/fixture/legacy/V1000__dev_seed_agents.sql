-- Test fixture, not a product migration.
--
-- Byte-for-byte role of the pre-TASK-001A seed: a versioned migration numbered
-- 1000 inside the *schema* stream, with a plain INSERT and no idempotency guard.
-- Used to rebuild a legacy development database so the tests can reproduce the
-- failure reported as R1 and then verify the documented recovery.

INSERT INTO agents (name, role, specialization, active) VALUES
    ('Code Architect',      'Software Engineer', 'Backend architecture and system design', TRUE),
    ('Frontend Developer',  'Frontend Engineer', 'React TypeScript UI development',        TRUE),
    ('Database Specialist', 'Database Engineer', 'PostgreSQL and data modeling',           TRUE);
