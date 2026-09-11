# AI Company OS — Project State

## Project
AI Company OS

## Status
PHASE 1 in corso. **TASK-002 implementata, verificata e passata in review sul branch
`task-002-project-registry-foundation`** (2026-09-11). Review differenziale eseguita, i
quattro rilievi correggibili (F-1…F-4) sono stati applicati sullo stesso branch; i restanti
(F-5…F-10) sono registrati come debito. Non integrata in `master`.

## Current phase
PHASE 1 — Foundations (persistenza completata, primo dominio introdotto)

## Current task
**TASK-002 — Core Domain Model & Project Registry Foundation.** Implementazione completa,
suite verde, artefatti prodotti. Prossimo passo: review differenziale, poi decisione di merge.

## Last completed task
**TASK-002 — Core Domain Model & Project Registry Foundation** (implementata, non ancora
integrata).

Il Company OS ha il suo primo dominio reale: `Project`, tabella PostgreSQL creata da `V2`,
con CRUD, ciclo di vita esplicito e **archiviazione al posto della cancellazione fisica**.

Vincolo di scope rispettato: il progetto è *pronto a diventare* il contenitore di task,
agenti, documenti, framework, template, integrazioni e memoria — non lo è ancora. `V2` è
puramente additiva: crea una tabella e due indici, non tocca `agents` né `tasks`, non
introduce chiavi esterne.

| Elemento | Scelta |
|---|---|
| Entità | `Project(id, name, description, status, createdAt, updatedAt)`, `BIGINT` identity come le altre |
| Stato | Enum chiuso `ACTIVE` / `ARCHIVED`, imposto anche da `CHECK` nel database |
| Cancellazione | Nessun mapping `DELETE` → `405`. Si archivia |
| Transizioni | Sull'entità, non nel service. Transizione illegale → `409` |
| Unicità nome | Indice unico funzionale su `lower(name)`; il service usa la stessa normalizzazione e traduce in `409` la sola violazione di quell'indice |
| Modifica | Solo se `ACTIVE`: `PUT` su un progetto archiviato → `409`, prima va `restore`-ato (ADR-004 §8) |
| Errori | `ProblemDetail`, advice **limitato a `ProjectController`** |

API: `POST /api/projects`, `GET /api/projects[?status=]`, `GET /{id}`, `PUT /{id}`,
`POST /{id}/archive`, `POST /{id}/restore`.

Artefatti: `tasks/TASK-002/*`, `docs/adr/ADR-004`.

Prima, in `master`: **TASK-001 e TASK-001A — Reproducible Persistence Foundation**, ciclo
completo implementazione → review Codex (`PASS WITH FIXES`) → fix del rilievo HIGH → merge.
Artefatti: `tasks/TASK-001/*`, `tasks/TASK-001A/*`, `docs/adr/ADR-001`, `ADR-002`, `ADR-003`,
`docs/RUNNING.md`, `docs/reviews/TASK-001_CODEX_REVIEW.md`.

