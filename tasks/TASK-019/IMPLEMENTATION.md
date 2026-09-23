# TASK-019 — Implementazione

| File | Ruolo |
|---|---|
| `db/migration/V10__create_task_runs.sql` | Tabella, tre `CHECK`, indice per task, indice unico parziale |
| `run/model/TaskRun`, `RunStatus` | Entità con transizioni che controllano il punto di partenza |
| `run/repository/TaskRunRepository` | Lettura per task, esistenza di run attive, lock della riga |
| `run/service/RunService` | `launch` (protocollo del task), letture |
| `run/service/RunQueued` | Evento consegnato dopo il commit |
| `run/execution/RunDispatcher` | Listener `AFTER_COMMIT`, executor, recupero all'avvio |
| `run/execution/RunRecorder` | Tre transazioni brevi, lock della sola run |
| `run/execution/RunPrompt` | Prompt di sistema e utente dal registro |
| `run/engine/*` | `EngineClient`, `HttpEngineClient` (JDK), `EngineFailure`, `RunFailures`, `EngineProperties` |
| `run/RunConfiguration` | Bean del client e dell'executor limitato |
| `run/controller/RunController` | Tre rotte |
| `task/model/Task#requireRunnable`, `FinishedTaskCannotRunException` | La regola sta sul task |
| `api/ApiProblem`, `ApiExceptionHandler` | `run-not-found`, `task-run-in-progress`, `finished-task-cannot-run` |
| `application-{dev,prod}.properties` | `aicos.engine.url`, `aicos.engine.token` (dev: default locale; prod: obbligatori) |

Test: `TaskRunApiTest` (22), `HttpEngineClientTest` (8, socket reale). Supporto:
`ScriptedEngineClient` (`@Primary` in ogni test Spring), pulizia di `task_runs` nel `@BeforeEach` di
`AbstractPostgresTest`. Pin aggiornati: versioni fino a `10`, tabelle con `task_runs`.
