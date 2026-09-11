# TASK-003 — IMPLEMENTATION

## 1. La forma della relazione

Una riga di `tasks` porta un `project_id` nullable con chiave esterna verso `projects(id)`.
La relazione è **unidirezionale**: `Task` conosce il proprio `Project`, `Project` non sa
niente dei task.

L'unidirezionalità non è una semplificazione, è la scelta che tiene onesta la decisione
rimandata. Una `@OneToMany` su `Project` renderebbe la cascata `archive` → task una
questione di annotazione (`cascade = ...`) invece della decisione di dominio che è. Senza
quella collezione, dare ad `archive` un effetto sui task richiede di scrivere il codice che
lo fa, cioè di deciderlo esplicitamente. ADR-005 §4.

## 2. La migrazione

`backend/src/main/resources/db/migration/V3__add_task_project_relation.sql`

```sql
ALTER TABLE tasks ADD COLUMN project_id BIGINT;

ALTER TABLE tasks
    ADD CONSTRAINT tasks_project_id_fkey
    FOREIGN KEY (project_id) REFERENCES projects (id);

CREATE INDEX tasks_project_id_idx ON tasks (project_id);
```

Tre proprietà volute:

| Proprietà | Perché |
|---|---|
| Nessuna `UPDATE` | Non esiste un valore corretto da scrivere nelle righe preesistenti (ADR-005 §2) |
| `ADD COLUMN` senza `DEFAULT` | Su PostgreSQL è una modifica di soli metadati: non riscrive la tabella, è istantanea, non può restare a metà |
| Nessuna clausola `ON DELETE` | Il `NO ACTION` predefinito **è** la decisione: una `DELETE` su un progetto con task viene rifiutata, invece di cascare o staccare in silenzio (ADR-005 §7) |

L'indice è necessario perché PostgreSQL non indicizza da solo il lato referenziante di una
chiave esterna, e «i task di questo progetto» è la lettura per cui la relazione esiste.

## 3. Il dominio

`Task` acquista un campo e un metodo.

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "project_id")
private Project project;

public void assignTo(Project project) {
    if (project.isArchived()) {
        throw new ArchivedProjectCannotReceiveTasksException(project.getId());
    }
    this.project = project;
}
```

La regola sta sull'entità, non nel service, per la stessa ragione per cui ci stanno le
transizioni di `Project` (ADR-004 §4): `project` non ha un setter, quindi `assignTo` è
l'unica strada e nessun punto di ingresso futuro — un importer, il Planner, un agente — può
dimenticarsi il controllo.

`getProjectId()` restituisce `null` o l'identificatore. Inizializza il riferimento pigro,
quindi va chiamato dentro una transazione — vedi §5.

## 4. Il contratto HTTP

| Metodo | Path | Comportamento | Novità |
|---|---|---|---|
| `POST` | `/api/tasks` | `projectId` **opzionale** nel body | modificato, additivo |
| `GET` | `/api/tasks` | `projectId` in ogni elemento | modificato, additivo |
| `PUT` | `/api/tasks/{id}/project` | assegna o sposta; **idempotente** | nuovo |
| `GET` | `/api/projects/{projectId}/tasks` | i task di quel progetto, dal più vecchio | nuovo |

Nessun `DELETE /api/tasks/{id}/project`: pubblicare un endpoint è irreversibile, non
pubblicarlo no, e un task assegnato per errore si corregge spostandolo (ADR-005 §5).

Codici di errore dei percorsi nuovi:

| Situazione | Codice | `title` del `ProblemDetail` |
|---|---|---|
| Task inesistente | `404` | `Task not found` |
| Progetto inesistente (in creazione o in assegnazione) | `404` | `Project not found` |
| Progetto `ARCHIVED` | `409` | `Archived project cannot receive tasks` |
| `projectId` assente o non positivo | `400` | — (Bean Validation, forma di Spring) |

Su `PUT /api/tasks/{id}/project` entrambi gli identificatori possono non risolvere, quindi
lo stato da solo è ambiguo: è il `title` a disambiguare, ed è asserito dai test.

### `ProjectTaskController` sta nel package `task`

La rotta è `/api/projects/{projectId}/tasks` ma la classe vive accanto ai task. Montarla su
`ProjectController` avrebbe reso bidirezionale una dipendenza che oggi corre in una
direzione sola, in cambio di un prefisso di URL.

## 5. Perché `TaskService` restituisce DTO

`Task.project` è `LAZY` e `spring.jpa.open-in-view` è `false`: quando il controller vede
l'entità, il contesto di persistenza è già chiuso, e leggere `projectId` lì solleverebbe
`LazyInitializationException`. La mappatura deve avvenire dove i dati ci sono.

Quindi `TaskService` è `@Transactional` e restituisce `TaskResponse`. `ProjectService` non ha
associazioni e continua a restituire entità: la differenza fra i due moduli ha una causa, non
è una svista (ADR-005 §9).

Effetto collaterale voluto: con l'entità confinata nel service, l'unico modo per cambiare il
progetto di un task è `assignToProject`, cioè il punto in cui le regole sono.

Le due query che attraversano la relazione usano un `join fetch` esplicito, così una lista
di N task non produce N query per i rispettivi progetti:

```java
@Query("SELECT t FROM Task t LEFT JOIN FETCH t.project p WHERE p.id = :projectId ORDER BY t.id ASC")
List<Task> findAllByProjectId(@Param("projectId") Long projectId);