## Stato del sistema
- Database: **PostgreSQL 17** via `docker-compose.yml`, volume `aicompany_postgres_data`, porta su loopback.
- Schema: di proprietà di **Flyway** (`db/migration`, storia `flyway_schema_history`), oggi a **`V2`**; Hibernate in `validate`. Una colonna mancante blocca l'avvio; la perdita di un `NOT NULL` **no** — delimitazione chiarita dalla review (R4).
- Tabelle: `agents`, `tasks`, **`projects`**.
- Seed di sviluppo: **stream Flyway separato** (`db/dev/V1`, storia `flyway_dev_seed_history`), applicato solo dal profilo `dev` da `DevSeedFlywayConfiguration`. Lo stream di schema è identico in tutti i profili (ADR-003).
- Transizione automatica in `dev` per i database che contengono ancora `V1000`: la riga legacy viene rimossa dalla storia di schema, i dati restano.
- Profili: `dev` (default), `test` (Testcontainers), `prod` (sole variabili d'ambiente).
- API: DTO con Bean Validation su `agents`, `tasks` e `projects`. `POST /api/tasks {}` → `400`. `POST /api/projects {}` → `400` con elenco dei campi. Creazione valida → `201` + `Location`.
- Contratto di errore: `ProblemDetail` **solo** sotto `/api/projects`; `agents` e `tasks` conservano il default di Spring. Disomogeneità nota e testata, si chiude con TD-07.
- Test: **77**, contro PostgreSQL reale (erano 26; 66 a fine implementazione, 77 dopo le correzioni della review). `./mvnw -B clean test` → BUILD SUCCESS.
- H2 rimosso dal progetto.

## Stato Git (verificato il 2026-09-11)
- Branch corrente: **`task-002-project-registry-foundation`**, creato da `master` a `32174a1`.
- **`master` intatto**, nessun merge di TASK-002.
- **Nessun remote configurato, nessun push eseguito.** Una destinazione remota richiede approvazione esplicita.
- Storia non riscritta: nessun force push, reset, rebase o cancellazione di branch.
- Working tree pulito.

Commit di TASK-002:

| Hash | Contenuto |
|---|---|
| `4e4fa64` | `feat(project)` — dominio `Project`, `V2`, repository/service/controller, DTO, advice |
| `9fb3029` | `test(project)` — 40 test nuovi, rinumerazione della fixture di test a `V900` |
| HEAD | `docs(task-002)` — ADR-004, artefatti, aggiornamento di questo file |

Branch precedenti conservati: `task-000-audit`, `task-001-persistence-foundation`.

## Decisioni architetturali
- **ADR-001** — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato, non ancora implementato. *Accettata*.
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway, verificato su database reale. *Accettata, parzialmente superata da ADR-003*.
- **ADR-003** — Il seed di sviluppo è uno stream Flyway separato dallo schema, con tabella di storia propria. *Accettata*.
- **ADR-004** — Project Registry: `Project` è un'entità autonoma senza relazioni in TASK-002; insieme di stati chiuso imposto due volte (enum + `CHECK`); si archivia invece di cancellare; transizione illegale → `409`; unicità del nome garantita dal database, con il service che ne usa la stessa normalizzazione; un progetto archiviato non è modificabile (§8); contratto di errore limitato al modulo. *Accettata*.

## Prossimo passo proposto

1. ~~Review differenziale di TASK-002~~ — **fatta**. F-1…F-4 corretti sul branch, F-5…F-10
   registrati come TD-19…TD-24.
2. **Decisione di merge** di `task-002-project-registry-foundation` in `master`. Restano
   aperte, come domande e non come difetti, le tre scelte di contratto in
   `tasks/TASK-002/HANDOFF.md`: `archive` non idempotente, `GET` senza filtro che include
   gli archiviati, advice limitato a un controller.
3. Solo dopo, definizione dello scope di **TASK-003**.

Candidati naturali per TASK-003, **nessuno approvato**:

| Candidato | Nota |
|---|---|
| Relazione `Task` → `Project` | Richiede le tre decisioni rimandate da ADR-004 §1: appartenenza obbligatoria o no, cosa fare delle righe esistenti, cosa significa archiviare un progetto con task aperti. **Blocca TD-19**: dà ad `archive` effetti su entità figlie, quindi il controllo di concorrenza va deciso prima |
| Enum di dominio su `Task.status` / `priority` | Cambio di contratto osservabile, da dichiarare |
| `GET /api/tasks/{id}` | LOW della review TASK-001, ancora aperto |
| TD-07 — contratto di errore uniforme | Chiuderebbe la disomogeneità introdotta consapevolmente da TASK-002 |
| Task documentale | Chiude R3/R5/R6/R7 e le correzioni ai file `docs/audit/*` di TASK-000 |

## Debito aperto rilevante
TD-04 sicurezza, TD-07 gestione errori, TD-08 `MasterOrchestrator`, TD-11 CORS, TD-12/TD-13 dominio, TD-14 CI assente, TD-15 Lombok inutilizzato, TD-17/TD-18 `README.md`.

### Debito nuovo dalla review di TASK-002 (registrato, non implementato)

| ID | Rilievo | Contenuto |
|---|---|---|
| **TD-19** | F-5 | **Nessun controllo di concorrenza.** Nessun `@Version` su `Project`, nessun lock: due `archive` concorrenti rispondono entrambi `200` invece che `200` + `409`, e due `PUT` concorrenti si sovrascrivono in silenzio. **Requisito da rivalutare prima di introdurre qualunque effetto di `archive`/`restore` su entità figlie** — cioè prima della relazione `Task` → `Project`: finché archiviare non tocca nient'altro il costo è un `200` di troppo, dopo diventa un lost update con conseguenze |
| **TD-20** | F-6 | Contratto di errore disomogeneo *dentro* il modulo project: JSON malformato o `Content-Type` mancante su `/api/projects` non producono un `ProblemDetail`. Si chiude naturalmente con TD-07 |
| **TD-21** | F-7 | Asserzione debole in `theProjectErrorContractDoesNotLeakIntoTheTaskApi`: verifica solo l'assenza di `$.errors`, resterebbe verde se `agents`/`tasks` passassero a `ProblemDetail` senza quella proprietà |
| **TD-22** | F-8 | AC-1 non ha copertura automatica del percorso *incrementale*: nessun test porta un database da `V1` a `V2` e verifica che `agents` e `tasks` restino intatte. Verificato solo a mano sul volume di sviluppo |
| **TD-23** | F-9 | `MigrationStreamTest` legge le versioni da Flyway ma fissa ancora l'elenco delle tabelle: la prossima migrazione che crea una tabella lo rompe comunque. Scelta accettabile, affermazione da correggere dove è scritta |
| **TD-24** | F-10 | `ProjectService.findById` è `@Transactional(readOnly = true)` e viene chiamato da `update`/`archive`/`restore` via `this.`: le scritture funzionano perché il self-invocation aggira il proxy. Passare a proxy AspectJ, o estrarre il lookup, le romperebbe |

Rilievi della review TASK-001 **ancora aperti**: **R3** (`.env` configura Compose ma non il
processo Maven), **R5** (`server.address` non vincolato a loopback), **R6** (tag immagine
mobile, nomi Compose fissi), **R7** (`.gitignore` non copre `.env.*`), più il debito devtools
sull'exit code di `spring-boot:run` — quest'ultimo documentato in `docs/RUNNING.md` §2.
Correzioni documentali richieste dalla review agli artefatti `docs/audit/*` di TASK-000:
ancora da applicare.

Debito nuovo, contratto consapevolmente da TASK-002:
- due forme di errore coesistenti nell'API, fino a TD-07;
- flusso a tre richieste (`restore` → `PUT` → `archive`) per correggere un progetto archiviato, conseguenza accettata di ADR-004 §8;
- nessuna paginazione su `GET /api/projects`;
- nessuno slug pubblico stabile: il nome è anche la chiave naturale;
- `docs/RUNNING.md` non documenta ancora gli endpoint `/api/projects`.

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
- Project Registry ✅ *fondazione introdotta da TASK-002*
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
**Review di TASK-002 e decisione di merge.** TASK-003 **non è avviata** e il suo scope non è
approvato.
