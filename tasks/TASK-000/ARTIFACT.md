# TASK-000 — ARTIFACT

## Risposte alle domande di audit

**Cosa esiste già?**
Un backend Spring Boot 4.1 / Java 21 con tre contesti embrionali (`agent`, `task`, `ai.orchestrator`): 13 file Java, ~350 righe, 4 endpoint REST, persistenza H2 in-memory. Più la governance `.company-os/` e i documenti di visione. Nient'altro.

**Cosa funziona?**
Verificato a runtime: `GET /api/agents` (3 agent seed), `GET`/`POST /api/tasks`, `POST /api/orchestrator`. Build e test passano (`./mvnw -B test` → exit 0).

**Cosa è incompleto?**
Tutto il resto. Nessun frontend, nessun servizio Python/LangGraph, nessun database durevole, nessuna AI reale, nessuna validazione, nessuna sicurezza, nessun DTO, nessuna gestione errori, 1 solo test (`contextLoads`).

**Cosa si può riutilizzare?**
Lo stack (Java 21 + Spring Boot 4.1), la struttura package-by-feature, la stratificazione controller/service/repository, i due repository Spring Data, il Maven wrapper, lo smoke test. Il valore non è nelle 350 righe ma nelle decisioni architetturali già prese e valide.

**Cosa va rifattorizzato?**
Entità (`Agent`, `Task`: modello troppo povero, niente setter, stati come stringhe libere), i tre controller (esporre DTO, validare, gestire errori), i due service (CRUD incompleto), `AgentInitializer` (package sbagliato), `application.properties` (profili, PostgreSQL), `pom.xml` (metadati, Lombok inutilizzato), `README.md` (markdown rotto, stack inesistente).

**Cosa va sostituito?**
`MasterOrchestrator` e `OrchestratorController` (4 `if` su keyword, nessuna AI, ritorna nomi di agent inesistenti nel DB), H2 in-memory, `ddl-auto=update`.

**Cosa va rimosso?**
`backend/HELP.md` (boilerplate, già in `.gitignore` ma staged), `spring.h2.console.enabled` (inerte: 404 verificato).

**Quali moduli target sono già rappresentati?**
Due su venticinque, entrambi parzialmente: Agents (~10%, anagrafica read-only) e Task Engine (~8%, CRUD parziale senza artefatti). Copertura complessiva **< 2%**.

**Quali sono i rischi architetturali più pericolosi?**
1. Costruire i moduli AI prima di persistenza e dominio: tutto andrebbe rifatto.
2. Rimandare la sicurezza mentre si introducono credenziali di provider, OAuth e pagamenti.
3. Scambiare l'ampiezza per progresso: aprire 25 domini in superficie produce 25 stub — cioè `MasterOrchestrator` moltiplicato per 25.

**Qual è il percorso minimo di migrazione?**
M-0 igiene Git → M-1 PostgreSQL + Flyway + Docker → M-2 dominio `Project` e relazioni → M-3 contratti API → M-4 test e CI → M-5 Model Gateway (sostituisce l'orchestratore) → M-6 frontend minimo. Cinque domini funzionanti su venticinque valgono più di venticinque stub.

**Quale dovrebbe essere TASK-001?**
**Fondazione di persistenza e dominio** (M-0 + M-1): igiene Git, PostgreSQL in Docker, profili, Flyway con baseline, seed migrato, test di persistenza tra riavvii. È il collo di bottiglia dell'intera catena di dipendenze.

## Decisioni chiave

1. **Non si riscrive.** Nessuna evidenza a supporto: build verde, endpoint funzionanti, struttura corretta. Il repository è uno scheletro sano, non un legacy.
2. **ADR-001 proposto** (richiede approvazione umana): mantenere Spring Boot come control plane invece di migrare a FastAPI come suggerito da `MASTER_PROMPT.md`, e aggiungere un servizio Python separato per il livello AI in M-5.
3. **Un solo REPLACE mirato**: `MasterOrchestrator`. È un placeholder, va sostituito, non evoluto.

## Classificazione componenti

KEEP 7 · REFACTOR 12 · REPLACE 4 · DELETE 2 · NEW 12+
Dettaglio in `docs/audit/REUSABLE_COMPONENTS.md`.

## Rilievo urgente fuori scope

Il codice applicativo è **staged ma non committato** e **non esiste alcun remote Git**. L'unica copia del lavoro è sul disco locale. Da risolvere prima di qualsiasi altra attività (M-0).

## Indice degli artefatti

- `docs/audit/CURRENT_ARCHITECTURE.md`
- `docs/audit/CURRENT_FEATURES.md`
- `docs/audit/CURRENT_STACK.md`
- `docs/audit/REUSABLE_COMPONENTS.md`
- `docs/audit/TECHNICAL_DEBT.md`
- `docs/audit/GAP_ANALYSIS.md`
- `docs/audit/MIGRATION_MAP.md`
- `tasks/TASK-000/IMPLEMENTATION.md`
- `tasks/TASK-000/HANDOFF.md`

## Stato
TASK-000 **completato**. TASK-001 **non avviato**.
