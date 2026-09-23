# TASK-020 — Implementazione

| File | Ruolo |
|---|---|
| `db/migration/V11__add_agent_model.sql` | `agents.model VARCHAR(200)` nullable, nessun default, nessun `CHECK` |
| `agent/model/Agent` | Campo `model`, costruttore a 4 argomenti, `updateDetails` a 4 argomenti |
| `agent/dto/*` | `model` facoltativo, con il pattern dell'id di modello; `AgentResponse.model` |
| `agent/routing/AgentRouter` | Termini, prefisso ≥ 5, punteggio, ordinamento stabile |
| `agent/routing/RoutingController` | `POST /api/routing/suggestions` |
| `task/controller/TaskRoutingController` | `GET /api/tasks/{id}/agent-suggestions` |
| `run/service/RunService` | Risoluzione del modello |
| **rimossi** `ai/orchestrator/**` | `MasterOrchestrator`, `OrchestratorController` |

Test: `AgentModelAndRoutingTest` (11), `AgentRouterTermsTest` (3). Pin delle versioni a `11`.
