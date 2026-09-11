-- Development seed data. Applied only under the "dev" profile, which adds
-- classpath:db/dev to spring.flyway.locations.
--
-- These three agents are demonstration data carried over from the removed
-- AgentInitializer, not reference data: TASK-000 found no requirement making
-- them part of the production dataset.
--
-- Idempotency comes from Flyway's schema history (a versioned migration runs
-- once per database), not from an application-level emptiness check.
--
-- A database seeded this way must not be promoted to production: the prod
-- profile does not resolve this migration and Flyway would report it as
-- applied-but-missing.

INSERT INTO agents (name, role, specialization, active) VALUES
    ('Code Architect',      'Software Engineer', 'Backend architecture and system design', TRUE),
    ('Frontend Developer',  'Frontend Engineer', 'React TypeScript UI development',        TRUE),
    ('Database Specialist', 'Database Engineer', 'PostgreSQL and data modeling',           TRUE);
