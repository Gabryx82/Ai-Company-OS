# CURRENT_ARCHITECTURE — Architettura reale (TASK-000)

## 1. Mappa del repository

```
AI-Company-OS/
├── README.md                  # descrive uno stack target, non quello reale
├── .company-os/               # governance Company OS (nuovo, non applicativo)
│   ├── AGENT_PROTOCOL.md
│   ├── CONTEXT_POLICY.md
│   ├── PROJECT_STATE.md
│   └── TOKEN_POLICY.md
├── docs/                      # visione e architettura target (DRAFT)
│   ├── ARCHITECTURE.md
│   ├── IMPLEMENTATION_PLAN.md
│   ├── MASTER_PROMPT.md
│   └── PRODUCT_VISION.md
├── prompts/                   # prompt operativi per gli agenti
├── tasks/TASK-000/            # task corrente
└── backend/                   # UNICO codice applicativo esistente
    ├── pom.xml
    ├── mvnw / mvnw.cmd / .mvn/
    ├── HELP.md                # generato da Spring Initializr, ignorato da .gitignore ma staged
    └── src/
        ├── main/java/com/aicompany/backend/
        │   ├── BackendApplication.java
        │   ├── agent/{controller,model,repository,service}
        │   ├── task/{controller,model,repository,service}
        │   └── ai/orchestrator/{controller,model}
        ├── main/resources/application.properties
        └── test/java/.../BackendApplicationTests.java
```

**Totale codice applicativo: 13 file Java, ~350 righe.** Non esiste altro codice eseguibile nel repository.

## 2. Architettura applicativa

Monolite Spring Boot a **tre bounded context embrionali**, organizzati per feature (package-by-feature) con stratificazione classica controller → service → repository → entity JPA.

```
                    ┌──────────────────────────────┐
   HTTP :8080  ───► │      Spring Boot MVC          │
                    ├──────────────────────────────┤
                    │ AgentController               │  GET  /api/agents
                    │ TaskController                │  GET  /api/tasks
                    │                               │  POST /api/tasks
                    │ OrchestratorController        │  POST /api/orchestrator
                    ├──────────────────────────────┤
                    │ AgentService   TaskService    │
                    │ MasterOrchestrator (@Service) │
                    ├──────────────────────────────┤
                    │ AgentRepository TaskRepository│  Spring Data JPA
                    ├──────────────────────────────┤
                    │ Agent           Task          │  @Entity
                    └──────────────┬───────────────┘
                                   │ Hibernate 7.4 / ddl-auto=update
                                   ▼
                        ┌────────────────────┐
                        │  H2 in-memory      │  volatile
                        └────────────────────┘

   AgentInitializer (CommandLineRunner) ──► seed 3 agent al primo avvio
```

### Contesto `agent`
- `Agent` — entità JPA: `id`, `name`, `role`, `specialization`, `active`. Solo getter, **nessun setter**: entità di fatto immutabile dopo la costruzione.
- `AgentRepository` — `JpaRepository<Agent, Long>` vuoto.
- `AgentService` — espone solo `getAllAgents()`.
- `AgentController` — un solo endpoint `GET /api/agents`, annotato `@CrossOrigin`.
- `AgentInitializer` — `CommandLineRunner` che inserisce 3 agent hardcoded se la tabella è vuota. **Collocato nel package `repository`**, che è una violazione di layering: non è un repository.

### Contesto `task`
- `Task` — entità JPA: `id`, `title`, `description` (5000 char), `status`, `priority`. Status e priority sono **stringhe libere**, non enum. Solo getter, nessun setter.
- `TaskRepository` — `JpaRepository<Task, Long>` vuoto.
- `TaskService` — `getAllTasks()`, `save()`.
- `TaskController` — `GET /api/tasks`, `POST /api/tasks`. Nessuna validazione, nessun `@CrossOrigin`.

