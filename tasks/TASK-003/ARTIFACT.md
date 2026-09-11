# TASK-003 — ARTIFACT

## File introdotti

| File | Contenuto |
|---|---|
| `backend/src/main/resources/db/migration/V3__add_task_project_relation.sql` | Colonna `project_id` nullable, chiave esterna `tasks_project_id_fkey`, indice `tasks_project_id_idx` |
| `backend/src/main/java/.../task/controller/ProjectTaskController.java` | `GET /api/projects/{projectId}/tasks` |
| `backend/src/main/java/.../task/controller/TaskExceptionHandler.java` | `ProblemDetail` per le sole eccezioni nuove |
| `backend/src/main/java/.../task/dto/TaskProjectAssignmentRequest.java` | Body di `PUT /api/tasks/{id}/project` |
| `backend/src/main/java/.../task/exception/TaskNotFoundException.java` | `404` |
| `backend/src/main/java/.../task/exception/ArchivedProjectCannotReceiveTasksException.java` | `409` |
| `backend/src/test/java/.../task/TaskProjectAssociationApiTest.java` | 20 test di contratto HTTP |
| `backend/src/test/java/.../persistence/TaskProjectRelationPersistenceTest.java` | 7 test di persistenza su PostgreSQL reale |
| `docs/adr/ADR-005-task-project-association.md` | Le tre decisioni rimandate da ADR-004 §1, più cinque conseguenti |
| `tasks/TASK-003/{CONTEXT.yaml,TASK.md,IMPLEMENTATION.md,ARTIFACT.md,HANDOFF.md}` | Artefatti di task |

## File modificati

| File | Modifica |
|---|---|
| `task/model/Task.java` | `@ManyToOne(LAZY) project`, `assignTo(Project)` con la regola sull'archiviato, `getProjectId()` |
| `task/dto/TaskCreateRequest.java` | `projectId` opzionale, `@Positive` |
| `task/dto/TaskResponse.java` | `projectId` in uscita |
| `task/repository/TaskRepository.java` | `findAllByProjectId`, `findAllWithProject`, entrambe con `join fetch` |
| `task/service/TaskService.java` | `@Transactional`, restituisce DTO, `create` con progetto, `assignToProject`, `findAllByProject` |
| `task/controller/TaskController.java` | `PUT /{id}/project`; `create` passa il `projectId` |
| `test/.../persistence/SchemaMigrationTest.java` | Versioni `("1","2","3")`; `project_id` nullable; FK con `delete_rule = NO ACTION`; indice |
| `test/.../persistence/DevSeedMigrationTest.java` | Versioni `("1","2","3")` |
| `test/.../persistence/MigrationStreamTest.java` | Test dell'upgrade `V2 → V3` su database popolato, con `Flyway.target` |
| `test/.../project/ProjectApiTest.java` | `@BeforeEach` cancella prima i task: la FK lo impone |
| `test/.../project/ProjectPersistenceTest.java` | idem |

Diffstat: **11 file modificati, 10 nuovi** — `389 insertions(+), 25 deletions(-)` sui
modificati.

## Migrazione

```
V1__create_agents_and_tasks.sql
V2__create_projects.sql
V3__add_task_project_relation.sql   <- nuova
```

Stream di schema invariato per il resto. Lo stream di seed (`db/dev`, storia
`flyway_dev_seed_history`) non è toccato.

## Contratto API — differenziale

### Modificato, in modo additivo

```
POST /api/tasks
  body: {"title","description","status","priority", "projectId"?}   <- projectId nuovo, opzionale
  201 + Location + {"id","title","description","status","priority","projectId"}
  404 se projectId non risolve      (nessun task creato)
  409 se il progetto e' ARCHIVED    (nessun task creato)
  400 invariato, forma di errore invariata

GET /api/tasks
  200 [{..., "projectId": <id|null>}]                               <- projectId nuovo
```

### Nuovo

```
PUT /api/tasks/{id}/project
  body: {"projectId": <id>}          obbligatorio, positivo
  200 + TaskResponse                 assegna, sposta, o conferma (idempotente)
  400 projectId assente o non positivo
  404 {"title":"Task not found"}     l'id del task non risolve
  404 {"title":"Project not found"}  l'id del progetto non risolve
  409 {"title":"Archived project cannot receive tasks"}

GET /api/projects/{projectId}/tasks
  200 [TaskResponse...]              solo i task di quel progetto, dal piu' vecchio
                                     lista vuota se non ne ha
                                     200 anche se il progetto e' ARCHIVED
  404 {"title":"Project not found"}
```

### Non modificato

`GET/POST/PUT /api/projects`, `/archive`, `/restore`, `DELETE` → `405`, `/api/agents`, e la
forma del `400` di `POST /api/tasks`.

## Verifiche eseguite

### Suite completa

```
./mvnw -B clean test  ->  Tests run: 107, Failures: 0, Errors: 0, Skipped: 0  -  BUILD SUCCESS
```

