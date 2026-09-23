# TASK-016 — Implementazione

| File | Ruolo |
|---|---|
| `task/model/TaskPriority` | `LOW`, `MEDIUM`, `HIGH`; `contains`, `vocabulary` |
| `task/dto/InTaskPriorityVocabulary` | Gemello di `InTaskStatusVocabulary` (campo `String`, ADR-011 §4) |
| `task/dto/TaskUpdateRequest` | Titolo, descrizione, priorità |
| `task/model/Task#updateDetails` | Regola di congelamento |
| `task/service/TaskService#update`, `#findAll(TaskStatus)` | L0 → P1 → L2 progetto; filtro |
| `task/controller/TaskController` | `PUT /{id}`, `GET ?status=` |
| `db/migration/V9__add_task_priority_check.sql` | `tasks_priority_check` |
| Test esistenti | Pin delle versioni esteso a `9`; fixture con `TaskPriority` invece di stringhe |

Test nuovi: `TaskDetailsApiTest` (15), due casi in `MigrationStreamTest`.
Rosso prima: il commit di test non compilava (`TaskPriority`, `PUT`, filtro assenti).
