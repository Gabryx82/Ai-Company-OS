# TASK-010 — Implementazione

Decisioni in `docs/adr/ADR-011-task-status-closed-vocabulary.md`. Evidenza in
`tasks/TASK-010/CENSUS.md`. Qui c'è **cosa è stato scritto e dove**.

## 1. File

### Nuovi

| File | Ruolo |
|---|---|
| `task/model/TaskStatus.java` | L'enum chiuso. `contains(String)` e `vocabulary()` — la seconda deriva il messaggio d'errore dai membri, così non può andare stantia |
| `task/dto/InTaskStatusVocabulary.java` | Il vincolo Jakarta e il suo validator, con la motivazione di ADR-011 §4 scritta dove verrà letta |
| `db/migration/V7__add_task_status_check.sql` | `tasks_status_check` |
| `task/TaskStatusVocabularyTest.java` | 9 test: il bordo HTTP e il database |
| `task/TaskStatusPinTest.java` | 4 test: l'insieme, e l'accordo fra le guardie |

### Modificati

| File | Cambio |
|---|---|
| `task/model/Task.java` | `String status` → `TaskStatus status`, `@Enumerated(EnumType.STRING)`. Nessun setter, nessuna transizione: ADR-011 §3 |
| `task/dto/TaskCreateRequest.java` | `@Size(max=255)` su `status` sostituito da `@InTaskStatusVocabulary`; aggiunto `statusValue()`, l'unico punto in cui la stringa del wire diventa un valore di dominio |
| `task/dto/TaskResponse.java` | `TaskStatus status`. **JSON invariato**: Jackson scrive l'enum col nome |
| `task/service/TaskService.java` | `create(...)` prende `TaskStatus` |
| `task/controller/TaskController.java` | `request.status()` → `request.statusValue()` |
| `persistence/SchemaMigrationTest.java`, `persistence/DevSeedMigrationTest.java` | L'elenco delle versioni applicate acquista `"7"` |
| `persistence/MigrationStreamTest.java` | Due test per `V7` (§3) |
| 11 classi di test | `new Task(..., "OPEN", ...)` → `TaskStatus.OPEN`. Meccanico, 25 call site |

## 2. Dove sta ogni guardia, e perché lì

```
POST /api/tasks {"status": "..."}
        │
        ├─ 1 ── @NotBlank                     assente/vuoto → "status is required"
        │       @InTaskStatusVocabulary       fuori insieme → "status must be one of OPEN, IN_PROGRESS, DONE"
        │                                     entrambe → 400 validation-failed, errors.status
        │
        ├────── TaskCreateRequest.statusValue()    l'unica conversione String → TaskStatus
        │
        ├─ 2 ── Task.status : TaskStatus          @Enumerated(STRING), nessun setter
        │
        └─ 3 ── tasks_status_check                 tutto ciò che non passa di qui
```

Il validator **lascia passare `null` e blank** di proposito: sono affare di `@NotBlank`. Due
vincoli che segnalano la stessa assenza mettono due frasi sotto lo stesso campo, e
`ApiExceptionHandler` tiene la prima che arriva — cioè un caso in cui il messaggio dipende
dall'ordine di valutazione. Un test lo asserisce sul **messaggio**, perché entrambi i percorsi
rispondono `400 validation-failed` e il codice da solo non distingue quale vincolo ha sparato.

## 3. I due test su `V7`, e cosa ciascuno copre

`everyConsecutiveUpgradePreservesWhatWasAlreadyThere` (TASK-006) **copriva già** la
sopravvivenza delle righe nel passo `V6 → V7`, senza che nessuno aggiungesse un caso: scrive un
task `OPEN` a ogni versione prima di migrare alla successiva. Era l'upgrade test di una
migrazione che allora non esisteva, ed è la ragione per cui il vocabolario **doveva** contenere
`OPEN`: escluderlo lo avrebbe reso rosso a ogni coppia.

Quello che quel test non può dire è specifico di `V7`, e sta nei due nuovi:

| Test | Cosa asserisce |
|---|---|
| `taskStatusVocabularyIsAppliedToAPopulatedV6Database` | La migrazione riesce su un database popolato, **non riscrive la riga preesistente** (stesso valore, stessa grafia), i conteggi non cambiano, e il vincolo è reale dopo — su `INSERT` **e** su `UPDATE` |
| `taskStatusVocabularyStopsOnADatabaseThatHoldsAValueOutsideIt` | Il rischio dichiarato, eseguito: con un `'banana'` in tabella la migrazione **fallisce**, non si registra come applicata, **e la riga resta intatta** |

La seconda metà del secondo test è la parte che conta davvero: una migrazione che si fermasse
**dopo** aver distrutto ciò che non sa classificare sarebbe peggio di una che riesce.

## 4. Perché `@Size(max = 255)` è sparito da `status`

Subsunto. Un valore di 300 caratteri non è nel vocabolario, quindi è rifiutato comunque, con lo
stesso `400` e lo stesso `type`. Cambia solo il messaggio, che adesso dice la cosa utile invece
della lunghezza. `@Size` resta su `title`, `description` e `priority`, dove è ancora l'unico
vincolo di forma.

## 5. Quello che **non** è stato toccato, verificato e non assunto

| File | Perché è la verifica che conta |
|---|---|
| `api/ApiProblem.java` | **Nessun `type` nuovo.** Era I-10, ed è la regola 6 del briefing |
| `api/ApiExceptionHandler.java` | Il contratto di TASK-005 regge senza modifiche |
| `api/PreconditionCoverageTest.java` | **I-11.** Nessun percorso di scrittura nuovo, quindi doveva restare verde **senza essere modificato**. Lo è: non compare nel diff, ed è verde |
| `db/dev/V1__dev_seed_agents.sql` | Il seed non scrive task. `V4` insegnò che una migrazione può rompere il seed; qui non può, e il censimento lo stabilisce invece di sperarlo |
| `Task.priority` | TD-36, deliberato |
