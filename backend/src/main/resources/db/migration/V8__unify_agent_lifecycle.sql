-- TD-31: the agent lifecycle stops being a boolean and becomes the closed enum
-- that Project has had since V2.
--
-- THIS MIGRATION IS DESTRUCTIVE AND IRREVERSIBLE. It drops agents.active.
--
-- The autonomous charter puts that behind a human decision (hard stop #3), and
-- ADR-008 section 2 declined to do it while a reasonable alternative existed.
-- The decision was taken explicitly by a human on 2026-09-19, scoped to exactly
-- what TD-31 describes: unify the representation, nothing else. ADR-012.
--
-- WHY IT LOSES NOTHING
--
-- ADR-008 section 2 already stated the shape of this migration and why it is
-- safe: "il backfill sarebbe senza perdita -- un booleano verso due valori e'
-- una biiezione". That is the whole argument and it is checkable:
--
--     active = TRUE   <-> status = 'ACTIVE'
--     active = FALSE  <-> status = 'INACTIVE'
--
-- Two values map to two values, both directions total and injective. No row can
-- fail to have an image and no two rows collapse into one. Nothing is invented
-- and nothing is discarded, which is why this needs no mapping decision: the
-- mapping is the one ADR-008 wrote down, and the vocabulary is the one the API
-- has been publishing all along through AgentResponse.status.
--
-- active is NOT NULL since V1, so there is no third case to decide. The backfill
-- below would still be total if it were nullable, but it is not, and a CASE with
-- an unreachable ELSE would invite somebody to believe it were.
--
-- WHY 'INACTIVE' AND NOT 'ARCHIVED'
--
-- ADR-008 section 2: a project put away and an agent switched off are not the
-- same thing, and using one word for both would be formal consistency against
-- meaning. The enum values are unified in FORM with ProjectStatus, not in
-- vocabulary.

-- 1. The new column, nullable for the length of this migration only.
ALTER TABLE agents ADD COLUMN status VARCHAR(32);

-- 2. The backfill. The bijection above, written once.
UPDATE agents SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'INACTIVE' END;

-- 3. Now it can be NOT NULL: every row has a value, and the order matters --
--    adding the column NOT NULL first would need a DEFAULT, and a DEFAULT here
--    would lie about whichever state it did not pick. V4 added a DEFAULT to the
--    agent timestamps for a reason that does not apply here: there, writers
--    outside the application omit the column; here, no writer may omit a
--    lifecycle state without somebody deciding what it means.
ALTER TABLE agents ALTER COLUMN status SET NOT NULL;

-- 4. The set is closed and owned by the database, not only by the enum -- the
--    same two guards ADR-004 section 2 gave projects, for the same reason: the
--    enum protects the application path, the constraint protects everything
--    else. Widening the set requires a migration, deliberately.
ALTER TABLE agents ADD CONSTRAINT agents_status_check CHECK (status IN ('ACTIVE', 'INACTIVE'));

-- 5. The column TD-31 exists to remove.
--
--    This is the irreversible step. Everything above is additive and could be
--    rolled back by dropping the column; from here the boolean is gone, and a
--    database that runs this cannot be returned to V7 by running SQL backwards.
--    That is what the human decision authorised.
--
--    No index is created on status, and that is deliberate rather than an
--    oversight. There was no index on active either, so adding one here would be
--    a performance change smuggled in under a representation change. Projects
--    have projects_status_idx; agents do not, and TD-31 is about the boolean
--    versus the enum, not about what is indexed.
ALTER TABLE agents DROP COLUMN active;
