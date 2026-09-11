# MIGRATION_MAP — Percorso di migrazione minimo (TASK-000)

Principio guida (`docs/MASTER_PROMPT.md`): *piccoli cambiamenti reversibili, nessuna riscrittura senza evidenza*.

## 1. Decisione di fondo

**Non si riscrive.** L'audit non ha trovato alcuna evidenza che giustifichi la sostituzione dello stack: la build passa, i test passano, gli endpoint rispondono, la struttura package-by-feature è corretta.

Il repository esistente è **uno scheletro sano, non un sistema legacy**. La strategia è espansione incrementale con un solo REPLACE mirato (`MasterOrchestrator`).

## 2. Sequenza di migrazione

### M-0 — Igiene e messa in sicurezza (prerequisito, ore)
- Committare il lavoro attualmente staged (il codice è **senza backup**: nessun remote configurato).
- Configurare un remote Git.
- `.gitignore` alla radice.
- Rimuovere `backend/HELP.md` dall'index (è già in `.gitignore`).
- Correggere l'escape markdown del `README.md` e separare "stato attuale" da "target".
- **Rischio**: nullo. **Sblocca**: tutto il resto.

### M-1 — Fondazione riproducibile
- `docker-compose.yml` con PostgreSQL.
- Profili `dev` / `test` / `prod`; `application.properties` → `application-{profile}.yml`.
- Flyway con baseline dallo schema Hibernate corrente; `ddl-auto=validate`.
- Seed degli agent migrato da `AgentInitializer` a una migrazione versionata.
- H2 confinato ai test.
- `open-in-view` esplicito; rimozione di `spring.h2.console.enabled` (inerte, 404 verificato).
- **Risolve**: TD-01, TD-02, TD-14, TD-16, D-03. **Rischio**: basso.

