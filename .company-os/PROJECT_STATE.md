# AI Company OS — Project State

> **Fonte primaria dello stato.** Una sessione nuova, senza cronologia di chat, deve poter
> leggere questo file più `AUTONOMOUS_CHARTER.md` e `AUTONOMOUS_LOOP.md` e continuare il loop.

## Project
AI Company OS

## Modalità di lavoro
**Autonomous Project Mode con Human Final Review**, attiva dal 2026-09-14.
Autorità e hard stop: `.company-os/AUTONOMOUS_CHARTER.md`. Procedura: `.company-os/AUTONOMOUS_LOOP.md`.
L'agente definisce, implementa, revisiona e chiude le task senza approvazione intermedia.
`master` è il gate umano finale e non si tocca.

## Status
PHASE 1 in corso. **TASK-004 completata e integrata in `autonomous/phase-1-foundations`**
(2026-09-14). Suite **126 test verdi**. Nessun failure aperto.

## Current phase
PHASE 1 — Foundations (persistenza, primo dominio, prima relazione, **prima invariante di
concorrenza**)

## Current task
**Nessuna in corso.** La prossima è **TASK-005 — Uniform Error Contract (TD-07)**, scelta
autonomamente; motivazione nel «Prossimo passo».

## Last completed task

**TASK-004 — Archival Consistency & Project Lock Protocol** (2026-09-14).

Chiude l'incoerenza che ADR-005 §4 aveva lasciato dichiarata: l'archiviazione di un progetto
rifiutava lavoro nuovo ma non proteggeva il lavoro che conteneva — un task usciva da un
progetto archiviato con un `200`.

| Decisione | Contenuto |
|---|---|
| Consistenza **derivata** | `archive`/`restore` scrivono **una sola riga**, la propria. Zero `UPDATE` su `tasks`. Cambia la regola, non il dato. `restore` è l'inverso esatto **per costruzione**: non c'è niente da ricordare |
| Congelamento | Un task il cui progetto è `ARCHIVED` non si sposta → `409`. Letture invariate. `PUT` verso lo **stesso** progetto archiviato → `200` no-op |
| Protocollo **L0–L7** | La riga del task esclusiva **prima** di leggerne l'associazione (L0); le righe dei progetti da cui la decisione dipende, condivise (L2–L4); ordine globale `tasks` → `projects` per id crescente (L5); le letture non bloccano (L6); il protocollo è universale (L7) |
| Nessuna migrazione | Schema fermo a **`V3`**. Nessuna colonna, nessun vincolo, nessun `@Version` |
| Contratto invariato | `TaskResponse` identico a TASK-003 |

**Unico cambio di contratto osservabile**: `PUT /api/tasks/{id}/project` passa da `200` a `409`
quando **sposta** un task fuori da un progetto archiviato.

Un test ha riprodotto, prima che il codice esistesse, un bypass della regola per **staleness**
che i soli lock sui progetti non intercettano — lo scrittore stantio tiene `{A, B}` e l'archive
tocca `C`, insiemi disgiunti. Da lì **L0**, approvato il 2026-09-14.

Artefatti: `tasks/TASK-004/*`, `docs/adr/ADR-006-archival-consistency-and-project-serialization.md`.

## Task precedenti

- **TASK-003 — Task → Project Association Foundation** (merged in `master`, 2026-09-12).
  Prima relazione persistente: `Task` → `Project`, chiave esterna da `V3`, mapping JPA
  unidirezionale, API di assegnazione e listato per progetto. ADR-005.
- **TASK-002 — Core Domain Model & Project Registry Foundation** (merged in `master`,
  2026-09-11). `Project`, tabella da `V2`, ciclo di vita esplicito, archiviazione al posto
  della cancellazione. ADR-004.
- **TASK-001 / TASK-001A — Reproducible Persistence Foundation** (merged in `master`).
  PostgreSQL, schema di proprietà di Flyway, seed dev in stream separato. ADR-001, ADR-002,
  ADR-003.

## Stato del sistema

- Database: **PostgreSQL 17** via `docker-compose.yml`, volume `aicompany_postgres_data`.
- Schema: di proprietà di **Flyway**, oggi a **`V3`**. Hibernate in `validate`.
  **TASK-004 non ha aggiunto migrazioni.**
