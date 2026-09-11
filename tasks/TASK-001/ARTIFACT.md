# TASK-001 — ARTIFACT

## Risultato

La persistenza volatile è stata sostituita da una fondazione PostgreSQL versionata e verificata su database reale. I dati sopravvivono al riavvio dell'applicazione **e** alla ricreazione del container; lo schema è un artefatto versionato; un input invalido non raggiunge più il database.

## Acceptance criteria

| AC | Criterio | Esito | Evidenza |
|---|---|---|---|
| AC-1 | Base Git dichiarata, lavoro preesistente preservato, nessun merge su `master` | ✅ | branch `task-001-persistence-foundation` da `task-000-audit`; `master` intatto a `930f70f` |
| AC-2 | PostgreSQL via Docker Compose con volume e procedura documentata | ✅ | container healthy in ~10s, volume `aicompany_postgres_data`, `docs/RUNNING.md` |
| AC-3 | Schema creato **solo** da migrazioni; Hibernate non lo modifica | ✅ | `SchemaMigrationTest` — le uniche tabelle sono `agents`, `tasks`, `flyway_schema_history` |
| AC-4 | Nessun drift al secondo avvio; mismatch segnalato | ✅ | 2° avvio: `Schema "public" is up to date`; colonna rimossa a mano → `SchemaManagementException: missing column [priority]`, avvio bloccato |
| AC-5 | Comportamenti validi di lettura/creazione preservati | ✅ | `GET /api/agents` 200 (3 agent), `GET /api/tasks` 200, `POST` valido → 201 |
| AC-6 | `POST /api/tasks {}` rifiutato, nessuna riga persistita | ✅ | runtime: `400`; `TaskApiValidationTest` verifica anche `repository.count() == 0` |
| AC-7 | Seed con un solo proprietario, solo in dev, nessun duplicato | ✅ | `AgentInitializer` rimosso; seed in `db/dev/V1000`; dopo riavvio sempre 3 agent |
| AC-8 | Test contro PostgreSQL reale | ✅ | 17 test via Testcontainers, `BUILD SUCCESS` |
| AC-9 | Dati conservati al riavvio app **e** alla ricreazione del container | ✅ | `docker compose down` + `up` → nuovo container `f4bfee3994c1`, task e agent ancora presenti |
| AC-10 | Nessun `Project`, relazione, provider, frontend o CRUD introdotto | ✅ | diff limitato a persistenza, DTO/validazione, test e documentazione |

## Debito tecnico chiuso

| ID (TASK-000) | Descrizione | Stato |
|---|---|---|
| TD-01 | Persistenza volatile (H2 in-memory) | **Chiuso** — PostgreSQL con volume |
| TD-02 | Schema generato da Hibernate senza migrazioni | **Chiuso** — Flyway + `validate` |
| TD-03 | Nessuna validazione degli input | **Chiuso** — DTO + Bean Validation, `400` verificato |
| TD-09 | Copertura di test funzionale nulla | **Ridotto** — da 1 a 17 test su database reale; CI ancora assente |
| TD-16 | `open-in-view` non configurato | **Chiuso** — impostato a `false` |
| D-03 | `spring.h2.console.enabled` inerte | **Chiuso** — rimosso con H2 |
| TD-05 | Entità JPA esposte come contratto API | **Parziale** — DTO su `agents` e `tasks`; `orchestrator` invariato (fuori scope) |
| TD-10 | Violazione di layering (`AgentInitializer` in `repository`) | **Chiuso** — classe rimossa |

Restano aperti e fuori scope: TD-04 (sicurezza), TD-07 (gestione errori), TD-08 (`MasterOrchestrator`), TD-11 (CORS), TD-12/TD-13 (dominio), TD-14 (CI), TD-15 (Lombok inutilizzato), TD-17/TD-18 (`README.md`).

## Decisioni registrate

- **ADR-001** — Spring Boot control plane, servizio Python per l'AI in seguito. *Accettata* (approvazione utente).
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway, verificato su database reale. *Accettata*.

## Cambi di contratto deliberati

1. `POST /api/tasks` risponde **`201`** con header `Location` invece di `200`. Endpoint senza client attivi.
2. `status` e `priority` sono **obbligatori**. L'alternativa — default lato server — avrebbe inventato semantica di dominio che la review ha esplicitamente rinviato.
3. Le risposte espongono DTO invece delle entità. Forma dei campi invariata.

## Correzioni della review Codex recepite

| Rilievo | Recepimento |
|---|---|
| R1 — stato Git obsoleto | Baseline dichiarata in `TASK.md` e `CONTEXT.yaml`; `PROJECT_STATE.md` corretto; rimossa l'affermazione non verificata «unica copia sul disco» |
| R2 — test dopo i cambiamenti che dovrebbero proteggere | Test e validazione **dentro** questa task, non rinviati a M-4 |
| R4 — baseline vs migrazione iniziale | `V1` **crea** lo schema su database vuoto, con tipi PostgreSQL |
| R4 — verifica del volume | Verificata la ricreazione del container, non solo il riavvio dell'app |
| R4 — idempotenza del seed | Affidata alla storia Flyway; `count()==0` eliminato |
| R4 — due proprietari del seed | `AgentInitializer` rimosso |
| R5 — cardinalità di dominio assunte | Nessuna relazione o enum introdotti |
| Correzione 1 — creator Jackson non dimostrato | Spiegazione causale non riproposta |
| Correzione 2 — ID nel body | Il DTO di creazione non ha componente `id`; test dedicato |

## Fuori scope, non toccato

Frontend, LangGraph, servizio Python, Agent Registry, autenticazione, `MasterOrchestrator`, CORS, gestione errori, entità `Project`, refactoring generale, TASK-002.

## Stato
TASK-001 **completato**. TASK-002 **non avviato**.
