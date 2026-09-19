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
--
-- EDITED BY TD-31 (ADR-012), AND WHY THAT WAS UNAVOIDABLE
--
-- This file used to write the column "active". V8 drops that column, so leaving
-- this statement alone would have made the seed fail on every FRESH database:
-- the schema stream runs first and by the time the seed runs there is no such
-- column. A seed that cannot run is not a seed.
--
-- Editing an applied versioned migration changes its checksum, which normally
-- breaks Flyway validation on every database that already ran it -- exactly the
-- constraint V4 respected when it gave the agent timestamps a DEFAULT rather
-- than teaching this file about them. That escape does not exist here: a DEFAULT
-- cannot help a statement that NAMES a column which no longer exists.
--
-- So the checksum is realigned instead, by DevSeedFlyway.apply, which runs
-- Flyway's own repair() before migrate(). See the javadoc there for why that is
-- safe for this stream specifically and what it costs.

INSERT INTO agents (name, role, specialization, status)
SELECT seed.name, seed.role, seed.specialization, seed.status
FROM (VALUES
    ('Code Architect',      'Software Engineer', 'Backend architecture and system design', 'ACTIVE'),
    ('Frontend Developer',  'Frontend Engineer', 'React TypeScript UI development',        'ACTIVE'),
    ('Database Specialist', 'Database Engineer', 'PostgreSQL and data modeling',           'ACTIVE')
) AS seed(name, role, specialization, status)
WHERE NOT EXISTS (
    SELECT 1 FROM agents existing WHERE existing.name = seed.name
);