77 test prima, **107** adesso. I 30 nuovi: 20 di contratto HTTP, 7 di persistenza, 2 di
schema (`taskProjectRelationIsEnforcedByTheDatabase`, `tasksAreIndexedByProject`), 1 di
upgrade incrementale.

| Classe | Test |
|---|---|
| `TaskProjectAssociationApiTest` | 20 |
| `TaskProjectRelationPersistenceTest` | 7 |
| `SchemaMigrationTest` | 9 (erano 7) |
| `MigrationStreamTest` | 8 (erano 7) |
| `ProjectApiTest` | 26 |
| `ProjectPersistenceTest` | 10 |
| `ProjectLifecycleTest` | 9 |
| `ProjectServiceConflictTest` | 4 |
| `TaskApiValidationTest` | 6 |
| `TaskPersistenceTest` | 4 |
| `DevSeedMigrationTest`, `BackendApplicationTests` | il resto |

### Upgrade incrementale V2 → V3, automatico

`MigrationStreamTest.taskProjectRelationIsAddedToAPopulatedV2Database`, contro PostgreSQL
reale in Testcontainers, in uno schema isolato:

- database fermato a `V2` con `Flyway.target("2")`, colonna `project_id` assente;
- seed applicato, un task e un progetto scritti;
- `migrate()` → **1 sola** migrazione eseguita, storia `1, 2, 3`;
- task preesistente intatto (`title`, `description`) e `project_id NULL`; zero righe con
  `project_id` valorizzato; agenti del seed intatti; progetto preesistente intatto;
- il task preesistente si assegna al progetto preesistente;
- `project_id = 987654` → `DataIntegrityViolationException`;
- lo stream avanza ancora oltre `V3` (fixture `V900`).

### Upgrade reale sul volume di sviluppo

Volume `aicompany_postgres_data`, **non cancellato**. Stato prima:

```
 version |             script              | success
---------+---------------------------------+---------
 1       | V1__create_agents_and_tasks.sql | t
 2       | V2__create_projects.sql         | t

 id | title | status      tasks: 1 riga     projects: 0 righe     agents: 3
  1 | ok    | OPEN
```

Avvio in profilo `dev`: Flyway applica `V3`, Hibernate in `validate` non protesta,
l'applicazione parte. Stato dopo:

```
 3       | V3__add_task_project_relation.sql | t

 project_id | bigint |  | (nullable)
Indexes:  "tasks_project_id_idx" btree (project_id)
Foreign-key constraints:
  "tasks_project_id_fkey" FOREIGN KEY (project_id) REFERENCES projects(id)

 id | title | status | project_id
  1 | ok    | OPEN   |              <- sopravvissuto, senza progetto      agents: 3
```

### Smoke test HTTP sull'istanza reale

| Richiesta | Esito |
|---|---|
| `GET /api/tasks` prima di tutto | `[{"id":1,...,"projectId":null}]` — il task preesistente, esplicitamente non assegnato |
| `POST /api/projects` | `201`, id 2 |
| `PUT /api/tasks/1/project {"projectId":2}` | `200`, `projectId: 2` — un task nato prima della relazione entra in un progetto |
| `GET /api/projects/2/tasks` | `200`, contiene il task 1 |
| `POST /api/tasks` con `projectId: 2` | `201`, `projectId: 2` |
| `PUT /api/tasks/1/project {"projectId":987654}` | `404` `Project not found` |
| `PUT /api/tasks/987654/project` | `404` `Task not found` |
| `GET /api/projects/987654/tasks` | `404` `Project not found` |
| `POST /api/projects/2/archive` | `200`, `ARCHIVED` |
| `GET /api/projects/2/tasks` dopo l'archiviazione | `200`, i due task **invariati e ancora attaccati** |
| `PUT /api/tasks/1/project` verso il progetto archiviato | `409` `Archived project cannot receive tasks` |
| `POST /api/tasks` con il progetto archiviato | `409`, nessun task creato |
| `POST /api/tasks {}` | `400`, forma di errore di prima (nessun `ProblemDetail`) |
| `POST /api/tasks` senza `projectId` | `201`, `projectId: null` — il contratto preesistente regge |
| `DELETE /api/projects/2` | `405`, invariato |

Righe di smoke test rimosse al termine: `project_id` del task 1 riportato a `NULL`, task 4 e
5 cancellati, progetto «TASK-003 smoke» cancellato. Stato finale del volume identico a quello
iniziale, a meno della versione di schema — che è l'upgrade voluto — e delle sequenze di
identificatori, che sono monotone per costruzione.

## Stato Git

| Voce | Valore |
|---|---|
| Branch | `task-003-task-project-association`, creato da `master` |
| HEAD all'avvio | `c73fa39` |
| Remote | **nessuno configurato** — nessun push |
| `master` | **intatto**, nessun merge |
| Storia | non riscritta: nessun force push, reset, rebase o cancellazione di branch |
