# TASK-007 — Agent Registry

> **Autonomous Project Mode.** Scope deciso dall'agente, `AUTONOMOUS_CHARTER.md` §2.

## Baseline
Branch `task-007-agent-registry` da `autonomous/phase-1-foundations` (`d72cf19`).
Suite di partenza: **137 verdi**. Schema a `V3`.

## Perché adesso
`AUTONOMOUS_LOOP.md` §4, **livello 4**. I livelli 1–3 sono vuoti: nessun invariante promesso è
falso, nessun blocker, e il debito che il passo successivo toccava è stato chiuso da TASK-005 e
TASK-006.

`Agent` è la tabella più vecchia e la meno curata: cinque colonne, nessun timestamp, nessuna
unicità, nessuna validazione, un solo `GET`. Skills, Rules, Subagents, Tools, MCP Registry,
Model Gateway e Planner si appoggiano tutti a un registro di agenti che oggi non esiste.

## Decisioni
In `docs/adr/ADR-008-agent-registry.md`.

| # | Decisione |
|---|---|
| 1 | Le decisioni di ADR-004 si applicano **identiche**: niente `DELETE`, transizione illegale `409` sull'entità, unicità del nome imposta dal database, un agente fuori registro non si modifica, timestamp con fuso dall'entità |
| 2 | Il ciclo di vita resta sul **`boolean active`**. Unificarlo con l'enum di `Project` richiederebbe di eliminare una colonna, cioè una migrazione irreversibile che il charter mette dietro una decisione umana, e un'alternativa ragionevole esiste. **Divergenza dichiarata → TD-31** |
| 3 | `AgentResponse` espone `status: "ACTIVE"` / `"INACTIVE"` **derivato** dal booleano, accanto ad `active`: un vocabolario solo per il client, un solo stato nel database |
| 4 | `V4` **additiva**: `created_at`, `updated_at`, indice unico su `lower(name)`. Nessuna colonna rimossa, nessuna tabella creata |
| 5 | **Nessuna relazione** con `Project` né con `Task`: stesso vincolo che ADR-004 §1 si era dato, con la stessa motivazione |
| 6 | Il **protocollo di lock si applica**, perché L7 dice che si applica. Si riduce a L1 e L6, perché è quello che `Agent` ha |

## Invarianti

| ID | Invariante | Come si verifica |
|---|---|---|
| **I-1** | `V4` non perde nessuna riga e non elimina nessuna tabella | Il test sulle coppie consecutive di TASK-006, **senza aggiungere un caso** |
| **I-2** | Le righe preesistenti sopravvivono con i timestamp valorizzati e i valori invariati | Test di upgrade su database popolato |
| **I-3** | Due `deactivate` concorrenti sullo stesso agente: esattamente uno riesce, l'altro è `409` | Test a due thread (L1) |
| **I-4** | Un agente inattivo non si modifica: `PUT` → `409`, riattivarlo lo rende di nuovo modificabile | Test di API |
| **I-5** | L'unicità del nome è del database, non del service | Test sulla traduzione della violazione dell'indice |
| **I-6** | Ogni errore nuovo ha il suo `type` enumerato | `ApiProblemCoverageTest`, che **fallisce da solo** se manca una mappatura |
| **I-7** | `status` è derivato: non esiste nel database | Asserzione sulle colonne di `agents` |

## In scope
1. `V4__add_agent_registry_columns.sql`.
2. `Agent`: timestamp, `activate()` / `deactivate()`, `updateDetails()` che rifiuta se inattivo.
3. `status` **derivato** in `AgentResponse`. Nessuna colonna e nessun enum persistito.
4. `AgentRepository`: `findByIdForUpdate` (L1), unicità normalizzata come `ProjectRepository`.
5. `AgentService`: CRUD, traduzione della violazione del **solo** indice del nome.
6. `AgentController`: `POST`, `GET [?active=]`, `GET /{id}`, `PUT /{id}`,
   `POST /{id}/activate`, `POST /{id}/deactivate`. Nessun `DELETE`.
7. `AgentCreateRequest` / `AgentUpdateRequest` con Bean Validation.
8. Quattro `ApiProblem` nuovi e i loro handler.
9. Test: API, ciclo di vita, persistenza, upgrade `V3 → V4` su database popolato, concorrenza.
10. Artefatti, ADR-008, `PROJECT_STATE.md`.

## Fuori scope
- **Qualunque relazione** fra `Agent` e `Project` o `Task`.
- Enum di stato su `Agent` e rimozione di `active` → **TD-31**.
- Assegnazione di task ad agenti, Planner, orchestrazione, routing.
- TD-28 / TD-30 (rilevazione dell'intento stantio), TD-14 (CI), TD-04 (auth), TD-11 (CORS).
- Frontend, servizio Python/AI, paginazione.
- Merge in `master`, push, remote.

## Acceptance criteria
- **AC-1** — `V4` si applica su un `V3` popolato: agenti, task e progetti intatti, timestamp
  valorizzati. Il test delle coppie consecutive copre il passo **senza modifiche**.
- **AC-2** — `POST /api/agents` valido → `201` + `Location`; `{}` → `400` con `errors`.
- **AC-3** — Nome duplicato, anche con maiuscole diverse → `409` `agent-name-conflict`.
- **AC-4** — `GET /api/agents?active=false` filtra; senza filtro include tutti.
- **AC-5** — `GET /api/agents/{id}` inesistente → `404` `agent-not-found`.
- **AC-6** — `PUT` su un agente **inattivo** → `409` `inactive-agent-is-immutable`; dopo
  `activate` la stessa richiesta → `200`.
- **AC-7** — `deactivate` ripetuto → `409` `illegal-agent-state-transition`.
- **AC-8** — `DELETE /api/agents/{id}` → `405`, forma `ProblemDetail`.
- **AC-9** — `AgentResponse` espone `status` coerente con `active`, e `agents` **non** ha una
  colonna `status`.
- **AC-10** (I-3) — Due `deactivate` concorrenti: un `200` e un `409`.
- **AC-11** (I-5) — La violazione dell'indice del nome diventa `409`; **qualunque altra**
  violazione di integrità propaga invariata.
- **AC-12** — Suite completa verde.
- **AC-13** — Verifica per mutazione su AC-10, AC-11 e sulla guardia dell'agente inattivo.

## Chiusure attese
Nessuna. **Apre TD-31.**
