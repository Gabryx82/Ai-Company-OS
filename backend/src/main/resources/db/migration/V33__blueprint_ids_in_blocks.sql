-- PHASE 32 (TD-60). A scan writes hundreds of blocks and thousands of relations;
-- with IDENTITY ids Hibernate inserts them one statement at a time (it must read
-- each id back). The entities now take their ids from a sequence in blocks of 50
-- (Hibernate's pooled optimizer), so the inserts are sent in JDBC batches.
--
-- No row is read or written. The pooled optimizer uses, for each value V the
-- sequence returns, the ids V-49 .. V: the sequence therefore restarts 50 above
-- the highest id in use, and steps by 50. An INSERT that relies on the column
-- default takes one value V itself, which no block handed to Hibernate contains.
ALTER TABLE blueprint_nodes ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE blueprint_nodes_seq INCREMENT BY 50 OWNED BY blueprint_nodes.id;
SELECT setval('blueprint_nodes_seq', COALESCE((SELECT max(id) FROM blueprint_nodes), 0) + 50, false);
ALTER TABLE blueprint_nodes ALTER COLUMN id SET DEFAULT nextval('blueprint_nodes_seq');

ALTER TABLE blueprint_edges ALTER COLUMN id DROP IDENTITY;
CREATE SEQUENCE blueprint_edges_seq INCREMENT BY 50 OWNED BY blueprint_edges.id;
SELECT setval('blueprint_edges_seq', COALESCE((SELECT max(id) FROM blueprint_edges), 0) + 50, false);
ALTER TABLE blueprint_edges ALTER COLUMN id SET DEFAULT nextval('blueprint_edges_seq');
