-- Task -> Project association.
--
-- V2 created the container; this migration is the first edge in the domain
-- graph. It is deliberately the smallest change that establishes the relation:
-- a nullable column, a foreign key, and the index that makes "the tasks of this
-- project" a cheap question.
--
-- Why nullable (ADR-005 §1). The tasks table is already populated in every
-- environment that has been running, and there is no correct project to point
-- those rows at: they were created before projects existed. The alternatives
-- were both worse than a nullable column -- inventing a synthetic "Unassigned"
-- project to satisfy NOT NULL leaves behind a row nobody can delete and every
-- later feature has to special-case, and deleting the pre-existing tasks throws
-- away data to make a constraint fit. NULL here means exactly one thing, and it
-- is a true statement about those rows: not yet assigned to a project.
--
-- This also keeps the migration metadata-only. ADD COLUMN with no DEFAULT does
-- not rewrite the table on PostgreSQL, so applying V3 to a large tasks table is
-- instant and cannot half-finish.
ALTER TABLE tasks ADD COLUMN project_id BIGINT;

-- Referential integrity belongs to the database, for the same reason the project
-- name uniqueness does (ADR-004 §5): the application check makes the error
-- readable, the constraint makes it true.
--
-- No ON DELETE clause, so the default NO ACTION applies, and that is the
-- decision rather than an omission. CASCADE would make deleting a project
-- silently destroy its tasks, which contradicts ADR-004 §3 -- the Company OS
-- archives projects, it does not delete them. SET NULL would silently detach
-- them. NO ACTION means that if somebody ever does reach for a DELETE outside
-- the application, the database refuses it while tasks still point there, and
-- that refusal is the conversation we want to have.
ALTER TABLE tasks
    ADD CONSTRAINT tasks_project_id_fkey
    FOREIGN KEY (project_id) REFERENCES projects (id);

-- "Give me the tasks of this project" is the read the relation exists for, and
-- PostgreSQL does not index the referencing side of a foreign key on its own.
CREATE INDEX tasks_project_id_idx ON tasks (project_id);
