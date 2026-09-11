-- Development seed data.
--
-- This migration belongs to a stream of its own. It is applied only under the
-- "dev" profile, by a second Flyway instance whose schema history table is
-- flyway_dev_seed_history (see DevSeedFlywayConfiguration). It is deliberately
-- NOT part of classpath:db/migration: mixing seed and schema in one versioned
-- stream is what made a later V2 unapplicable on a seeded database
-- (TASK-001 Codex review, R1).
--
-- Because the two streams are independent, its version number restarts at 1 and
-- never constrains the schema stream.
--
-- These three agents are demonstration data carried over from the removed
-- AgentInitializer, not reference data: TASK-000 found no requirement making
-- them part of the production dataset.
--
-- Two layers keep the seed from duplicating rows:
--   1. Flyway's own history: a versioned migration runs once per database.
--   2. The NOT EXISTS guard below, which also makes the statement safe to replay
--      during the legacy-history transition documented in docs/RUNNING.md.
-- It does not restore agents deleted by hand within the same database; that is
-- accepted for demonstration data.

INSERT INTO agents (name, role, specialization, active)
SELECT seed.name, seed.role, seed.specialization, seed.active
FROM (VALUES
    ('Code Architect',      'Software Engineer', 'Backend architecture and system design', TRUE),
    ('Frontend Developer',  'Frontend Engineer', 'React TypeScript UI development',        TRUE),
    ('Database Specialist', 'Database Engineer', 'PostgreSQL and data modeling',           TRUE)
) AS seed(name, role, specialization, active)
WHERE NOT EXISTS (
    SELECT 1 FROM agents existing WHERE existing.name = seed.name
);
