-- Operator review of 2026-09-25, two catalog corrections on databases created
-- before them. The catalog files only insert missing keys (ADR-018 §4), so an
-- existing row keeps the old values unless a migration moves it.

-- 1. Open WebUI is the Docker container "open-webui" on localhost:3000 -- the one
--    3D Omniverse embeds in its ChatLLM tab and creates with its docker-compose --
--    not the desktop app's server on 8080, which only starts from inside the app.
--    Only a row still on the old default moves: an entry the operator edited stays.
UPDATE software
SET url               = 'http://localhost:3000',
    health_url        = 'http://localhost:3000/health',
    executable        = 'docker',
    executable_args   = E'start\nopen-webui',
    app_id            = NULL,
    configuration     = 'Container Docker «open-webui» su localhost:3000: lo stesso che 3D Omniverse incorpora nella scheda ChatLLM e crea con il suo docker-compose. Si avvia con «docker start open-webui»; serve Docker Desktop attivo.',
    updated_at        = now()
WHERE key = 'open-webui'
  AND url = 'http://localhost:8080';

-- 2. The operator does not use pay-per-token APIs: the "Claude Opus 5 (API)" entry
--    goes. Claude stays reachable through the subscription (claude-subscription:default,
--    Claude Code). An agent still bound to it keeps its model and the entry stays.
DELETE FROM llm_models
WHERE key = 'anthropic:claude-opus-5'
  AND NOT EXISTS (SELECT 1 FROM agents WHERE agents.model = 'anthropic:claude-opus-5');
