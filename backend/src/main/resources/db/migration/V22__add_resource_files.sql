-- PHASE 19 (ADR-026). Skills and knowledge are files the operator can read and
-- edit; the table is their index.
--
-- origin: CATALOG (shipped with AI Company OS), FILE (found in the library
-- folder), WEB (imported from a URL), USER (created in the console).
-- file_path: where the file is, relative to the library root
-- (skills/<key>/SKILL.md, knowledge/<key>.md); NULL until one exists.
-- Additive only.

ALTER TABLE harness_resources ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'CATALOG';
ALTER TABLE harness_resources ADD CONSTRAINT harness_resources_origin_check
    CHECK (origin IN ('CATALOG', 'FILE', 'WEB', 'USER'));
ALTER TABLE harness_resources ADD COLUMN file_path VARCHAR(500);
ALTER TABLE harness_resources ADD COLUMN file_synced_at TIMESTAMP WITH TIME ZONE;
