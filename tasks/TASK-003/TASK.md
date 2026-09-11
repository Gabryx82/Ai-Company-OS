# TASK-003 — Task → Project Association Foundation

## Owner
Claude Code / Opus 5

## Reviewer
Codex (review differenziale)

## Baseline
- Branch di lavoro: `task-003-task-project-association`, creato da `master`.
- HEAD all'inizio: `c73fa39` — `docs(project-state): record TASK-002 merged`.
- Nessun remote configurato. Nessun merge, nessun push.

## Objective
Introdurre la relazione persistente **`Task` → `Project`**: la prima arco reale del grafo di
dominio del Company OS.

Il vincolo di scope è la parola *foundation*: la relazione esiste, è imposta dal database ed
è governata da regole dichiarate, ma **non produce ancora nessun effetto automatico** di
`archive`/`restore` sui task.

## Le tre domande sciolte prima di implementare
ADR-004 §1 aveva rimandato esplicitamente tre decisioni. Sono il prerequisito di questa task
e sono state prese prima di scrivere codice. Motivazioni complete in
`docs/adr/ADR-005-task-project-association.md`.

| # | Domanda | Decisione |
|---|---|---|
| 1 | `project_id` nullable in questa fase? | **Sì.** Non esiste un valore corretto per le righe preesistenti, e un progetto sintetico «Unassigned» di backfill sarebbe un workaround permanente e indelebile. `NULL` significa una cosa sola e vera: non ancora assegnato (ADR-005 §1) |
| 2 | Come si migrano i task già esistenti? | **Non si migrano.** `V3` non contiene nessuna `UPDATE`. Restano `project_id NULL`, lo stato è visibile nel contratto (`projectId: null`) ed è recuperabile con `PUT /api/tasks/{id}/project` (ADR-005 §2) |
| 3 | Cosa accade associando un task a un progetto `ARCHIVED`? | **`409 Conflict`.** Un contenitore fuori dal registro operativo non riceve lavoro nuovo, altrimenti l'archiviazione è decorativa. Reversibile dal chiamante: `restore` e la stessa richiesta passa (ADR-005 §3) |

## In scope
1. Migrazione `V3__add_task_project_relation.sql`: colonna nullable, chiave esterna, indice.
2. Mapping JPA `@ManyToOne(LAZY)` unidirezionale su `Task`, con la regola sull'entità.
3. `POST /api/tasks` accetta un `projectId` opzionale.
4. `PUT /api/tasks/{id}/project` per assegnare o spostare un task esistente.
5. `GET /api/projects/{projectId}/tasks` per leggere i task di un progetto.
6. `TaskResponse` espone `projectId`.
7. Validation e `ProblemDetail` **solo** per le eccezioni nuove: `404` task, `404` progetto,
   `409` progetto archiviato.
8. Test su PostgreSQL reale, incluso l'upgrade incrementale `V2 → V3` su database popolato.
9. ADR-005, artefatti di task, aggiornamento di `.company-os/PROJECT_STATE.md`.

## Fuori scope (vincolo esplicito dell'utente)
- **Cascata `archive`/`restore` da `Project` a `Task`.**
- Cancellazioni automatiche di qualunque tipo.
- **TD-19** (controllo di concorrenza su `Project`): non va risolto finché `archive`/`restore`
  non produce effetti sulle entità figlie. Questa task non gliene dà — vedi ADR-005 §4.
- Planner, agent assignment, graph/orchestration, TASK-004.
- Enum di dominio su `Task.status` / `Task.priority`: restano stringhe libere.
- `GET /api/tasks/{id}` (LOW della review TASK-001, ancora aperto).
- TD-07 / advice globale: il contratto di errore dei due endpoint preesistenti non cambia.
- Frontend, Agent Registry, servizio Python/AI, integrazioni esterne, autenticazione, CI.

## Acceptance criteria
- **AC-1** — `V3` si applica su un database già a `V2` e popolato, senza toccare le righe
  esistenti: i task preesistenti sopravvivono con `project_id NULL`, agenti e progetti
  intatti. Verificato da un test automatico **e** sul volume di sviluppo reale.
- **AC-2** — `POST /api/tasks` senza `projectId` → `201`, task non assegnato. Il contratto
  preesistente continua a funzionare invariato.
- **AC-3** — `POST /api/tasks` con `projectId` valido → `201` e `projectId` nella risposta.
- **AC-4** — `POST /api/tasks` con `projectId` inesistente → `404`, **nessun task creato**.
- **AC-5** — `PUT /api/tasks/{id}/project` assegna un task preesistente; ripeterlo con lo
  stesso progetto è idempotente (`200`); con un progetto diverso lo sposta.
- **AC-6** — Assegnare a un progetto `ARCHIVED` → `409`, task invariato. Dopo `restore` la
  stessa richiesta → `200`.
- **AC-7** — `GET /api/projects/{id}/tasks` restituisce i soli task di quel progetto, dal più
  vecchio; progetto inesistente → `404`, non lista vuota; progetto archiviato → `200`.
- **AC-8** — `archive` di un progetto **non** cambia né stacca i suoi task.
- **AC-9** — Il database rifiuta un `project_id` che non risolve, e rifiuta di cancellare un
  progetto che ha ancora task.
- **AC-10** — La forma del `400` di `POST /api/tasks {}` è quella di prima.
- **AC-11** — Suite completa verde contro PostgreSQL reale.

## Vincoli operativi
- Nessun merge, nessun push, nessun remote, nessuna riscrittura di storia.
- Il volume di sviluppo `aicompany_postgres_data` non va cancellato: l'upgrade va verificato
  su di esso, e le righe di smoke test vanno rimosse al termine.
