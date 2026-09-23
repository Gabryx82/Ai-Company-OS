# TASK-020 — Artefatto

**TD-08 chiuso**: il placeholder è sostituito, non evoluto, come `MIGRATION_MAP.md` M-5 chiedeva
(«REPLACE: da 4 `if` su keyword a routing verso agent realmente presenti nel registry»; «contratto
orchestrator tipizzato»). Suite **307 → 321**. Stream **`V10` → `V11`** (additiva).

## Rotture dichiarate
- `POST /api/orchestrator` **rimosso** → `404`. Era un placeholder che restituiva nomi di agenti
  inesistenti (`docs/RUNNING.md` lo diceva «da sostituire, non da usare»).
- `AgentResponse` guadagna `model` (additivo). `PUT /api/agents/{id}` senza `model` lo azzera: è la
  semantica di sostituzione del `PUT`, e al momento dell'introduzione nessun agente ne aveva uno.

## Verifica per mutazione
| # | Mutazione | Esito |
|---|---|---|
| A1 | agenti inattivi suggeriti | rosso |
| A2 | solo corrispondenza esatta | rosso (test unitario) |
| A3 | la run ignora il modello dell'agente | rosso |
| A4 | il `PUT` non sostituisce il modello | rosso |
| A5 | niente spareggio per id | rosso (2) |

## Smoke test end-to-end reale (2026-09-23) — il primo del blocco
Clone fresco del database reale (`aicompany_smoke` ← `aicompany`, a `V3`); jar in `dev` e AI Engine
avviati. Migrazioni `V1→V11` applicate al clone, **dati reali preservati** (il task `ok`/`LOW` e i
tre agenti del seed). Flusso: agente 1 → `PUT` con `model: ollama:llama3.2:3b`; task nuovo; routing;
assegnazione; lancio → `202 QUEUED`; task `IN_PROGRESS`; run **`SUCCEEDED`**, `servedModel
ollama:llama3.2:3b`, 143 → 263 token, 24,4 s, output reale del modello (un design dell'endpoint).

**Lo smoke test ha trovato un difetto che nessun test aveva visto**: il routing suggeriva il
database specialist per `POST /api/projects/{id}/archive`, perché `post` (4 lettere) è prefisso di
`postgresql`. Soglia del prefisso portata a **5**, e il caso pinnato in `AgentRouterTermsTest`.

## LOW
- L-1 Il routing è lessicale: sinonimi e lingue miste non si riconoscono.
- L-2 Il modello dell'agente non è verificato contro `/v1/models` al salvataggio: un modello
  inesistente si scopre alla prima run (fallita con `unknown-model`).
