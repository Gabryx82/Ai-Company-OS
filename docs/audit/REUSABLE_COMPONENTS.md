# REUSABLE_COMPONENTS — Cosa riutilizzare (TASK-000)

Classificazione richiesta da `tasks/TASK-000/TASK.md`: **KEEP / REFACTOR / REPLACE / DELETE / NEW**.

## 1. Tabella di classificazione

| Componente | Percorso | Stato | Motivazione |
|---|---|---|---|
| Modulo backend Spring Boot | `backend/` | **KEEP** | Build verde, test verde, stack moderno (Boot 4.1 / Java 21). Nessuna evidenza che giustifichi una riscrittura. |
| `BackendApplication` | `backend/.../BackendApplication.java` | **KEEP** | Entry point standard, corretto. |
| Maven wrapper | `backend/mvnw*`, `.mvn/` | **KEEP** | Build riproducibile senza Maven installato (Maven non è su PATH). |
| Struttura package-by-feature | `agent/`, `task/`, `ai/` | **KEEP** | Scelta corretta: si estende naturalmente ai domini Company OS. |
| `AgentRepository` | `agent/repository/` | **KEEP** | Interfaccia Spring Data vuota, valida così com'è. |
| `TaskRepository` | `task/repository/` | **KEEP** | Idem. |
| `AgentService` | `agent/service/` | **REFACTOR** | Solo `getAllAgents()`. Serve CRUD completo, paginazione, filtro per `active`/progetto. |
| `TaskService` | `task/service/` | **REFACTOR** | Serve CRUD completo, transizioni di stato, assegnazione ad agent e progetto. |
| `AgentController` | `agent/controller/` | **REFACTOR** | Esporre DTO invece dell'entità; rimuovere `@CrossOrigin` locale a favore di config CORS globale; completare i verbi HTTP. |
| `TaskController` | `task/controller/` | **REFACTOR** | Aggiungere `@Valid`, `201 Created` + `Location`, gestione errori, DTO. |
| `Agent` (entity) | `agent/model/Agent.java` | **REFACTOR** | Modello troppo povero per il Product Vision. Mancano: `provider`, `model`, `systemPrompt`, skills, rules, subagent, appartenenza a progetto, timestamp. Mancano i setter (blocca l'update). |
| `Task` (entity) | `task/model/Task.java` | **REFACTOR** | `status`/`priority` devono diventare enum; servono `createdAt`/`updatedAt`, FK a progetto e agent, riferimento agli artefatti. |
| `AgentInitializer` | `agent/repository/AgentInitializer.java` | **REFACTOR** | Logica valida (seed idempotente) ma **collocata nel package sbagliato** (`repository`). Spostare in `config`/`bootstrap`, sostituire `System.out` con logger, migrare i dati seed a una migrazione versionata. |
| `BackendApplicationTests` | `backend/src/test/` | **KEEP** | Smoke test valido. Da affiancare, non da sostituire. |
| `MasterOrchestrator` | `ai/orchestrator/model/` | **REPLACE** | 4 `if` su keyword. Nessuna AI. Non è una base evolvibile verso un Model Gateway + routing. Il valore da conservare è solo *concettuale* (esiste un punto di ingresso per l'orchestrazione). |
| `OrchestratorController` | `ai/orchestrator/controller/` | **REPLACE** | Contratto `String` → `String`, non tipizzabile. Da riscrivere con DTO quando esisterà un orchestratore reale. |
| `application.properties` | `backend/src/main/resources/` | **REFACTOR** | Serve separazione per profili (`dev`/`test`/`prod`), PostgreSQL, rimozione di `ddl-auto=update`, `open-in-view` esplicito. |
| `pom.xml` | `backend/pom.xml` | **REFACTOR** | Metadati vuoti (`<name/>`, `<licenses><license/></licenses>`); Lombok dichiarato e mai usato; mancano Flyway, Actuator, Testcontainers. |
| H2 in-memory | configurazione | **REPLACE** | Persistenza volatile. Va sostituita da PostgreSQL (mantenibile come DB di test). |
| `ddl-auto=update` | configurazione | **REPLACE** | Sostituire con Flyway. Rischio alto di drift dello schema. |
| `spring.h2.console.enabled` | configurazione | **DELETE** | Inerte (404 verificato) e comunque non desiderabile fuori dallo sviluppo. |
| `backend/HELP.md` | `backend/HELP.md` | **DELETE** | Boilerplate di Spring Initializr. È in `.gitignore` **ed è anche staged**: incoerenza da risolvere. |
| `README.md` | radice | **REFACTOR** | Markdown con escape errati (`\#`, `\-`) che non renderizza; descrive uno stack in gran parte inesistente. |
| `docs/ARCHITECTURE.md` | `docs/` | **REFACTOR** | Marcato DRAFT, da rivedere dopo TASK-000 come previsto dal documento stesso. |
| `docs/IMPLEMENTATION_PLAN.md` | `docs/` | **REFACTOR** | Da trasformare in indice di task concreti, come dichiarato nel file. |
| Governance `.company-os/` | `.company-os/` | **KEEP** | Coerente, utile, già operativa. |
| Frontend React + TypeScript | — | **NEW** | Assente. |
| Servizio AI Python / LangGraph | — | **NEW** | Assente. |
| Model Gateway / provider registry | — | **NEW** | Assente. |
| Dominio `Project` | — | **NEW** | Assente e centrale nel Product Vision. |
| Migrazioni Flyway | — | **NEW** | Assenti. |
| Livello DTO + mapper | — | **NEW** | Assente. |
| Gestione errori globale | — | **NEW** | Assente. |
| Sicurezza / auth | — | **NEW** | Assente. |
| Docker / docker-compose | — | **NEW** | Assenti. |
| CI (test su push) | — | **NEW** | Assente. |
| `.gitignore` di radice | — | **NEW** | Assente. |
| Domini 4–11, 13–25 di `ARCHITECTURE.md` | — | **NEW** | Nessuna implementazione esistente. |

## 2. Riepilogo quantitativo

| Stato | Conteggio |
|---|---|
| KEEP | 7 |
| REFACTOR | 12 |
| REPLACE | 4 |
| DELETE | 2 |
| NEW | 12+ |

## 3. Il vero asset riutilizzabile

Il valore del repository esistente **non è nelle ~350 righe di Java**, che sono riscrivibili in poche ore. È in tre decisioni già prese e valide:

1. **Lo stack** — Java 21 + Spring Boot 4.1 è maturo, tipizzato, con ecosistema completo per persistenza, sicurezza e test.
2. **La struttura package-by-feature** — si estende senza attriti ai 25 domini target.
3. **La stratificazione controller/service/repository** — separazione delle responsabilità già corretta.

Questi tre elementi vanno **preservati**. Tutto il resto è espansione, non migrazione.
