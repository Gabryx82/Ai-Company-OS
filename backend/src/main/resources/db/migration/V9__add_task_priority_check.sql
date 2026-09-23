-- TASK-016 (TD-36). The closed vocabulary of tasks.priority, in the database.
--
-- The twin of V7 for the neighbouring column, and with the same standing: not
-- additive by construction -- it applies a constraint to existing rows -- and
-- additive on this repository's data. The census (tasks/TASK-016/CENSUS.md) found
-- only 'HIGH' and 'LOW' in every source, including the real development database,
-- so this reads, writes and rewrites no row.
--
-- On a database holding any other value this migration FAILS and leaves the row
-- untouched. That is intended, and it is the precedent of V4 and V7: a stop is
-- the conversation that makes somebody decide, where a silent rewrite would be a
-- mapping invented on behalf of whoever wrote the value. MigrationStreamTest
-- exercises both outcomes.
--
-- The set is written out by hand because SQL cannot read TaskPriority; the
-- application's tests compare the two.
ALTER TABLE tasks
    ADD CONSTRAINT tasks_priority_check
    CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH'));
