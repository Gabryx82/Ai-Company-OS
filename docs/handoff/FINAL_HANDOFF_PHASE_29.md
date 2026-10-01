# Final handoff — PHASE 29: upgrade dei modelli locali

Branch `autonomous/phase-29-local-model-upgrade` (da `75336fb`, punta di PHASE 28), non pushato.
Decisioni, misure e ragioni: [ADR-034](../adr/ADR-034-local-model-upgrade.md).

## Che cosa cambia

| Dove | Cambio |
|---|---|
| `ai-engine` | `think: false` per le risposte testuali (`AICOS_ENGINE_OLLAMA_THINK`); `num_ctx` 16384 (`AICOS_ENGINE_OLLAMA_NUM_CTX`), 4096 per deepseek e llama (`…_NUM_CTX_BY_MODEL`); fallback per modello non installato (`…_FALLBACKS`), sempre dichiarato nel campo `model` |
| catalogo | `lfm2.5:8b` (FAST, ACTIVE), `qwen3.8-gsq:27b-iq3xxs` (REASONING, CANDIDATE), `deepseek-coder-v2:16b` DEPRECATED → `qwen3.5:9b`, `gemma4:e4b` nuovo pacchetto |
| template | Test Engineer su `ollama:lfm2.5:8b` |
| migrazioni | `V27` (catalogo; Test Engineer da template non modificato), seed `V3` (Frontend Developer e Database Specialist non modificati su `qwen3.5:9b`). Solo righe ancora ai valori originali |
| 3D Omniverse (fuori da git) | ruolo `light` → `qwen3.5:4b`, alias LiteLLM `light` e `qwen-4b`, `fallback_model` per agente, `num_ctx` per agente (16K; 4K per `script`), etichetta del generatore non più scritta a mano; backup degli originali in `backup\pre-model-upgrade-2026-09-30` |
| Ollama | installati `lfm2.5:8b`, nuovo `gemma4:e4b` (il precedente come `gemma4:e4b-legacy`), `qwen3.8-gsq:27b-iq3xxs`; rimossi solo i candidati scartati `ornith:9b` e `ornith-1.5:9b`. Nessuna impostazione globale di Ollama toccata |

## Verifiche eseguite

| Verifica | Esito |
|---|---|
| Suite Java (`./mvnw test`, Testcontainers) | 518 test, 0 falliti, 1 saltato (preesistente) |
| `LocalModelUpgradeMigrationTest` sotto mutazione | 3 mutazioni delle guardie di V27 e seed V3 → 3 test rossi; ripristino → verdi |
| AI Engine | 68/68; dal vivo 14/14 |
| Control plane dal vivo, clone `aicompany_p29` del database reale | V25 → V26 → V27 + seed V3 all'avvio; 17/17 controlli, una run reale per modello, tre run in parallelo |
| Rollback | database (clone) e 3D Omniverse (copia) nei due sensi; Ollama a secco |
| 3D Omniverse | 55/55; smoke dei tre agenti locali dal vivo su cartelle temporanee |

## Prima di accettare

- Il database di sviluppo `aicompany` è alla V25 e non è stato toccato: al primo avvio su questo branch
  riceve V26 (Blueprint, PHASE 28), V27 e il seed V3. Il clone `aicompany_p29` resta in Docker
  (`DROP DATABASE aicompany_p29;` per toglierlo).
- Docker: `%LOCALAPPDATA%\Docker\run.stale-20260930` contiene i socket orfani messi da parte durante
  la fase (nulla cancellato).

## Raccomandazioni non applicate

- `OLLAMA_MAX_LOADED_MODELS=2` (variabile utente, poi riavvio di Ollama): con tre modelli diversi in
  parallelo la RAM libera è scesa a 0,1 GB (ADR-034 §3.7).
- 3D Omniverse: un promemoria delle API Blender 5 / USD nella skill `dcc-operations`. Nessun modello
  locale scrive USD corretto: tutti inventano API plausibili (ADR-034 §3.1).
- iGPU per modello (TD-67).

## Come tornare indietro

- Codice: tornare su `autonomous/phase-28-project-blueprint`.
- Modelli, 3D Omniverse, dati: `scripts\rollback-local-models.ps1` (a secco di default; `-Apply` per
  eseguire, `-RestoreGemmaPackage` per rimettere il pacchetto precedente di gemma sul tag).