- Tabelle: `agents`, `tasks`, `projects`. `tasks.project_id` nullable con FK senza `ON DELETE`.
- Seed di sviluppo: stream Flyway separato (`db/dev/V1`), profilo `dev` (ADR-003).
- Profili: `dev` (default), `test` (Testcontainers), `prod`.
- **Concorrenza**: protocollo di lock L0–L7 su `tasks` e `projects` (ADR-006 §4). Lock
  bloccanti ordinari: nessun `lock_timeout`, nessuna policy di retry, nessun `503`.
- Contratto di errore: **tre forme convivono** — `ProblemDetail` sotto `/api/projects`,
  `ProblemDetail` sulle sole risposte di errore introdotte da TASK-003 e TASK-004 sotto
  `/api/tasks`, il default di Spring altrove. Disomogeneità nota, si chiude con **TD-07**.
- Test: **126** (erano 109 in `master`). `./mvnw -B clean test` → BUILD SUCCESS.
- H2 rimosso.

## Stato Git (verificato il 2026-09-14)

- **`master`**: fermo a `d5ff121`. **Gate umano finale, nessun merge autonomo.**
- **Integration branch**: `autonomous/phase-1-foundations`, HEAD **`77c2071`**.
- Branch di lavoro: `task-004-archival-consistency`, integrato in fast-forward.
- **Nessun remote configurato, nessun push eseguito.**
- Storia lineare, mai riscritta.

Commit di TASK-004, ora nell'integration branch:

| Hash | Contenuto |
|---|---|
| `f3730af` | `docs(task-004)` — scope approvato |
| `add5cd9` | `test(project)` — le due race, deliberatamente rosse sulla baseline |
| `430b001` | `test(project)` — il bypass per staleness, riprodotto |
| `934a954` | `docs(governance)` — Autonomous Project Mode |
| `6d0f23d` | `docs(task-004)` — L0, ordine globale, TD-30 ristretto |
| `736a22d` | `feat(project,task)` — implementazione |
| `77c2071` | `test(project,task)` — protocollo, regola, ragionamento |

Branch conservati: `task-000-audit`, `task-001-persistence-foundation`,
`task-002-project-registry-foundation`, `task-003-task-project-association`,
`task-004-archival-consistency`.

## Decisioni architetturali

- **ADR-001** — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato. *Accettata*.
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway. *Accettata, parzialmente superata da ADR-003*.
- **ADR-003** — Il seed di sviluppo è uno stream Flyway separato. *Accettata*.
- **ADR-004** — Project Registry: stati chiusi imposti due volte, si archivia invece di cancellare, transizione illegale → `409`, unicità del nome nel database, un archiviato non è modificabile (§8). *Accettata*.
- **ADR-005** — Relazione `Task` → `Project`: `project_id` nullable, i task preesistenti non si migrano, associare a un `ARCHIVED` è `409`, relazione unidirezionale, FK senza `ON DELETE`. *Accettata*.
- **ADR-006** — Coerenza archiviazione → task **derivata** (nessuna scrittura sui figli, `restore` inverso per costruzione), congelamento in scrittura con letture aperte, `PUT` idempotente `200` no-op, protocollo di lock **L0–L7** con ordine globale `tasks` → `projects`, nessuna migrazione, contratto invariato, nessun `503`. *Accettata e **implementata**.*

## Prossimo passo autonomo

**TASK-005 — Uniform Error Contract (TD-07).**

Scelta secondo `AUTONOMOUS_LOOP.md` §4. I livelli 1 e 2 sono vuoti — nessun invariante promesso
è falso, nessun blocker architetturale aperto. Il livello 3 seleziona TD-07:

- è **debito che peggiora a ogni task**: oggi l'API parla tre dialetti di errore, e ogni
  endpoint nuovo deve sceglierne uno o aggiungerne un quarto;
- **assorbe TD-20, TD-27 e TD-29**, che sono tutti aspetti dello stesso contratto mancante;
- ogni pezzo della target architecture che seguirà (Agent Registry, Planner, Model Gateway)
  aggiunge superficie API: farlo dopo costa più che farlo adesso.

Primo passo: branch `task-005-uniform-error-contract` dall'integration branch, artefatti di
scope e ADR-007, poi i test che dimostrano le tre forme oggi coesistenti.

## Debito aperto rilevante

**Chiusi da TASK-004**: TD-19 (componente ciclo di vita), **TD-24**, **TD-25**.

