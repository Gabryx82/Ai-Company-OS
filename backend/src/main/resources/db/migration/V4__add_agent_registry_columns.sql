-- Agent registry: bring the oldest table up to the standard every other entity
-- already meets.
--
-- Purely additive. Two columns and one index; nothing is dropped, nothing is
-- rewritten, no table is created. ADR-008 §3.

-- Timestamps with a zone, for the reason ADR-004 §7 gave for projects: an
-- Instant written to a timestamp WITHOUT time zone loses its offset silently,
-- and the value that comes back is plausible and wrong. A test asserts the
-- column type, because Hibernate's validate mode does not reliably catch this.
--
-- Added nullable, backfilled, then constrained. Doing it in that order means the
-- migration works on a populated table without a DEFAULT that would then linger
-- as part of the schema.
ALTER TABLE agents ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE agents ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

-- ADR-005 §2 refused to write values into pre-existing rows, and this is not a
-- contradiction of it. There, no correct value existed: no project was the right
-- one for a task created before projects, so inventing one would have asserted
-- something false. Here the value exists and is true -- those rows were created
-- before now -- and now() is the closest available approximation of a real fact.
--
-- It is an approximation and it should be read as one: an agent from the
-- development seed will look as though it was created at migration time. That is
-- a loss of precision, not an invention.
UPDATE agents SET created_at = now(), updated_at = now()
 WHERE created_at IS NULL OR updated_at IS NULL;

ALTER TABLE agents ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE agents ALTER COLUMN updated_at SET NOT NULL;

-- And a database default, which ADR-004 §7 deliberately did NOT give projects.
--
-- That decision was about the application path and still holds there: the entity
-- sets both timestamps in @PrePersist, so the object in memory matches the row
-- without a round trip, and nothing here changes that.
--
-- What the default is for is the writers that are not the application. The
-- development seed is one of them, and it is a versioned migration that has
-- already been applied to existing databases -- editing it to add two columns
-- would change its checksum and break Flyway validation on every volume that
-- has run it. A manual INSERT in psql is another. Without a default, those
-- writers stop working the moment the columns become NOT NULL.
--
-- Found by the suite: V4 without this broke the dev seed stream outright.
ALTER TABLE agents ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE agents ALTER COLUMN updated_at SET DEFAULT now();

-- Uniqueness belongs to the database, for the reason ADR-004 §5 gave: the check
-- in the service makes the error readable, the index makes it true. Two
-- concurrent requests can both pass a service-level check and only this stops
-- the second one.
--
-- On lower(name), and the service uses the same normalisation explicitly --
-- lower(name) = lower(:name), never the derived IgnoreCase form, which generates
-- upper() and is not the inverse of lower() in PostgreSQL.
--
-- Declared risk: on a database that already holds two agents whose names differ
-- only by case, this migration FAILS. That is the correct behaviour. The
-- alternative would be to pick a survivor in silence, and whoever is in that
-- position needs to make that choice themselves -- a migration that stops is the
-- conversation that makes them.
CREATE UNIQUE INDEX agents_name_unique_idx ON agents (lower(name));