@Query("SELECT t FROM Task t LEFT JOIN FETCH t.project ORDER BY t.id ASC")
List<Task> findAllWithProject();
```

## 6. Creazione: il progetto si risolve prima dell'insert

```java
Task task = new Task(title, description, status, priority);
if (projectId != null) {
    task.assignTo(requireProject(projectId));
}
return TaskResponse.from(repository.save(task));
```

Un `projectId` sbagliato non lascia dietro un task creato e non collocato: la risoluzione
avviene prima del `save`, nella stessa transazione. Due test asseriscono `count() == 0` dopo
un `404` e dopo un `409`.

## 7. L'advice sui task gestisce solo le eccezioni nuove

`TaskExceptionHandler` è `@RestControllerAdvice(assignableTypes = {TaskController.class,
ProjectTaskController.class})` e **non** gestisce `MethodArgumentNotValidException`.

L'omissione è il punto: gestirla cambierebbe la forma del `400` che `POST /api/tasks`
produce da TASK-001, cioè un cambio di contratto osservabile su un endpoint fuori scope. Il
risultato è che sotto `/api/tasks` convivono due forme di errore — `ProblemDetail` per le
risposte nuove, il default di Spring per quelle che c'erano già. È la stessa disomogeneità
che TASK-002 ha accettato come **TD-07**, non allargata (ADR-005 §8).

Un test lo blocca: `theValidationContractOfTheTaskApiIsUnchanged` verifica che un
`POST /api/tasks {}` non abbia né `$.errors` né `$.title`.

## 8. Effetto collaterale obbligato sui test esistenti

`ProjectApiTest` e `ProjectPersistenceTest` azzeravano i progetti con
`repository.deleteAll()`. Da `V3` la chiave esterna rifiuta di cancellare un progetto a cui
un task punta ancora, quindi il `@BeforeEach` cancella prima i task.

Non è un aggiramento del vincolo: è esattamente il comportamento che
`databaseRefusesToDeleteAProjectThatStillHasTasks` asserisce come corretto, osservato da
un'altra angolazione.

`SchemaMigrationTest` e `DevSeedMigrationTest` passano da `("1", "2")` a `("1", "2", "3")`.
L'elenco resta esplicito di proposito, come deciso in TASK-002: chi aggiunge una `V4` deve
dichiararla.

## 9. Il test dell'upgrade incrementale

`MigrationStreamTest.taskProjectRelationIsAddedToAPopulatedV2Database` è il test che le altre
migrazioni non avevano: ogni altro caso costruisce lo schema in un colpo solo, quindi una
migrazione che dipendesse in silenzio da una tabella vuota passerebbe comunque.

Usa `Flyway.target("2")` per fermare lo stream a `V2`, scrive le righe che un'installazione
in esercizio avrebbe — seed, un task, un progetto — e solo allora lascia correre `V3`.

Verifica, in ordine: che a `V2` la colonna non ci sia; che l'upgrade esegua **una** sola
migrazione; che le righe preesistenti sopravvivano con gli stessi valori e `project_id NULL`;
che dopo l'upgrade un task preesistente sia assegnabile; che la chiave esterna rifiuti un
identificatore che non risolve; e che lo stream possa ancora andare oltre la nuova testa.

## 10. Cosa questa task ha deliberatamente lasciato dov'era

- **`archive`/`restore` non toccano i task.** Il prerequisito è TD-19, e TD-19 va sciolto
  insieme alla decisione sulla cascata, non prima e non dopo (ADR-005 §4).
- **Nessun controllo di concorrenza aggiunto.** Nessun `@Version`, nessun lock.
- **`Task.status` e `Task.priority` restano stringhe libere.**
- **`GET /api/tasks/{id}` continua a non esistere**, benché `POST` emetta un `Location` che
  punta lì. LOW della review TASK-001, ancora aperto.
- **`docs/RUNNING.md` non documenta gli endpoint nuovi**, come già non documenta quelli di
  `/api/projects`. Debito di TASK-002, non peggiorato e non chiuso.
