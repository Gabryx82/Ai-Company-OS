-- PHASE 32 (TD-71). An entry of the catalog AI Company OS ships (software, a skill or
-- framework of the library, a model) can be retired: it leaves the lists, and the
-- start that inserts the shipped catalog no longer inserts it again. Restoring it
-- removes the row here and puts the entry back as shipped.
--
-- Additive: a new table. The key is the catalog's own key, not a foreign key: the
-- row it names is gone while it is retired.
CREATE TABLE catalog_retirements (
    catalog     VARCHAR(16)  NOT NULL,
    key         VARCHAR(200) NOT NULL,
    retired_by  VARCHAR(120) NOT NULL,
    retired_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (catalog, key),
    CONSTRAINT catalog_retirements_catalog_check CHECK (catalog IN ('SOFTWARE', 'RESOURCE', 'MODEL'))
);