### Alto valore

| ID | Contenuto |
|---|---|
| **TD-07** | Contratto di errore non uniforme: tre forme convivono. **Assorbe TD-20, TD-27, TD-29.** Prossima task |
| **TD-28** | `PUT /api/projects/{id}` esposto alla sovrascrittura con dati stantii. Il lock serializza ma non rileva. Richiede `ETag`/`If-Match` nel contratto HTTP |
| **TD-30** | *(MINOR, ristretto)* Riassegnazioni concorrenti dello stesso task: last-write-wins **su stato fresco**. L0 le serializza e ciascuna applica le regole ai dati che trova; manca la **rilevazione** dell'intento stantio. Gemello di TD-28 sull'altra entità |

### Assorbiti da TD-07

| ID | Contenuto |
|---|---|
| **TD-20** | JSON malformato o `Content-Type` mancante su `/api/projects` non producono `ProblemDetail` |
| **TD-27** | Path variable non numerico: due forme di errore sulla stessa rotta |
| **TD-29** | *(MINOR)* Nessun contratto API normalizzato per gli errori infrastrutturali di concorrenza e locking. **Non implica timeout, retry o `503`** |

### Qualità dei test e migrazioni

| ID | Contenuto |
|---|---|
| **TD-21** | Asserzione debole in `theProjectErrorContractDoesNotLeakIntoTheTaskApi` |
| **TD-22** | Nessun test di upgrade incrementale `V1` → `V2`. Il modello esiste già in `MigrationStreamTest` |
| **TD-23** | `MigrationStreamTest` fissa ancora l'elenco delle tabelle |
| **TD-26** | Il path lazy non è esercitato fuori transazione. **Parzialmente coperto** da TASK-004: un test pinna che leggere `projectId` non inizializzi il proxy |

### Preesistente

TD-04 sicurezza, TD-08 `MasterOrchestrator`, TD-11 CORS, TD-12/TD-13 dominio, TD-14 CI assente,
TD-15 Lombok inutilizzato, TD-17/TD-18 `README.md`.

Rilievi della review TASK-001 ancora aperti: **R3** (`.env` non configura il processo Maven),
**R5** (`server.address` non vincolato a loopback), **R6** (tag immagine mobile), **R7**
(`.gitignore` non copre `.env.*`). Correzioni documentali ai file `docs/audit/*` di TASK-000:
da applicare. `docs/RUNNING.md` non documenta `/api/projects` né gli endpoint di TASK-003.

## Failure aperti

**Nessuno.** 126/126 verdi.

## Domande di contratto aperte

Da decidere insieme, quando esisterà un client reale che le pone:

- nessun modo di sapere in anticipo che un task è congelato: lo si scopre dal `409`;
- nessun `DELETE /api/tasks/{id}/project`: `NULL → non NULL` resta a senso unico;
- nessun filtro «task senza progetto» su `GET /api/tasks`;
- `archive` non idempotente; `GET /api/projects` senza filtro include gli archiviati;
- nessuna paginazione; nessuno slug pubblico stabile.

## Working principles
- **Autonomous execution con Human Final Review** (`AUTONOMOUS_CHARTER.md`).
- Persistent project artifacts instead of long chat histories.
- Minimal context loading.
- Invarianti prima del codice; rosso prima di verde; mutazione su ciò che è portante.
- Never rewrite or discard existing working code without evidence and justification.
- Un debito non si chiude perché il codice sembra diverso.

## Target architecture
- Project Registry ✅ *fondazione (TASK-002), relazione con i task (TASK-003), coerenza di archiviazione e concorrenza (TASK-004)*
- Agent Registry
- Skills / Rules / Subagents / Tools / MCP Registry
- Model Gateway and local/cloud routing
- Context / Prompt / Harness / Loop / Graph Engineering
- Visual Code Architecture Graph, Knowledge Vault, Memory Graph
- Planner
- GitHub / GitLab, ClickUp, Google Drive / Gmail, software adapters
- Template Hub, Framework Explorer, External AI workspaces
- Voice Interaction Layer, Payments / quota monitoring, 3D Omniverse integration

## Immediate goal
Completare PHASE 1 in modo autonomo sull'integration branch, poi preparare `FINAL_HANDOFF.md`
per la review umana. **Nessun merge in `master`.**