### M-2 — Modello di dominio
- Nuova entità **`Project`** (dominio 2), oggi completamente assente.
- Relazioni: `Project 1—N Agent`, `Project 1—N Task`, `Task N—1 Agent`.
- `status` e `priority` → enum + transizioni di stato controllate.
- Timestamp di audit (`createdAt`, `updatedAt`).
- Setter/builder sulle entità (oggi l'update è impossibile).
- **Risolve**: TD-12, TD-13, TD-06 (parziale), G-02. **Rischio**: medio — cambia lo schema, ma con Flyway già in M-1 è gestito.

### M-3 — Contratti API
- Livello DTO (record) per input e output; le entità escono dal contratto pubblico.
- `@Valid` + vincoli Jakarta Validation (lo starter è già dichiarato e inutilizzato).
- `@RestControllerAdvice` con RFC 7807 Problem Details.
- CORS globale; rimozione di `@CrossOrigin` locale.
- CRUD completo per Agent e Task; `201 Created` + header `Location`.
- OpenAPI/springdoc.
- **Risolve**: TD-03, TD-05, TD-06, TD-07, TD-11, D-01, D-04, D-05. **Rischio**: basso.

### M-4 — Rete di sicurezza dei test
- `@DataJpaTest` per i repository, `@WebMvcTest` per i controller, Testcontainers per l'integrazione con PostgreSQL.
- CI che esegue i test a ogni push.
- Decidere su Lombok (adottarlo o rimuoverlo).
- **Risolve**: TD-09, TD-14, TD-15. **Rischio**: nullo.
- *Nota*: M-4 può procedere in parallelo a M-2/M-3 e dovrebbe **precedere** ogni refactoring successivo.

### M-5 — Model Gateway (sostituzione dell'orchestratore)
- Astrazioni `Provider` e `Model` (dominio 4): registry, credenziali, quota.
- Almeno un provider reale funzionante + Ollama per i modelli locali.
- `MasterOrchestrator` **REPLACE**: da 4 `if` su keyword a routing verso agent realmente presenti nel registry.
- Contratto orchestrator tipizzato (oggi `String` → `String`).
- Gestione dei segreti tramite variabili d'ambiente.
- **Risolve**: TD-08, TD-22, G-04. **Rischio**: alto — richiede review differenziale (`TOKEN_POLICY.md`, livello "high-risk").

### M-6 — Frontend minimo
- React + TypeScript + Vite.
- Dashboard: elenco progetti, agent, task; creazione task; lancio di un'esecuzione.
- Client tipizzato generato da OpenAPI (prodotta in M-3).
- **Risolve**: G-03. **Rischio**: basso (nuovo codice isolato).

### M-7 — Sicurezza
- Spring Security, utenti, ruoli, RBAC.
- Da anticipare se prima di M-7 vengono introdotte credenziali di provider o integrazioni OAuth.
- **Risolve**: TD-04, G-05.

**Dopo M-7**: i domini restanti (Skills, Rules, Tools, MCP, Context Engine, Prompt Engine, Graph, Knowledge Vault, Planner, integrazioni, Template Hub, voce, pagamenti, 3D) diventano estensioni ordinarie su una base solida. Non prima.

## 3. Cosa NON fare adesso

| Tentazione | Perché è un errore ora |
|---|---|
| Implementare LangGraph / esecuzione a grafo | Non c'è persistenza durevole né dominio: andrebbe rifatto |
| Costruire il Knowledge Graph / pgvector | Dipende da M-1 e M-2 |
| Aprire i 25 domini in superficie | Produce 25 stub, cioè `MasterOrchestrator` moltiplicato per 25 |
| Riscrivere il backend in FastAPI | Nessuna evidenza a supporto; distrugge l'unico codice funzionante |
| Integrare ClickUp / Drive / Gmail / 3D | Richiedono auth (M-7) e dominio Project (M-2) |
| Evolvere `MasterOrchestrator` | È un placeholder: va sostituito, non migliorato |

## 4. Percorso minimo verso un sistema utilizzabile

**M-0 → M-1 → M-2 → M-3 → M-4 → M-5 → M-6**

Al termine si ottiene un *Minimum Viable Company OS*: progetti persistenti, agent configurabili, task tracciati, un modello AI realmente invocabile e una UI per usarli. Copre 5 domini su 25 — ma 5 domini **funzionanti** valgono più di 25 stub.

## 5. Deviazione architetturale proposta (da formalizzare come ADR)

**ADR-001 — Mantenere Spring Boot come control plane invece di migrare a FastAPI**

- **Contesto**: `docs/MASTER_PROMPT.md` indica FastAPI come backend preferito, esplicitamente "subject to TASK-000 validation".
- **Evidenza**: il backend Spring Boot 4.1 / Java 21 esistente compila, passa i test e risponde correttamente su tutti gli endpoint. È l'unico codice funzionante del repository.
- **Decisione proposta**: Spring Boot resta il control plane (progetti, agent, task, auth, persistenza). Un servizio Python separato viene introdotto in M-5 per il livello AI (LangGraph, embedding, orchestrazione modelli), comunicante via HTTP. Questo è coerente con lo stack poliglotta già dichiarato nel `README.md` originale.
- **Alternativa scartata**: riscrittura completa in FastAPI — costo certo, beneficio non dimostrato, distrugge l'unico asset funzionante.
- **Stato**: **richiede approvazione umana** prima di essere considerata vincolante.

## 6. TASK-001 raccomandato

**TASK-001 — Fondazione di persistenza e dominio** (corrisponde a M-0 + M-1, con M-2 come possibile estensione)

Motivazione: è il collo di bottiglia di tutta la catena di dipendenze (`GAP_ANALYSIS.md` §4). Nessun altro lavoro produce valore durevole prima.

Scope proposto:
1. Igiene Git e messa in sicurezza del lavoro non committato (M-0).
2. `docker-compose.yml` con PostgreSQL.
3. Profili `dev`/`test`/`prod`.
4. Flyway con baseline; `ddl-auto=validate`.
5. Seed agent migrato in migrazione versionata; `AgentInitializer` spostato fuori da `repository/`.
6. Test di integrazione che dimostri la persistenza durevole tra riavvii.

Criterio di completamento: i dati sopravvivono al riavvio dell'applicazione, la build passa, lo schema è versionato.

**Nota di scope**: TASK-001 tocca codice applicativo esistente (`AgentInitializer`, `application.properties`, `pom.xml`), operazione **non consentita** in TASK-000. Richiede quindi approvazione umana esplicita all'apertura, secondo `.company-os/AGENT_PROTOCOL.md` §4.
