# TASK-002 — Core Domain Model & Project Registry Foundation

## Owner
Claude Code / Opus 5

## Reviewer
Codex (review differenziale)

## Baseline
- Branch di lavoro: `task-002-project-registry-foundation`, creato da `master`.
- HEAD all'inizio: `32174a1` — `docs(project-state): record TASK-001 as completed and merged, prepare TASK-002`.
- Nessun remote configurato. Nessun merge, nessun push.

## Objective
Introdurre il primo dominio reale del Company OS: `Project`, persistito su PostgreSQL,
pronto a diventare il contenitore logico di task, agenti, documenti, framework, template,
integrazioni e memoria.

«Pronto a diventare» è il vincolo di scope: la tabella e il ciclo di vita esistono, ma
**nessuna entità esistente viene collegata a `Project` in questa task**. `Task` resta
invariata.

## In scope
1. Entità `Project` con ciclo di vita esplicito.
2. Enum di dominio `ProjectStatus` = `{ACTIVE, ARCHIVED}`, insieme chiuso.
3. Migrazione `V2__create_projects.sql` nello stream di schema.
4. `ProjectRepository`, `ProjectService`, `ProjectController`.
5. DTO di ingresso e uscita con Bean Validation.
6. CRUD essenziale: create, list (con filtro di stato), read, update.
7. **Archiviazione al posto della cancellazione fisica**: `DELETE` non è mappato,
   `archive` / `restore` sono transizioni esplicite e verificate.
8. Gestione errori a `ProblemDetail` **limitata al modulo project**: `400`, `404`, `409`.
9. Test automatici su PostgreSQL reale.
10. Documentazione, ADR-004, artefatti di task, aggiornamento di `PROJECT_STATE.md`.

## Fuori scope (vincolo esplicito dell'utente)
Frontend; Agent Registry; Skills / Rules / Tools; servizio Python/AI; Memory Graph;
integrazioni esterne.

Fuori scope anche, per coerenza con la baseline:
- relazione `Task` → `Project` e migrazione dei dati esistenti;
- enum su `Task.status` / `Task.priority` (restano stringhe libere);
- `GET /api/tasks/{id}` (LOW della review TASK-001, ancora aperto);
- rilievi R3, R5, R6, R7 della review TASK-001;
- autenticazione, CORS, CI.

## Decisioni prese all'avvio
| Domanda | Decisione |
|---|---|
| Valori di `status` | Insieme chiuso `ACTIVE`, `ARCHIVED`. Nessuna macchina a stati più ampia: le uniche transizioni legali sono `ACTIVE → ARCHIVED` e `ARCHIVED → ACTIVE` |
| `Task` appartiene a un `Project`? | **No, non in questa task.** Nessuna colonna aggiunta a `tasks`, nessuna migrazione di dati |
| Identificatore | `BIGINT` identity, come `agents` e `tasks`. Nessuno slug pubblico: rimandato |
| Unicità del nome | Enforced dal database con indice unico funzionale su `lower(name)` |

## Acceptance criteria
- **AC-1** — `V2` si applica su un database già a `V1`, in dev, test e prod, senza toccare
  `agents` e `tasks`.
- **AC-2** — `POST /api/projects` valido → `201` + header `Location`; il progetto nasce `ACTIVE`.
- **AC-3** — Body invalido (nome assente, vuoto o troppo lungo) → `400`, niente persistito.
- **AC-4** — Nome duplicato, anche con maiuscole diverse → `409`, un solo record.
- **AC-5** — `GET /api/projects/{id}` inesistente → `404` con `ProblemDetail`.
- **AC-6** — `POST /{id}/archive` → stato `ARCHIVED`; riarchiviare → `409`; il record esiste ancora.
- **AC-7** — `POST /{id}/restore` su un progetto archiviato → `ACTIVE`; su uno attivo → `409`.
- **AC-8** — `DELETE /api/projects/{id}` → `405`: non esiste cancellazione fisica via API.
- **AC-9** — `GET /api/projects?status=ARCHIVED` restituisce solo gli archiviati; valore di
  stato non riconosciuto → `400`.
- **AC-10** — Il database rifiuta da solo nome nullo e stato fuori dall'insieme chiuso.
- **AC-11** — `createdAt` e `updatedAt` valorizzati alla creazione; `updatedAt` avanza a ogni
  modifica, `createdAt` no.
- **AC-12** — Invarianti TASK-001 preservate: Hibernate `validate`, seed dev in stream
  separato, `POST /api/tasks {}` → `400`, nessun H2.
- **AC-13** — `./mvnw -B clean test` verde su PostgreSQL reale.

## Safety
Vietati: force push, reset/rebase distruttivi, cancellazione di branch, riscrittura della
storia Git, merge su `master`, push, configurazione di remote, commit di segreti o output di
build, cancellazione del volume dati locale.

## Required outputs
- `tasks/TASK-002/{TASK.md,CONTEXT.yaml,IMPLEMENTATION.md,ARTIFACT.md,HANDOFF.md}`
- `docs/adr/ADR-004-project-registry-and-archival-lifecycle.md`
- Aggiornamento di `.company-os/PROJECT_STATE.md`

## Final constraint
Fermarsi al termine di TASK-002. **Non iniziare TASK-003.**
