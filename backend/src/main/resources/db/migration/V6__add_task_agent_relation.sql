-- Task -> Agent assignment. The second edge in the domain graph, and the one
-- that lets the Company OS say who works on what.
--
-- The three decisions are the ones V3 made for project_id, and they hold here
-- for the same reasons (ADR-010 section 5).
--
-- Nullable: every task that exists has no agent, and there is no correct agent
-- to point those rows at. NULL says one true thing about them -- not yet
-- assigned. Inventing a synthetic "Unassigned" agent to satisfy NOT NULL would
-- leave a row nobody can deactivate and every later feature has to special-case.
--
-- ADD COLUMN with no DEFAULT is metadata-only on PostgreSQL: instant on a large
-- tasks table, and it cannot half-finish.
ALTER TABLE tasks ADD COLUMN agent_id BIGINT;

-- Referential integrity belongs to the database (ADR-004 section 5): the
-- application check makes the error readable, the constraint makes it true.
--
-- No ON DELETE clause, so NO ACTION applies, and that is the decision rather
-- than an omission. An agent is deactivated, never deleted (ADR-004 section 3,
-- ADR-008). CASCADE would make a delete outside the application destroy the work
-- silently; SET NULL would silently orphan it. NO ACTION means the database
-- refuses while tasks still point there, and that refusal is the conversation we
-- want to have.
ALTER TABLE tasks
    ADD CONSTRAINT tasks_agent_id_fkey
    FOREIGN KEY (agent_id) REFERENCES agents (id);

-- "Give me the tasks of this agent" is the read the relation exists for, and
-- PostgreSQL does not index the referencing side of a foreign key on its own.
CREATE INDEX tasks_agent_id_idx ON tasks (agent_id);
