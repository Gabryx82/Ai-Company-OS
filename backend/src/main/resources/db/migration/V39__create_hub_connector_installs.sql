-- PHASE 32 (TD-90). The connectors the Knowledge Hub installed from a catalog (the
-- MCP Registry), per application and name, with the version installed: what the
-- update check compares with the catalog's latest. No value the operator gave is
-- kept -- they went to the application only.
--
-- Additive: a new table.
CREATE TABLE hub_connector_installs (
    app           VARCHAR(32)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    provider      VARCHAR(40)  NOT NULL,
    reference     VARCHAR(300) NOT NULL,
    version       VARCHAR(120),
    installed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (app, name)
);
