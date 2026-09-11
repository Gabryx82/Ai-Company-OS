-- Test fixture, not a product migration.
--
-- Stands in for "the next schema migration somebody writes". It lives under
-- src/test/resources so it is never resolved by the application: the tests add
-- this location explicitly to prove that the schema stream can move past V1 on a
-- database that was already created and seeded.

ALTER TABLE tasks ADD COLUMN probe_marker VARCHAR(16);
