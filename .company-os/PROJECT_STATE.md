# AI Company OS — Project State

## Project
AI Company OS

## Status
PHASE 0 completata. **TASK-001 e TASK-001A completate, validate e integrate in `master`**
(2026-09-11). La fondazione di persistenza è la base stabile del progetto.
Pronti per la definizione dello scope di TASK-002.

## Current phase
PHASE 1 — Foundations (persistenza completata)

## Current task
**Nessuna attiva.** TASK-002 non avviata: richiede approvazione dello scope.

## Last completed task
**TASK-001 — Reproducible Persistence Foundation: COMPLETATA e integrata in `master`.**

Ciclo completo: implementazione → review differenziale Codex (`PASS WITH FIXES`) →
TASK-001A per il fix del rilievo HIGH → validazione → merge.

| Fase | Esito |
|---|---|
| TASK-001 | H2 in-memory sostituito da PostgreSQL con schema di proprietà di Flyway, Hibernate `validate`, DTO con Bean Validation, test su database reale |
| Review Codex | `PASS WITH FIXES` — 1 HIGH (R1), 3 MEDIUM, 3 LOW. Nessun BLOCKER |
| TASK-001A | R1 chiuso: schema e seed separati in due stream Flyway. Parte di R2 e delimitazioni di R4 recepite |
| Merge | `--no-ff` in `master`, branch conservato, storia non riscritta |

Artefatti: `tasks/TASK-001/*`, `tasks/TASK-001A/*`, `docs/adr/ADR-001`, `ADR-002`,
`ADR-003`, `docs/RUNNING.md`, `docs/reviews/TASK-001_CODEX_REVIEW.md`.

