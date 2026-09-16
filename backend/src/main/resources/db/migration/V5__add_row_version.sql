-- ADR-009: a persistent row version, so that "the state the caller last saw"
-- has a value to compare against.
--
-- Additive: no column removed, no table created, no constraint applied to data
-- that already exists.
--
-- DEFAULT 0 is not cosmetic. Two kinds of writer never name this column: the
-- rows that already exist, and the development seed in db/dev/V1 -- an
-- already-applied migration whose checksum cannot change, so it cannot be
-- taught about a column added later. The default covers both. That lesson comes
-- from V4, where the suite found it and the plan had not.
--
-- NOT NULL because Hibernate maps @Version to a primitive: a null on a pre-V5
-- row would fail to load rather than quietly default.
ALTER TABLE tasks    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE projects ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agents   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
