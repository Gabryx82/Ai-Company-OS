-- Test fixture, not a product migration.
--
-- Stands in for "the next schema migration somebody writes". It lives under
-- src/test/resources so it is never resolved by the application: the tests add
-- this location explicitly to prove that the schema stream can move past its
-- current head on a database that was already created and seeded.
--
-- The version number is constrained from both sides and must stay that way:
--   * above the head of classpath:db/migration, or it would collide with a real
--     migration -- which is exactly what happened when it was numbered V2 and
--     TASK-002 introduced a real V2;
--   * below 1000, so the legacy fixture can still reproduce the R1 failure of a
--     schema history that has run ahead of the resolved migrations.

ALTER TABLE tasks ADD COLUMN probe_marker VARCHAR(16);