## Stato del sistema
- Database: **PostgreSQL 17** via `docker-compose.yml`, volume `aicompany_postgres_data`, porta su loopback.
- Schema: di proprietà di **Flyway** (`db/migration`, storia `flyway_schema_history`); Hibernate in `validate`. Una colonna mancante blocca l'avvio; la perdita di un `NOT NULL` **no** — delimitazione chiarita dalla review (R4).
- Seed di sviluppo: **stream Flyway separato** (`db/dev/V1`, storia `flyway_dev_seed_history`), applicato solo dal profilo `dev` da `DevSeedFlywayConfiguration`. `AgentInitializer` resta rimosso. Lo stream di schema è identico in tutti i profili, quindi `V2`, `V3`, … restano applicabili anche su un database seminato (ADR-003).
- Transizione automatica in `dev` per i database che contengono ancora `V1000`: la riga legacy viene rimossa dalla storia di schema, i dati restano.
- Profili: `dev` (default), `test` (Testcontainers), `prod` (sole variabili d'ambiente).
- API: DTO con Bean Validation su `agents` e `tasks`. `POST /api/tasks {}` → `400`. Creazione valida → `201` + `Location`.
- Test: **26**, tutti contro PostgreSQL reale. `./mvnw -B clean test` → BUILD SUCCESS.
- H2 rimosso dal progetto.

## Stato Git (verificato il 2026-09-11, dopo il merge)
- Branch corrente: **`master`**, HEAD `e8d0286` — merge commit di
  `task-001-persistence-foundation`.
- Merge eseguito con **`--no-ff`**: topologia del branch conservata, nessun fast-forward,
  nessuna riscrittura di storia, nessun branch cancellato.
- `task-001-persistence-foundation` **conservato** a `82ea1df`, interamente contenuto in
  `master`.
- **Nessun remote configurato, nessun push eseguito.** Una destinazione remota richiede
  approvazione esplicita.
- Working tree pulito.

Commit integrati in `master` con questo merge:

| Hash | Contenuto |
|---|---|
| `ea25bee` | backend applicativo preesistente |
| `503663c` | governance Company OS e artefatti TASK-000 |
| `2f01194` | review Codex di TASK-000 |
| `5e164f5` | TASK-001 — persistenza |
| `c295189` | TASK-001 — test |
| `b6e0b81` | TASK-001 — documentazione e ADR |
| `dba676b` | TASK-001A — separazione degli stream Flyway |
| `2a9912b` | TASK-001A — test di regressione |
| `82ea1df` | TASK-001A — ADR-003 e correzioni documentali |
| `e8d0286` | merge in `master` |

`master` prima del merge era a `930f70f` — «Initial project structure», l'unico commit che
conteneva già.

Verifiche eseguite **prima** del merge: working tree pulito, branch corretto, `master`
intatto a `930f70f`, `./mvnw -B clean test` → 26 test, 0 failure. Suite rieseguita **dopo**
il merge su `master`: 26 test, 0 failure, BUILD SUCCESS.

## Decisioni architetturali
- **ADR-001** — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato, non ancora implementato. *Accettata* (approvazione utente, 2026-09-11).
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway, verificato su database reale. *Accettata, parzialmente superata da ADR-003* (punto 6 e garanzia dev→prod).
- **ADR-003** — Il seed di sviluppo è uno stream Flyway separato dallo schema, con tabella di storia propria. *Accettata*.

## Prossimo passo proposto — preparazione di TASK-002

Sequenza rivista dalla review Codex: **contratti minimi e dominio con test → completamento
API/CI → sicurezza minima e contratto Run → Model Gateway → frontend minimo**.

**TASK-002 candidata: API contracts and domain model.** Lo stato del progetto è pronto a
riceverla; lo scope **non è ancora approvato** e la task **non è avviata**.

Cosa la rende eseguibile ora:

| Prerequisito | Stato |
|---|---|
| Schema versionato ed evolvibile | ✅ `V2` applicabile, dimostrato da test (ADR-003) |
| Rete di sicurezza sui test | ✅ 26 test su PostgreSQL reale |
| Drift di schema che blocca l'avvio | ✅ Hibernate `validate`, con portata documentata |
| Base integrata in `master` | ✅ merge `e8d0286` |

Scope candidato, da approvare:
1. Entità `Project` e relazione con `Task`.
2. Enum di dominio per `status` e `priority`, oggi stringhe libere obbligatorie — cambio di
   contratto osservabile, da dichiarare.
3. Migrazione `V2` corrispondente in `db/migration`.
4. Test di persistenza e di contratto per le nuove strutture.

Decisioni da prendere **prima** di avviare la task:
- Quali valori ammessi per `status` e `priority`, e se serve una macchina a stati o solo un
  insieme chiuso.
- Se `Task` debba appartenere obbligatoriamente a un `Project` (migrazione dei dati
  esistenti) oppure la relazione sia opzionale.
- Se introdurre `GET /api/tasks/{id}`, oggi assente benché `POST` emetta un header
  `Location` che punta a quell'URI (LOW della review, ancora aperto).

Alternativa a priorità più bassa, se si preferisce consolidare: una task documentale che
chiuda R3/R5/R6/R7 e le correzioni ai file `docs/audit/*` di TASK-000.

## Debito aperto rilevante
TD-04 sicurezza, TD-07 gestione errori, TD-08 `MasterOrchestrator`, TD-11 CORS, TD-12/TD-13 dominio, TD-14 CI assente, TD-15 Lombok inutilizzato, TD-17/TD-18 `README.md`.

Rilievi della review TASK-001 **ancora aperti**, indipendenti dalla strategia di migrazione
e non affrontati in TASK-001A: **R3** (`.env` configura Compose ma non il processo Maven),
**R5** (`server.address` non vincolato a loopback), **R6** (tag immagine mobile, nomi Compose
fissi), **R7** (`.gitignore` non copre `.env.*`), più il debito devtools sull'exit code di
`spring-boot:run` — quest'ultimo ora documentato in `docs/RUNNING.md` §2.
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
**Approvazione umana dello scope di TASK-002** (contratti API e modello di dominio), con le
tre decisioni elencate in «Prossimo passo proposto» prese prima dell'avvio.

Nessuna implementazione è autorizzata finché lo scope non è approvato: TASK-002 **non è
avviata**.
