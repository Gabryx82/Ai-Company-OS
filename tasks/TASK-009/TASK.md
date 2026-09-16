# TASK-009 — Assegnazione `Task` → `Agent`

> **Autonomous Project Mode.** Livello 4 di `AUTONOMOUS_LOOP.md` §4: il livello 3 si è svuotato
> quando TASK-008 ha chiuso TD-28 e TD-30.

## Baseline
Branch `task-009-task-agent-assignment` da `autonomous/phase-2-assignment` (`da0bd0e`).
Suite di partenza: **179 verdi**. Schema a `V5`.

## Perché adesso
`Agent` è un registro senza relazioni: `FINAL_HANDOFF.md` §3 lo diceva a chiare lettere — «nessuna
relazione: le domande di dominio non sono state poste». Planner, Subagents, Model Gateway e la
sostituzione di `MasterOrchestrator` (TD-08) poggiano tutti su un fatto che oggi il sistema non sa
esprimere: **chi lavora su che cosa.**

## Decisioni
In `docs/adr/ADR-010-task-agent-assignment.md`.

### Le tre domande di dominio, risolte

| # | Domanda | Risposta | Coincide con `Task` → `Project`? |
|---|---|---|---|
| **D1** | Assegnare a un agente **disattivato**? | **No, `409`** | Stesso esito, **argomento diverso**: non è contenimento, è responsabilità. Un'obbligazione che nessuno può assolvere |
| **D2** | Cambiare agente a un task in un progetto **archiviato**? | **No, `409`**, con il `type` che esiste già | Sì, ed è la prima verifica della frase generale di ADR-006 §2. «Per costruzione» **non è automatico**: vale perché la guardia sta sull'entità e il percorso nuovo la attraversa |
| **D3** | Disattivare un agente fa qualcosa ai suoi task? | **Nessuna scrittura** sui figli — come `archive`. Ma la regola derivata è **l'opposta**: un task il cui agente è inattivo **non è congelato** | **No, e qui il dominio diverge.** Congelarli li intrappolerebbe con chi non può eseguirli, proprio quando serve riassegnarli |

**D3bis** — far *fallire* `deactivate` finché ha lavoro assegnato è l'alternativa seria, ed è
scartata da due ragioni che puntano nella stessa direzione: introdurrebbe l'arco
`agents → tasks` e **chiuderebbe un ciclo nel lock graph**, e impedirebbe di togliere dal servizio
un agente guasto proprio quando è guasto.

### Il lock graph

**L5′**: `tasks` → `projects` → `agents`, id crescente dentro ogni classe.

L'aciclicità è **ridimostrata** su tutti i percorsi, nuovi e preesistenti (ADR-010 §3.4). Tre
archi, ordinamento topologico `tasks, projects, agents`, e tre archi assenti verificati uno per
uno. Il risultato che conta:

> L'aciclicità è **una conseguenza** di ADR-006 §1 e di **D3**, non una proprietà indipendente.

Due novità sostanziali nella tabella dei percorsi:
- `PUT /api/tasks/{id}/agent` **blocca il progetto del task** (SHARE) — discende da D2;
- e **non blocca l'agente di origine** — discende da D3, perché nessuna regola dipende dal suo stato.

### Versione e precondizione

`agent_id` è una colonna di `tasks`: l'assegnazione **scrive la riga del task**, incrementa
`tasks.version` e consuma l'ETag del task. **Nessun versionamento separato dell'associazione.**
`If-Match` obbligatorio per costruzione (ADR-009 **P4**).

## Invarianti

