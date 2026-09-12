# AI Company OS — Project State

## Project
AI Company OS

## Status
PHASE 1 in corso. **TASK-003 implementata, revisionata e corretta sul branch
`task-003-task-project-association`** (2026-09-12). Review indipendente eseguita: i due
rilievi obbligatori (M-1, M-2, entrambi di qualità dei test) sono stati chiusi e verificati
per mutazione; i LOW restano aperti, due dei quali registrati come TD-26 e TD-27. Suite
completa verde (**109 test**). Nessun merge, nessun push.

TASK-002 resta completata, revisionata e integrata in `master`.

## Current phase
PHASE 1 — Foundations (persistenza completata, primo dominio introdotto, prima relazione di
dominio introdotta)

## Current task
**TASK-003 — Task → Project Association Foundation.** Implementata, revisionata, corretta,
**non integrata**. Branch `task-003-task-project-association`, creato da `master` (`c73fa39`).

Introduce la prima relazione persistente del Company OS: `Task` → `Project`, con chiave
esterna PostgreSQL creata da `V3`, mapping JPA unidirezionale, API di assegnazione e listato
per progetto.

Vincolo di scope rispettato: la relazione **esiste ed è imposta dal database**, ma
`archive`/`restore` di un progetto **non producono ancora nessun effetto** sui suoi task.

Le tre domande che ADR-004 §1 aveva esplicitamente rimandato sono state sciolte prima di
implementare, e sono il contenuto di **ADR-005**:

| Domanda | Decisione |
|---|---|
| `project_id` nullable in questa fase? | **Sì.** Nessun valore corretto esiste per le righe preesistenti; un progetto sintetico «Unassigned» di backfill sarebbe un workaround indelebile che asserisce il falso |
| Come migrare i task esistenti? | **Non si migrano.** `V3` non contiene `UPDATE`. Restano `project_id NULL`, stato esplicito nel contratto (`projectId: null`) e recuperabile con `PUT /api/tasks/{id}/project` |
| Associare a un progetto `ARCHIVED`? | **`409`.** Un contenitore fuori dal registro operativo non riceve lavoro nuovo. `409` e non `403` perché il chiamante può togliere il rifiuto da sé: `restore`, e la stessa richiesta passa |

Decisioni conseguenti: relazione unidirezionale (nessuna `@OneToMany` su `Project`, così la
cascata non può essere un flag); chiave esterna senza `ON DELETE` (`NO ACTION` è la scelta);
nessun `DELETE /api/tasks/{id}/project`; `PUT` sull'associazione idempotente; progetto
inesistente su `GET /api/projects/{id}/tasks` → `404` e non lista vuota; advice sui task
limitato alle sole eccezioni nuove, così la forma del `400` preesistente non cambia.

API nuova o modificata:
`POST /api/tasks` con `projectId` opzionale, `GET /api/tasks` con `projectId` in uscita,
`PUT /api/tasks/{id}/project`, `GET /api/projects/{projectId}/tasks`.

Artefatti: `tasks/TASK-003/*`, `docs/adr/ADR-005-task-project-association.md`.

