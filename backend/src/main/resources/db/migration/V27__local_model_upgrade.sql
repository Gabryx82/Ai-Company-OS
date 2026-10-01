-- PHASE 29 (ADR-034). The local model upgrade, on databases created before it. The
-- catalog files only insert missing keys (ADR-018 §4), so an existing row keeps its
-- old values unless a migration moves it -- and only a row still on the old values
-- moves: an entry the operator edited stays as the operator left it.
--
-- Measured on the operator's machine (Intel Core Ultra 7 258V, 32 GB, Ollama 0.33.3
-- on CPU), 2026-09-30, same prompts for every model, machine-graded.

-- 1. DeepSeek Coder V2 16B is replaced by Qwen 3.5 9B for code: 4/6 against 6/6 on
--    code, 0/4 against 4/4 on reasoning, 3/5 against 5/5 on structured output, no tool
--    calling. It stays installed -- the rollback, and still 3D Omniverse's bpy worker,
--    where it outscores the newer models -- so DEPRECATED here and not RETIRED.
UPDATE llm_models
SET lifecycle   = 'DEPRECATED',
    replaced_by = 'ollama:qwen3.5:9b',
    notes       = 'Superato il 2026-09-30 (PHASE 29, ADR-034): sullo stesso banco di prova qwen3.5:9b fa codice 6/6 contro 4/6, ragionamento 4/4 contro 0/4, output strutturato 5/5 contro 3/5, e chiama gli strumenti (DeepSeek no). Resta installato: è il rollback, ed è ancora il worker bpy di 3D Omniverse, dove sugli script Blender fa 5-6/8 contro 0-2/8 dei modelli più nuovi.',
    updated_at  = now(),
    version     = version + 1
WHERE key = 'ollama:deepseek-coder-v2:16b'
  AND lifecycle = 'ACTIVE'
  AND replaced_by IS NULL;

-- 2. gemma4:e4b is the same tag with a new package: 6.6 GB instead of 9.6, and a
--    multi-token-prediction draft: 13-75% faster on code, JSON and tool calls on this CPU
--    (paired runs), 4/4 instead of 3/4 on the vision probe -- and ~2.5 GB more RAM.
UPDATE llm_models
SET size_gb    = 6.6,
    notes      = 'Multimodale: analisi di reference, screenshot e mockup del progetto. Pacchetto aggiornato il 2026-09-30 (PHASE 29): Q4_K_M con bozza MTP per la decodifica speculativa, 6,6 GB su disco invece di 9,6; misurato su questa CPU con confronto appaiato: 9,3 contro 8,2 token/s sul codice, 10,0 contro 7,5 sul JSON, 18 contro 10,5 sulle chiamate a strumenti, 4/4 invece di 3/4 nel test di visione; in RAM però circa 2,5 GB in più (6,9 contro 4,4 GB). Il pacchetto precedente resta come gemma4:e4b-legacy.',
    updated_at = now(),
    version    = version + 1
WHERE key = 'ollama:gemma4:e4b'
  AND size_gb = 9.6;

-- 3. A Test Engineer installed from its template moves to LFM2.5 8B-A1B, the new
--    template default: the same scores as qwen3.5:4b on code, reasoning, structured
--    output and tool calling, several times faster. Only an agent still on the model
--    its template gave it moves, with its baseline; one the operator rebound stays.
UPDATE agents SET
    model      = 'ollama:lfm2.5:8b',
    baseline   = replace(baseline, '"model":"ollama:qwen3.5:4b"', '"model":"ollama:lfm2.5:8b"'),
    version    = version + 1,
    updated_at = now()
WHERE origin = 'TEMPLATE'
  AND name = 'Test Engineer'
  AND role = 'Test Engineer'
  AND model = 'ollama:qwen3.5:4b'
  AND baseline LIKE '%"model":"ollama:qwen3.5:4b"%';
