# AI Company OS — Project State

## Project
AI Company OS

## Status
PHASE 0 completata. Fondazione di persistenza implementata (TASK-001, 2026-09-11).
In attesa di review differenziale Codex su TASK-001.

## Current phase
PHASE 1 — Foundations (persistenza completata)

## Current task
Nessuna attiva. TASK-001 chiusa; TASK-002 non avviata.

## Last completed task
TASK-001 — Reproducible Persistence Foundation.
Artefatti: `tasks/TASK-001/{TASK.md,CONTEXT.yaml,IMPLEMENTATION.md,ARTIFACT.md,HANDOFF.md}`, `docs/adr/ADR-001`, `docs/adr/ADR-002`, `docs/RUNNING.md`.

## Stato del sistema
- Database: **PostgreSQL 17** via `docker-compose.yml`, volume `aicompany_postgres_data`, porta su loopback.
- Schema: di proprietà di **Flyway** (`db/migration`); Hibernate in `validate`, il drift blocca l'avvio.
- Seed di sviluppo: migrazione `db/dev/V1000`, applicata solo dal profilo `dev`. `AgentInitializer` rimosso.
- Profili: `dev` (default), `test` (Testcontainers), `prod` (sole variabili d'ambiente).
- API: DTO con Bean Validation su `agents` e `tasks`. `POST /api/tasks {}` → `400`. Creazione valida → `201` + `Location`.
- Test: **17**, tutti contro PostgreSQL reale. `./mvnw -B clean test` → BUILD SUCCESS.
- H2 rimosso dal progetto.

## Stato Git (verificato il 2026-09-11)
- Branch corrente: `task-001-persistence-foundation`.
- `master`: `930f70f`, intatto, nessun merge.
- Backend applicativo: committato in `ea25bee`; artefatti TASK-000 in `503663c`; review Codex in `2f01194`.
- TASK-001: `5e164f5` (persistenza), `c295189` (test), più il commit di documentazione.
- Working tree pulito. **Nessun remote configurato** e nessun push eseguito: una destinazione remota richiede approvazione esplicita.
- Correzione rispetto allo stato precedente di questo file: il codice applicativo **non** è più «staged ma non committato». L'affermazione «unica copia sul disco locale» è stata rimossa perché non verificabile, come osservato dalla review Codex (R1).

## Decisioni architetturali
- **ADR-001** — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato, non ancora implementato. *Accettata* (approvazione utente, 2026-09-11).
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway, verificato su database reale. *Accettata*.

## Prossimo passo proposto
Sequenza rivista dalla review Codex: **contratti minimi e dominio con test → completamento API/CI → sicurezza minima e contratto Run → Model Gateway → frontend minimo**.

TASK-002 candidata: contratti API e modello di dominio (entità `Project`, relazioni, enum di stato), con i test già disponibili come rete di sicurezza. **Non avviata**: richiede approvazione dello scope.

## Debito aperto rilevante
TD-04 sicurezza, TD-07 gestione errori, TD-08 `MasterOrchestrator`, TD-11 CORS, TD-12/TD-13 dominio, TD-14 CI assente, TD-15 Lombok inutilizzato, TD-17/TD-18 `README.md`.
Correzioni documentali richieste dalla review agli artefatti `docs/audit/*` di TASK-000: ancora da applicare, fuori scope TASK-001.

## Working principles
- Human-in-the-Loop.
- Autonomous execution inside the approved task scope.
- Persistent project artifacts instead of long chat histories.
- Minimal context loading.
- Claude Code and Codex alternate work through explicit handoffs.
- Local models should be preferred for low-risk/low-complexity work once routing is implemented.
- Never rewrite or discard existing working code without evidence and justification.

## Target architecture
AI Company OS will progressively include:
- Project Registry
- Agent Registry
- Skills / Rules / Subagents / Tools / MCP Registry
- Model Gateway and local/cloud routing
- Context Engineering
- Prompt Engineering
- Harness Engineering
- Loop Engineering / Human approval gates
- Graph Engineering
- Visual Code Architecture Graph with pseudocode
- Knowledge Vault and navigable Memory Graph
- Planner
- GitHub / GitLab
- ClickUp
- Google Drive / Gmail
- Software adapters
- Template Hub
- Framework Explorer
- External AI workspaces
- Voice Interaction Layer
- Payments / subscriptions / quota monitoring
- 3D Omniverse integration

## Immediate goal
Review differenziale di TASK-001 da parte di Codex, poi approvazione dello scope di TASK-002.