## Last completed task
**TASK-002 — Core Domain Model & Project Registry Foundation** (implementata, revisionata,
corretta e **merged in `master` il 2026-09-11**).

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
- Schema: di proprietà di **Flyway** (`db/migration`, storia `flyway_schema_history`), oggi a **`V3`** sul branch di TASK-003 e **`V2`** in `master`; Hibernate in `validate`. Una colonna mancante blocca l'avvio; la perdita di un `NOT NULL` **no** — delimitazione chiarita dalla review (R4).
- Tabelle: `agents`, `tasks`, **`projects`**. Dal branch di TASK-003, `tasks.project_id` nullable con chiave esterna `tasks_project_id_fkey` verso `projects(id)`, senza `ON DELETE`, e indice `tasks_project_id_idx`.
- Seed di sviluppo: **stream Flyway separato** (`db/dev/V1`, storia `flyway_dev_seed_history`), applicato solo dal profilo `dev` da `DevSeedFlywayConfiguration`. Lo stream di schema è identico in tutti i profili (ADR-003).
- Transizione automatica in `dev` per i database che contengono ancora `V1000`: la riga legacy viene rimossa dalla storia di schema, i dati restano.
- Profili: `dev` (default), `test` (Testcontainers), `prod` (sole variabili d'ambiente).
- API: DTO con Bean Validation su `agents`, `tasks` e `projects`. `POST /api/tasks {}` → `400`. `POST /api/projects {}` → `400` con elenco dei campi. Creazione valida → `201` + `Location`.
- Contratto di errore: `ProblemDetail` sotto `/api/projects` e — dal branch di TASK-003 — sulle sole risposte di errore **introdotte da TASK-003** sotto `/api/tasks` e `/api/projects/{id}/tasks`. Gli endpoint preesistenti di `agents` e `tasks` conservano il default di Spring, validazione inclusa. Disomogeneità nota e testata, si chiude con TD-07.
- Test: **77** in `master`, **109** sul branch di TASK-003 dopo le correzioni della review, tutti contro PostgreSQL reale tranne i 3 strutturali sull'advice. `./mvnw -B clean test` → BUILD SUCCESS in entrambi.
- H2 rimosso dal progetto.

## Stato Git (verificato il 2026-09-12)
- Branch corrente: **`task-003-task-project-association`**, creato da `master` (`c73fa39`).
- **Nessun merge, nessun push.** `master` è intatto a `c73fa39`.
- **Nessun remote configurato.** Una destinazione remota richiede approvazione esplicita.
- Storia non riscritta: nessun force push, reset, rebase o cancellazione di branch.
- Suite sul branch di TASK-003: **109/109 verdi**, BUILD SUCCESS.

TASK-002 resta integrata in `master` con fast-forward (`32174a1..c5133d3`), storia lineare,
suite verde:

| Hash | Contenuto |
|---|---|
| `4e4fa64` | `feat(project)` — dominio `Project`, `V2`, repository/service/controller, DTO, advice |
| `9fb3029` | `test(project)` — 40 test nuovi, rinumerazione della fixture di test a `V900` |
| `3ddb3b8` | `docs(task-002)` — ADR-004 e artefatti di task |
| `c5133d3` | `fix(project)` — chiusura dei rilievi di review F-1…F-4, test relativi, documentazione |

Branch conservati, non cancellati: `task-000-audit`, `task-001-persistence-foundation`,
`task-002-project-registry-foundation`, `task-003-task-project-association`.

## Decisioni architetturali
- **ADR-001** — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato, non ancora implementato. *Accettata*.
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway, verificato su database reale. *Accettata, parzialmente superata da ADR-003*.
- **ADR-003** — Il seed di sviluppo è uno stream Flyway separato dallo schema, con tabella di storia propria. *Accettata*.
- **ADR-004** — Project Registry: `Project` è un'entità autonoma senza relazioni in TASK-002; insieme di stati chiuso imposto due volte (enum + `CHECK`); si archivia invece di cancellare; transizione illegale → `409`; unicità del nome garantita dal database, con il service che ne usa la stessa normalizzazione; un progetto archiviato non è modificabile (§8); contratto di errore limitato al modulo. *Accettata*.
- **ADR-005** — Relazione `Task` → `Project`: `project_id` nullable in questa fase, con la ragione dichiarata; i task preesistenti non si migrano e restano senza progetto; associare a un progetto `ARCHIVED` è `409`; `archive`/`restore` **non** hanno effetti sui task, e la relazione è unidirezionale proprio perché la cascata resti una decisione da scrivere e non un flag; chiave esterna senza `ON DELETE`; `PUT` sull'associazione idempotente; solo le risposte di errore nuove parlano `ProblemDetail`. *Accettata*.

## Prossimo passo proposto

1. ~~Review differenziale di TASK-003~~ — **fatta**. M-1 e M-2 chiusi e verificati per
   mutazione, LOW-1…LOW-6 non corretti, TD-26 e TD-27 registrati.
2. **Decisione di merge di TASK-003.**
3. Definizione dello scope di TASK-004. **Non ancora avvenuta.**

Candidati per TASK-004, **nessuno approvato**:

| Candidato | Nota |
|---|---|
| Cascata `archive`/`restore` da `Project` a `Task` | È il nodo successivo naturale. **Richiede di sciogliere prima TD-19 e TD-25**: dal momento in cui `archive` riscrive anche i task, il lost update smette di essere un `200` di troppo |
| `project_id` verso `NOT NULL` | Richiede prima un percorso che assegni tutto ciò che è rimasto scoperto, e una migrazione che lo verifichi |
| Enum di dominio su `Task.status` / `priority` | Cambio di contratto osservabile, da dichiarare |
| `GET /api/tasks/{id}` | LOW della review TASK-001, ancora aperto |
| TD-07 — contratto di errore uniforme | Oggi tre forme convivono: `ProblemDetail` sotto `/api/projects`, `ProblemDetail` sulle sole risposte nuove sotto `/api/tasks`, il default di Spring altrove |
| Task documentale | Chiude R3/R5/R6/R7, le correzioni ai file `docs/audit/*` di TASK-000 e `docs/RUNNING.md`, che non documenta né `/api/projects` né gli endpoint di TASK-003 |

Restano aperte le **tre scelte di contratto** di TASK-002 — `archive` non idempotente,
`GET /api/projects` senza filtro include gli archiviati, advice limitato a un controller —
più due nuove, dichiarate in ADR-005 e ancora domande di progetto e non difetti:

| Scelta | Domanda aperta |
|---|---|
| Nessun `DELETE /api/tasks/{id}/project` | `NULL → non NULL` è una porta a senso unico: un task assegnato non torna mai «senza progetto» |
| `GET /api/tasks` senza filtri | Non c'è modo di chiedere «i task senza progetto», che è proprio il caso d'uso di chi deve sistemare le righe preesistenti |

## Debito aperto rilevante
TD-04 sicurezza, TD-07 gestione errori, TD-08 `MasterOrchestrator`, TD-11 CORS, TD-12/TD-13 dominio, TD-14 CI assente, TD-15 Lombok inutilizzato, TD-17/TD-18 `README.md`.

### Debito nuovo dalla review di TASK-002 (registrato, non implementato)

| ID | Rilievo | Contenuto |
|---|---|---|
| **TD-19** | F-5 | **Ancora aperto dopo TASK-003, e correttamente:** la condizione di rivalutazione — «prima di dare ad `archive`/`restore` effetti su entità figlie» — **non è scattata**, perché TASK-003 introduce la relazione ma non la cascata e `archive` continua a toccare solo la propria riga. Da sciogliere, insieme a TD-25, nella task che introdurrà la cascata. **Nessun controllo di concorrenza.** Nessun `@Version` su `Project`, nessun lock: due `archive` concorrenti rispondono entrambi `200` invece che `200` + `409`, e due `PUT` concorrenti si sovrascrivono in silenzio. Finché archiviare non tocca nient'altro il costo è un `200` di troppo; dal momento in cui `archive` riscriverà anche i task diventa un lost update con conseguenze |
| **TD-20** | F-6 | Contratto di errore disomogeneo *dentro* il modulo project: JSON malformato o `Content-Type` mancante su `/api/projects` non producono un `ProblemDetail`. Si chiude naturalmente con TD-07 |
| **TD-21** | F-7 | Asserzione debole in `theProjectErrorContractDoesNotLeakIntoTheTaskApi`: verifica solo l'assenza di `$.errors`, resterebbe verde se `agents`/`tasks` passassero a `ProblemDetail` senza quella proprietà |
| **TD-22** | F-8 | AC-1 di TASK-002 non ha copertura automatica del percorso *incrementale*: nessun test porta un database da `V1` a `V2` e verifica che `agents` e `tasks` restino intatte. TASK-003 ha aggiunto il test equivalente per `V2` → `V3` — `MigrationStreamTest.taskProjectRelationIsAddedToAPopulatedV2Database`, che usa `Flyway.target` — quindi il **modello** del test ora esiste e applicarlo a `V1` → `V2` è meccanico. Il caso `V1` → `V2` resta comunque scoperto |
| **TD-23** | F-9 | `MigrationStreamTest` legge le versioni da Flyway ma fissa ancora l'elenco delle tabelle: la prossima migrazione che crea una tabella lo rompe comunque. Scelta accettabile, affermazione da correggere dove è scritta |
| **TD-24** | F-10 | `ProjectService.findById` è `@Transactional(readOnly = true)` e viene chiamato da `update`/`archive`/`restore` via `this.`: le scritture funzionano perché il self-invocation aggira il proxy. Passare a proxy AspectJ, o estrarre il lookup, le romperebbe |

### Debito nuovo da TASK-003 (registrato, non implementato)

| ID | Contenuto |
|---|---|
| **TD-26** | **Il path lazy non è esercitato fuori da una transazione.** Tutte le letture di produzione risolvono il progetto con un `join fetch`, quindi `Task.project` viene creato come proxy ma inizializzato solo dentro `TaskProjectRelationPersistenceTest.associationSurvivesAWriteAndReadCycle`. Nessun test copre il caso che in produzione fallirebbe davvero: una query senza `join fetch` il cui risultato viene letto a contesto di persistenza chiuso. La prossima repository method scritta senza `join fetch` produrrà `LazyInitializationException` a runtime e la suite resterà verde |
| **TD-27** | **Path variable non numerico: due forme di errore sullo stesso endpoint.** `GET /api/projects/abc/tasks` e `PUT /api/tasks/abc/project` rispondono `400` con la forma di default di Spring, mentre `GET /api/projects/999/tasks` risponde `ProblemDetail`. Stessa rotta, due dialetti, a seconda che l'identificatore sia sbagliato o assente. Stessa famiglia di TD-20: **da affrontare insieme alla normalizzazione futura degli errori (TD-07)**, non da sola |
| **TD-25** | **Finestra di concorrenza sull'assegnazione.** Fra il controllo «il progetto è `ACTIVE`» e il `commit` dell'assegnazione, un altro thread può archiviare il progetto: il task finisce attaccato a un progetto archiviato. Oggi la conseguenza si esaurisce lì — è uno stato che il sistema già ammette, perché un progetto si archivia liberamente con task dentro (ADR-005 §4) — e nessuna regola successiva lo usa. **Va chiusa insieme a TD-19, con lo stesso meccanismo**, nel momento in cui `archive` acquisterà effetti sui figli |

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
- Project Registry ✅ *fondazione introdotta da TASK-002; primo collegamento — i task — introdotto da TASK-003*
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
**Decisione di merge di TASK-003.** La task è implementata, revisionata e corretta sul
branch `task-003-task-project-association`, suite verde a 109 test, upgrade `V2 → V3`
verificato due volte: in Testcontainers su un database popolato fermato a `V2`, e sul volume
di sviluppo reale `aicompany_postgres_data`. TASK-004 **non è avviata** e il suo scope non
è approvato.

Il nodo successivo, qualunque sia la task che lo affronterà, è la cascata
`archive`/`restore` verso i task: richiede di sciogliere **TD-19** e **TD-25** prima di
scriverne il codice, non dopo.
