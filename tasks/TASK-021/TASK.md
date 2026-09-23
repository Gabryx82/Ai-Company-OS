# TASK-021 — Descrizione OpenAPI e modelli dell'engine inoltrati

**Fase**: PHASE 7. **ADR**: ADR-017 §2. **Milestone**: M-3 (metà OpenAPI).

## Invarianti
- **I-1** Ogni rotta `/api` e metodo registrati compaiono nella descrizione.
- **I-2** La copia committata `docs/api/openapi.json` è byte per byte quella prodotta dal codice.
- **I-3** La descrizione richiede il token.
- **I-4** `GET /api/engine/models` inoltra la lista dell'engine senza esporne il token; un engine muto
  è `503 engine-unavailable`.

## Artefatto
| File | Ruolo |
|---|---|
| `backend/pom.xml` | `springdoc-openapi-starter-webmvc-api` 3.1.1 |
| `api/OpenApiConfiguration` | Info, schema di sicurezza bearer, le due regole che uno schema non dice |
| `run/controller/EngineController`, `run/exception/EngineUnavailableException` | L'inoltro |
| `run/engine/EngineClient#models`, `HttpEngineClient#models` | `GET /v1/models` |
| `api/ApiProblem.ENGINE_UNAVAILABLE` | Nuovo `type` |
| `docs/api/openapi.json` | Il contratto committato (23 path) |
| `OpenApiContractTest` (5) | Copertura, protezione, deriva, inoltro, `503` |

Suite **321 → 326**. Mutazioni: descrizione pubblica **rossa**; deriva di un campo di `RunResponse`
**rossa**; errore dell'engine non mappato **rosso**.

Rigenerare dopo una modifica voluta dell'API:
`./mvnw test -Dtest=OpenApiContractTest -Dopenapi.write=true`, poi `cd frontend && npm run api:types`.
