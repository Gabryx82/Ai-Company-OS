-- PHASE 37 (ADR-043). AI Company OS is where agents are made; the tools around it
-- -- OpenClaw, Open WebUI, LiteLLM, LM Studio -- receive a copy of each one, in the
-- shape that tool has for it: an OpenClaw agent, an Open WebUI model, a LiteLLM
-- alias, an LM Studio preset.
--
-- One row per agent and tool says what stands there: the identifier the tool knows
-- it by, a fingerprint of what was written (so that an agent changed since is seen
-- as behind without asking the tool), and whether AI Company OS made it. `owned`
-- is false for an agent the tool already had and the operator brought in: that one
-- is the tool's, and nothing of it is ever rewritten or removed from here.
--
-- Additive: one new table. Its rows go with their agent; what was published stays
-- in the tool until the operator removes it there or from the console.

CREATE TABLE agent_publications (
    agent_id      BIGINT        NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    tool          VARCHAR(32)   NOT NULL,
    external_id   VARCHAR(200)  NOT NULL,
    fingerprint   VARCHAR(64),
    owned         BOOLEAN       NOT NULL DEFAULT TRUE,
    published_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_by  VARCHAR(64),

    CONSTRAINT agent_publications_pkey PRIMARY KEY (agent_id, tool),
    CONSTRAINT agent_publications_tool_check CHECK (tool ~ '^[a-z][a-z0-9-]{1,31}$'),
    CONSTRAINT agent_publications_external_id_check CHECK (length(btrim(external_id)) > 0),
    -- One agent of a tool is one agent here: two rows cannot claim the same one.
    CONSTRAINT agent_publications_external_unique UNIQUE (tool, external_id)
);
