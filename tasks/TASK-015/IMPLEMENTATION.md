# TASK-015 — Implementazione

| File | Ruolo |
|---|---|
| `task/model/TaskTransition` | La tabella degli archi (`from`, `to`, `requiresActiveAgent`) |
| `task/model/Task#apply` | Le tre regole, nell'ordine di ADR-014 §3 |
| `task/service/TaskService#transition` | L0 → P1 → L2 progetto → L2 agente (solo `START`) |
| `task/controller/TaskController` | `POST /{id}/start|complete|stop|reopen` |
| `task/exception/IllegalTaskStateTransitionException`, `UnassignedTaskCannotStartException` | 409 |
| `api/ApiProblem`, `ApiExceptionHandler` | Due `type` nuovi |
| `PreconditionCoverageTest`, `ApiProblemCoverageTest` | Aggiornati: `transition:Precondition`, due slug |

Test: `TaskLifecycleApiTest` (15), `TaskLifecycleConcurrencyTest` (1). Rosso prima: 16/16 (`404`).