| ID | Invariante | Come si verifica |
|---|---|---|
| **I-1** | Assegnare a un agente **inattivo** → `409`, e nulla è scritto | Test di API con rilettura |
| **I-2** | Un task in un progetto archiviato **non cambia agente** → `409` | Test di API. Con **entrambi** i rifiuti applicabili, vince quello sul task |
| **I-3** | `deactivate` / `activate` **non scrivono nessuna riga di `tasks`** | Contatore di `UPDATE` su `tasks`, con controllo positivo |
| **I-4** | Un task il cui agente è inattivo **è riassegnabile** | Test di API: è la via di recupero, e se fallisse D3 sarebbe falsa |
| **I-5** | `If-Match` assente → `428`, stantio → `412`, e nulla è scritto | Test di API |
| **I-6** | **Una sola versione**: cambiare il progetto invalida il tag di chi sta per cambiare l'agente | Test di API — è la forma osservabile di ADR-010 §4 |
| **I-7** | Assegnare un task **non** incrementa `agents.version` (P3) | ETag dell'agente prima e dopo |
| **I-8** | Cambio di progetto e cambio di agente concorrenti sullo **stesso task** si serializzano su L0: un `200` e un `412` | Test a due thread, latch |
| **I-9** | `deactivate` concorrente con un'assegnazione allo stesso agente non può interleavarsi: o l'assegnazione commette prima, o è `409` | Test a due thread (L1 contro L2) |
| **I-10** | `archive` del progetto concorrente con un cambio di agente: la regola di congelamento decide su stato vero | Test a due thread (L2 sul progetto dal percorso dell'agente) |
| **I-11** | `V6` additiva: nessuna riga persa, nessuna tabella eliminata | Il test sulle coppie consecutive di TASK-006, **senza aggiungere un caso** |
| **I-12** | Ogni `type` nuovo è enumerato | `ApiProblemCoverageTest` |
| **I-13** | **P4**: il percorso nuovo è nell'insieme pinnato dei write path | `PreconditionCoverageTest`, e una mutazione che lo esclude deve renderlo rosso |

## In scope
1. `V6__add_task_agent_relation.sql`.
2. `Task.agent`, `Task.assignTo(Agent)`, e la guardia di congelamento **estratta** in un punto solo.
3. `TaskRepository`: listato per agente, join fetch dove serve.
4. `TaskService.assignToAgent(Long, Long, Precondition)` — L0, P1, L2 su progetto e agente, L5′.
5. `AgentTaskController` — `GET /api/agents/{id}/tasks`.
6. `PUT /api/tasks/{id}/agent`, `TaskAgentAssignmentRequest`.
7. `TaskResponse.agentId`; `TaskCreateRequest.agentId` opzionale.
8. Un `ApiProblem` nuovo: `inactive-agent-cannot-receive-tasks`.
9. `PreconditionCoverageTest` esteso al percorso nuovo.
10. Test: API, concorrenza (tre interleaving), upgrade `V5 → V6` su database popolato.
11. Artefatti, ADR-010, `PROJECT_STATE.md`, `PHASE_2_PLAN.md`.

## Fuori scope
- **Congelare** i task di un agente inattivo, o far fallire `deactivate` → D3, D3bis.
- `DELETE /api/tasks/{id}/agent` → **TD-35**.
- Filtro «task fermi su agenti inattivi» → **TD-34**.
- Campi derivati sullo stato dell'agente in `TaskResponse`.
- Relazione bidirezionale, collezione di task su `Agent`.
- Planner, routing, sostituzione di `MasterOrchestrator` (TD-08): diventa lavoro reale **dopo**.
- TD-14 (CI), TD-04 (auth), TD-11 (CORS), TD-31 (hard stop umano).

## Criteri di accettazione
- **AC-1** `PUT /api/tasks/{id}/agent` assegna, risponde `200` + `ETag`, e `agentId` compare in `TaskResponse`.
- **AC-2** Agente inattivo → `409 inactive-agent-cannot-receive-tasks`; task invariato.
- **AC-3** Task in progetto archiviato → `409 archived-project-task-is-immutable`; e con entrambi i rifiuti applicabili è **questo** a vincere.
- **AC-4** Stesso agente già assegnato → `200` no-op, anche se nel frattempo è stato disattivato.
- **AC-5** `deactivate` non scrive righe di `tasks`, e il controllo positivo prova che il contatore si muove.
- **AC-6** Un task con agente inattivo si riassegna a un agente attivo.
- **AC-7** `428` senza `If-Match`, `412` con tag stantio, in entrambi i casi nulla scritto.
- **AC-8** Un cambio di progetto invalida il tag per il cambio di agente, e viceversa.
- **AC-9** Assegnare non muove l'ETag dell'agente.
- **AC-10** `GET /api/agents/{id}/tasks` elenca dal più vecchio; agente inesistente → `404`.
- **AC-11** `POST /api/tasks` con `agentId` inattivo → `409`, e **il task non viene creato**.
- **AC-12** I tre test di concorrenza (I-8, I-9, I-10) passano, e ciascuno è verificato per mutazione.
- **AC-13** `V6` su database popolato non perde righe.
- **AC-14** Suite completa verde; i test di TASK-004, TASK-007 e TASK-008 invariati nel significato.
- **AC-15** Una mutazione che toglie la `Precondition` da `assignToAgent` rende rosso `PreconditionCoverageTest`.