### Contesto `ai.orchestrator`
- `MasterOrchestrator` — annotato `@Service` ma **collocato nel package `model`**. Contiene `analyze(String)`: una catena di `if` su `contains()` di 4 keyword (`ecommerce`, `cms`, `marketing`, `social`) che ritorna una stringa fissa. **Non c'è alcuna AI**: nessun modello, nessun provider, nessuna chiamata LLM, nessuna dipendenza AI nel `pom.xml`.
- `OrchestratorController` — `POST /api/orchestrator`, accetta `String` grezzo e ritorna `String` grezzo. Nessun contratto JSON.

## 3. Osservazioni architetturali

1. **Non esiste un livello DTO.** Le entità JPA sono esposte direttamente come contratto HTTP in ingresso e in uscita. Il modello di persistenza e il contratto API sono accoppiati.
2. **La deserializzazione dei body POST è implicita.** `Task` non ha setter; la creazione funziona (verificata a runtime) perché Jackson 3 usa il costruttore a 4 argomenti come creator implicito. È un comportamento non dichiarato: aggiungere un campo al costruttore o cambiare libreria di serializzazione rompe silenziosamente l'API.
3. **Nessuna gestione degli errori.** Nessun `@ControllerAdvice`, nessun `@ExceptionHandler`, nessun formato di errore standard.
4. **CORS incoerente.** `@CrossOrigin` è applicato solo su `AgentController`, senza origini specificate. `TaskController` e `OrchestratorController` non lo hanno. Non esiste una configurazione CORS globale.
5. **Nessuna sicurezza.** Nessun Spring Security, nessuna autenticazione, autorizzazione, rate limiting o audit. Tutti gli endpoint sono pubblici e anonimi.
6. **Nessun concetto di Project.** Il dominio centrale del Product Vision (progetti che aggregano agent, task, conoscenza) non ha alcuna rappresentazione: `Agent` e `Task` sono entità isolate, senza relazioni tra loro né verso un progetto.
7. **Nessuna traccia di provider/modelli.** Non esistono astrazioni per model provider, routing, quota, contesto o prompt.
8. **Persistenza volatile.** H2 in-memory + `ddl-auto=update` significa che non esiste ancora una storia dello schema né persistenza reale.

## 4. Copertura dei domini target

Confronto con i 25 domini di `docs/ARCHITECTURE.md`:

| # | Dominio target | Copertura reale |
|---|---|---|
| 1 | Core / Identity / Permissions | **0%** |
| 2 | Projects | **0%** |
| 3 | Agents | **~10%** — anagrafica read-only con 4 campi |
| 4 | Models / Providers | **0%** |
| 5 | Skills | **0%** |
| 6 | Rules | **0%** |
| 7 | Subagents | **0%** |
| 8 | Tools / Tool Bundles | **0%** |
| 9 | MCP / Software Adapters | **0%** |
| 10 | Context Engine | **0%** |
| 11 | Prompt Engine | **0%** |
| 12 | Task / Artifact Engine | **~8%** — CRUD parziale di Task, nessun artefatto |
| 13 | Human Approval Engine | **0%** (esiste solo come processo documentale in `.company-os/`) |
| 14 | Graph Execution | **0%** |
| 15 | Code Intelligence / Visual Code Graph | **0%** |
| 16 | Knowledge Vault / Memory Graph | **0%** |
| 17 | Planner | **0%** |
| 18 | Git integration | **0%** |
| 19 | Collaboration integrations | **0%** |
| 20 | Template Hub | **0%** |
| 21 | Framework Explorer | **0%** |
| 22 | External AI Workspaces | **0%** |
| 23 | Voice Interaction | **0%** |
| 24 | Billing / Subscriptions / Quotas | **0%** |
| 25 | 3D Omniverse integration | **0%** |

Copertura complessiva stimata: **< 2%** del sistema target.

La conclusione operativa è che il repository non è un sistema da migrare, ma **uno scheletro sano da estendere**. Il valore attuale non è nel codice (350 righe) ma nella *struttura* e nello *stack scelto*, entrambi validi.
