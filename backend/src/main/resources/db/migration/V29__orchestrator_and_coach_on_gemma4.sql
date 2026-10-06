-- PHASE 30, on the operator's request of 2026-10-07: the Master Orchestrator and the
-- Learning Coach run on Gemma 4 E4B (ollama:gemma4:e4b) instead of Qwen 3.5 9B.
--
-- The templates already say so (catalog/agent-templates.json); this moves the agents
-- installed from them before, the way V27 moved the Test Engineer. Only an agent still
-- on the model its template gave it moves, with its baseline -- so it does not read as
-- "modified" -- and one the operator rebound stays as the operator left it.
--
-- Why it holds (ADR-034, measured on this machine): gemma4:e4b scored as qwen3.5:9b on
-- code (6/6), reasoning (4/4), structured output (5/5) and tool calls (4/4), is faster on
-- code and JSON, and reads images; it is already the orchestrator of 3D Omniverse.

UPDATE agents SET
    model      = 'ollama:gemma4:e4b',
    baseline   = replace(baseline, '"model":"ollama:qwen3.5:9b"', '"model":"ollama:gemma4:e4b"'),
    version    = version + 1,
    updated_at = now()
WHERE origin = 'TEMPLATE'
  AND name IN ('Master Orchestrator', 'Learning Coach')
  AND role IN ('Master Orchestrator', 'Learning Coach')
  AND model = 'ollama:qwen3.5:9b'
  AND baseline LIKE '%"model":"ollama:qwen3.5:9b"%';
