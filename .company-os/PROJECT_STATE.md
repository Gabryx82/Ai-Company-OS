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
**PHASE 1 — Foundations: COMPLETA.** Integrata in `autonomous/phase-1-foundations` (`0a35ac0`,
2026-09-14), in attesa di review umana: `FINAL_HANDOFF.md`. **Niente in `master`.**

**PHASE 2 — Assignment: in corso** dal 2026-09-15, su `autonomous/phase-2-assignment`, creato da
`autonomous/phase-1-foundations`. **TASK-008 completata** (2026-09-16). Suite **179 test verdi**,
schema **`V5`**, nessun failure aperto, nessun remote.

## Current phase
**PHASE 2 — Assignment.** Obiettivo, scope, motivazione livello per livello e criterio di
chiusura: **`.company-os/PHASE_2_PLAN.md`**. In una riga: *il Company OS sa dire chi lavora su
che cosa, e due client non possono sovrascriversi in silenzio mentre lo dicono.*

PHASE 1 resta com'è: `master` è ancora il gate di quella fase, e PHASE 2 ci si costruisce sopra
senza mergiarla.

## Current task
**TASK-009 — Relazione `Task` → `Agent`.** Livello 4 di `AUTONOMOUS_LOOP.md` §4: il livello 3 è
vuoto da quando TASK-008 ha chiuso TD-28 e TD-30. Non ancora avviata.

Eredita il protocollo di precondizione **per costruzione** (P4): `PUT /api/tasks/{id}/agent` nasce
con `If-Match`, e `PreconditionCoverageTest` non lascia aggiungere un write path senza dichiararlo.
Le tre domande di dominio da porre prima del codice e il punto sull'aciclicità dei lock sono in
`tasks/TASK-008/HANDOFF.md`.

## Last completed task

**TASK-008 — Optimistic concurrency nel contratto HTTP** (2026-09-16).

Chiude **TD-28** e **TD-30**, apre **TD-32** e **TD-33**. Suite **158 → 179**, schema **`V5`**.

I lock di ADR-006 serializzano ma non rilevano. Adesso il sistema fa entrambe le cose, con due
meccanismi che **non si sostituiscono**: L0–L7 è consistenza interna, `ETag`/`If-Match` è intento
stantio del client. Il lock rende **atomico** il confronto, il confronto rende **visibile** la
staleness.

| Decisione | Contenuto |
|---|---|
| **`@Version` è il contatore, non il rilevatore** | Dopo l'attesa su `PESSIMISTIC_WRITE` l'entità è caricata **già alla versione nuova**, quindi `OptimisticLockException` non arriva mai. **Nessun handler per essa**, e non va aggiunto: sarebbe un `412` che non scatta |
| Protocollo **P0–P4** | `If-Match` obbligatorio su ogni mutazione di risorsa esistente; confronto **dentro la transazione, dopo il lock, prima delle regole**; la versione conta la propria riga; clausola di chiusura ereditaria |
| `428` / `412` / `400` | Assente / stantio / illeggibile o `*`. Tre `type` nuovi, dentro ADR-007 senza eccezioni |
| Percorso canonico | **`GET /api/tasks/{id}`**, introdotto perché non esisteva. `ETag` anche su creazioni e mutazioni. Mai sui listati (TD-33), mai nei corpi JSON |
| `V5` additiva | `version BIGINT NOT NULL DEFAULT 0` su tutte e tre le tabelle. Il `DEFAULT` è la lezione di `V4` |
| Tutte e tre le risorse | Non solo le due rotte del debito: una precondizione con buchi non è una precondizione (L7 è il precedente) |
| **P4 eseguibile** | `PreconditionCoverageTest` pinna l'insieme dei write path: aggiungerne uno obbliga a decidere sulla precondizione |

**Rotture dichiarate.** Ogni mutazione senza `If-Match` passa da `200` a `428` — la rottura più
grande della fase, e deliberata: con `If-Match` facoltativo il debito non si chiude, si rende
*evitabile*. Il `PUT` idempotente con tag stantio passa da `200` a `412`. `DELETE /api/tasks/{id}`
da `404` a `405`.

**Una rottura che il piano non aveva previsto**, trovata dai test di concorrenza di TASK-004 e
TASK-007: il **perdente di due transizioni concorrenti vede `412` dove vedeva `409`**. La
transizione illegale resta ciò che riceve un chiamante **aggiornato**, esercitata sequenzialmente.
ADR-009 §8.

**La verifica per mutazione ha smentito due affermazioni dei documenti**, e sono state corrette, non
difese:

1. spostare il confronto **sotto** le regole **non** rende rosso il caso idempotente —
   `Task.assignTo` esce presto da **sé**, non dal service. Il test di I-3 non discriminava niente.
   Ciò che la posizione decide è **quale rifiuto** riceve un chiamante stantio, e c'è un test nuovo
   che lo asserisce;
2. spostarlo **sopra** il lock non perde la riga: Hibernate solleva `StaleObjectStateException`.
   Perde la **risposta** — `500` invece di `412`. Registrato in ADR-009 §2.3 come rete, non come
   meccanismo.

Artefatti: `tasks/TASK-008/*`, `docs/adr/ADR-009-optimistic-concurrency-http-contract.md`.

## Task precedenti di PHASE 2

Nessuna prima di TASK-008.

## Task di PHASE 1 (dettaglio)

**TASK-007 — Agent Registry** (2026-09-14).

`Agent` era la tabella più vecchia e la meno curata: cinque colonne, nessun timestamp, nessuna
unicità, nessuna validazione, un solo `GET`. Adesso è un registro allo stesso standard di
`Project`: `POST`, `GET [?active=]`, `GET /{id}`, `PUT /{id}`, `activate`, `deactivate`, nessun
`DELETE`.

Le decisioni di ADR-004 sono state **applicate, non ridiscusse**. Una sola divergenza, e
dichiarata: il ciclo di vita resta su `boolean active` invece dell'enum chiuso di `Project`,
perché unificarlo richiederebbe di eliminare una colonna — migrazione irreversibile che il
charter mette dietro una decisione umana quando un'alternativa ragionevole esiste, e esiste.
**TD-31.** La divergenza è nascosta al client e non al registro: `AgentResponse` espone
`status` **derivato**, e un test asserisce che non sia una colonna.

`V4` è additiva, e ha richiesto una cosa che il piano non aveva previsto: un `DEFAULT now()` sui
timestamp, perché il seed di sviluppo è una migrazione già applicata che inserisce senza
fornirli, e modificarla ne cambierebbe il checksum. **Trovato dalla suite, non dal progetto.**

Il protocollo di lock si applica perché **L7 dice che si applica**: due `deactivate` concorrenti
sono un `200` e un `409`, cioè il difetto di TD-19 che arriva già chiuso sulla terza entità.

Artefatti: `tasks/TASK-007/*`, `docs/adr/ADR-008-agent-registry.md`.

**TASK-006 — Migration Test Coverage** (2026-09-14).

Chiude **TD-22** e **TD-23**. Un solo file di test toccato, nessun cambiamento
all'applicazione.

TD-22 chiedeva il test incrementale `V1 → V2`. È stato scritto invece un test su **ogni coppia
consecutiva** che Flyway risolve: porta un database a `N`, ci scrive righe, migra a `N+1` e
verifica le tre cose che una migrazione può distruggere senza fallire — una riga sparisce, una
tabella sparisce, viene applicato più del previsto. Il test specifico avrebbe chiuso il buco di
ieri riaprendo quello di domani; leggere le coppie dallo stream copre `V4` il giorno in cui
esiste, senza che nessuno se ne ricordi.

TD-23 mantiene la decisione — l'elenco delle tabelle è dichiarato a mano, perché una migrazione
che crea una tabella è un fatto che qualcuno deve affermare — e perde la frase che descriveva il
test come capace di adattarsi da solo. Il difetto era la spiegazione, non l'elenco.

Verifica per mutazione: una `V3` che cancella righe, e una che elimina `agents`, rendono rosso
il test.

Artefatti: `tasks/TASK-006/*`.

**TASK-005 — Uniform Error Contract** (2026-09-14).

Chiude **TD-07**, e con lui **TD-20**, **TD-21**, **TD-27** e **TD-29**. L'API parlava tre
dialetti di errore; adesso ne parla uno.

| Decisione | Contenuto |
|---|---|
| Un advice globale | `ApiExceptionHandler`, che estende `ResponseEntityExceptionHandler`: le eccezioni che Spring solleva prima del nostro codice entrano nel contratto senza essere rincorse a ogni upgrade. Erano esattamente TD-20 e TD-27 |
| **`type` stabile** | `urn:ai-company-os:problem:<slug>`, enumerato in `ApiProblem`. **Il `type` è il contratto, il `title` è la prosa**: prima l'unico modo di distinguere due `409` era confrontarne la frase inglese |
| Validazione | `errors` con l'elenco dei campi, **ovunque** |
| Catch-all | `500` con `detail` fisso e stack loggato per intero: nessun nome di tabella, SQL o percorso raggiunge il chiamante |
| Locking | Prende quella forma. **Nessuna policy**: niente timeout, retry o `503`. ADR-006 §7 resta valida, e TD-29 era la forma, non la policy |
| I due advice di modulo | Rimossi: un contratto con due punti di definizione dipende da una precedenza che nessuno ha scelto |

**Rottura dichiarata**: cambiano i corpi di errore di `/api/agents` e delle risposte
preesistenti di `/api/tasks`. **Nessuno stato HTTP cambia.**

Artefatti: `tasks/TASK-005/*`, `docs/adr/ADR-007-uniform-error-contract.md`.

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
- Schema: di proprietà di **Flyway**, oggi a **`V5`**. Hibernate in `validate`.
- Tabelle: `agents`, `tasks`, `projects`. `tasks.project_id` nullable con FK senza `ON DELETE`.
  Da `V4`: `agents.created_at` / `updated_at` (`TIMESTAMPTZ`, `NOT NULL`, `DEFAULT now()`) e
  indice unico `agents_name_unique_idx` su `lower(name)`.
  Da `V5`: `version BIGINT NOT NULL DEFAULT 0` su **tutte e tre** le tabelle.
- **Due registri di dominio** con ciclo di vita esplicito: `Project` (enum `ACTIVE`/`ARCHIVED`)
  e `Agent` (`boolean active`, divergenza dichiarata → TD-31).
- Seed di sviluppo: stream Flyway separato (`db/dev/V1`), profilo `dev` (ADR-003).
- Profili: `dev` (default), `test` (Testcontainers), `prod`.
- **Concorrenza**: protocollo di lock L0–L7 su `tasks` e `projects` (ADR-006 §4). Lock
  bloccanti ordinari: nessun `lock_timeout`, nessuna policy di retry, nessun `503`.
- Contratto di errore: **uno solo** (ADR-007). Ogni risposta di errore è un `ProblemDetail`
  con `type` stabile `urn:ai-company-os:problem:<slug>`, enumerato in `ApiProblem`. Un advice
  globale, `ApiExceptionHandler`. Nessuna policy di timeout o retry.
- **Concorrenza ottimistica nel contratto HTTP**: protocollo **P0–P4** (ADR-009). `If-Match`
  obbligatorio su ogni mutazione di risorsa esistente, confrontato **dentro la transazione, dopo
  il lock esclusivo, prima delle regole**. `@Version` è il contatore, non il rilevatore: nessun
  handler per `OptimisticLockException`, e non va aggiunto.
- Test: **179** (erano 109 in `master`, 158 a fine PHASE 1). `./mvnw -B clean test` → BUILD SUCCESS.
- H2 rimosso.

## Stato Git (verificato il 2026-09-14)

- **`master`**: fermo a `d5ff121`. **Gate umano finale, nessun merge autonomo.**
- **Integration branch di PHASE 2**: `autonomous/phase-2-assignment`, creato da
  `autonomous/phase-1-foundations` (`0a35ac0`). Contiene il piano di fase e TASK-008.
- **Integration branch di PHASE 1**: `autonomous/phase-1-foundations`, HEAD **`0a35ac0`**, fermo.
- Branch di lavoro integrati in fast-forward: `task-004-archival-consistency`,
  `task-005-uniform-error-contract`, `task-006-migration-test-coverage`,
  `task-007-agent-registry`.
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
| `b1274d1` | `docs(task-004)` — chiusura, artefatti, stato per una sessione fredda |
| `1ddeee3` | `docs(project-state)` — correzione dell'integration HEAD |

Commit di TASK-005:

| Hash | Contenuto |
|---|---|
| `40c3578` | `test(api)` — i tre dialetti, 9 test deliberatamente rossi |
| `2f77f47` | `feat(api)` — il contratto unico |
| `d3faeae` | `docs(task-005)` — artefatti e review del proprio diff |

Branch conservati: `task-000-audit`, `task-001-persistence-foundation`,
`task-002-project-registry-foundation`, `task-003-task-project-association`,
`task-004-archival-consistency`.

## Decisioni architetturali

- **ADR-001** — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato. *Accettata*.
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway. *Accettata, parzialmente superata da ADR-003*.
- **ADR-003** — Il seed di sviluppo è uno stream Flyway separato. *Accettata*.
- **ADR-004** — Project Registry: stati chiusi imposti due volte, si archivia invece di cancellare, transizione illegale → `409`, unicità del nome nel database, un archiviato non è modificabile (§8). *Accettata*.
- **ADR-005** — Relazione `Task` → `Project`: `project_id` nullable, i task preesistenti non si migrano, associare a un `ARCHIVED` è `409`, relazione unidirezionale, FK senza `ON DELETE`. *Accettata*.
- **ADR-008** — Agent Registry: le decisioni di ADR-004 si applicano identiche; il ciclo di vita resta su `boolean active` invece dell'enum, perché unificarlo richiederebbe una migrazione irreversibile che il charter mette dietro una decisione umana (TD-31); `status` è **derivato** nel contratto, così il client vede un vocabolario solo e il database un solo stato; `V4` additiva; nessuna relazione con `Project` né con `Task`; il protocollo di lock si applica per L7. *Accettata e implementata*.
- **ADR-007** — Un solo contratto di errore per tutta l'API: advice globale che estende `ResponseEntityExceptionHandler`, `type` stabile e enumerato (`urn:ai-company-os:problem:<slug>`) come parte machine-readable del contratto, `errors` ovunque, catch-all con `detail` fisso e stack loggato, nessuna policy di timeout o retry. **Supera ADR-004 §6 e ADR-005 §8**. *Accettata e implementata*.
- **ADR-009** — Concorrenza ottimistica nel contratto HTTP: due meccanismi distinti e complementari (lock = consistenza interna, `ETag`/`If-Match` = intento stantio); `@Version` è un contatore persistente e **non** il rilevatore, perché dopo l'attesa su `PESSIMISTIC_WRITE` l'entità è già alla versione nuova; protocollo **P0–P4**; `If-Match` obbligatorio su tutte e tre le risorse; `428`/`412`/`400`; `GET /api/tasks/{id}` introdotto come percorso canonico dell'ETag; `V5` additiva. **Completa ADR-006 §8.** *Accettata e implementata*.
- **ADR-006** — Coerenza archiviazione → task **derivata** (nessuna scrittura sui figli, `restore` inverso per costruzione), congelamento in scrittura con letture aperte, `PUT` idempotente `200` no-op, protocollo di lock **L0–L7** con ordine globale `tasks` → `projects`, nessuna migrazione, contratto invariato, nessun `503`. *Accettata e **implementata**.*

## Prossimo passo autonomo

**TASK-009 — Relazione `Task` → `Agent`.** Branch `task-009-task-agent-assignment` da
`autonomous/phase-2-assignment`. Il briefing operativo — le tre domande di dominio e il punto
sull'aciclicità dei lock — è in `tasks/TASK-008/HANDOFF.md`, e non va riscoperto.

Il piano completo della fase sta in **`.company-os/PHASE_2_PLAN.md`**. In sintesi:

| # | Task | Livello | Stato |
|---|---|---|---|
| **TASK-008** | Optimistic concurrency (`ETag`/`If-Match`). Chiude TD-28, TD-30 | 3 | **completata** 2026-09-16 |
| **TASK-009** | Relazione `Task` → `Agent` | 4 | **prossima** |
| **TASK-010** | Da definire quando ci si arriva — TASK-009 può derivarne il contenuto | 5 | pianificata |
| **TASK-011** | Collisione di identificatori nel registro del debito; `docs/RUNNING.md` | 6 | pianificata |

Fuori da PHASE 2 e dichiarato tale: **TD-14** (CI: richiede un remote, hard stop #4), **TD-31**
(elimina una colonna, hard stop #3), **TD-04** (autenticazione: primo candidato di PHASE 3).

Il merge di PHASE 1 in `master` resta il gesto con cui un umano accetta il lavoro
(`AUTONOMOUS_CHARTER.md` §8), e nessun agente lo esegue.

## Debito aperto rilevante

**Chiusi da TASK-004**: TD-19 (componente ciclo di vita), **TD-24**, **TD-25**.
**Chiusi da TASK-005**: **TD-07**, **TD-20**, **TD-21**, **TD-27**, **TD-29**.
**Chiusi da TASK-006**: **TD-22**, **TD-23**.
**TASK-007** non chiude nulla e **apre TD-31**.
**Chiusi da TASK-008**: **TD-28**, **TD-30**. **TASK-008 apre TD-32 e TD-33.**

### Alto valore

| ID | Contenuto |
|---|---|
| **TD-31** | *(nuovo)* `Agent` esprime il ciclo di vita con un booleano, `Project` con un enum chiuso. Unificarli richiede di **eliminare una colonna**: migrazione irreversibile, dietro una decisione umana. Nel frattempo il contratto pubblico è già uniforme, perché `status` è derivato |
| **TD-14** | Nessuna CI. Con 158 test, invarianti di concorrenza e guardie verificate per mutazione, il costo di non averla cresce a ogni task |

### Nuovi, minori (TASK-008)

| ID | Contenuto |
|---|---|
| **TD-32** | L'entity-tag è forte ma deriva dalla versione della riga, non dai byte della rappresentazione: un cambio di forma della risposta senza cambio di stato darebbe lo stesso ETag a due rappresentazioni diverse. Nessun effetto su `If-Match`; effetto sulla cache HTTP, che il progetto non usa |
| **TD-33** | I listati non portano ETag, quindi mutare N risorse costa N letture singole. Non motivato finché non esiste un client che muta in blocco |

### Qualità dei test e migrazioni

| ID | Contenuto |
|---|---|
| **TD-26** | Il path lazy non è esercitato fuori transazione. **Parzialmente coperto** da TASK-004: un test pinna che leggere `projectId` non inizializzi il proxy |

### Preesistente

TD-04 sicurezza, TD-08 `MasterOrchestrator`, TD-12/TD-13 dominio, TD-15 Lombok inutilizzato,
TD-17/TD-18 `README.md`.

**TD-11 (CORS) — superficie cambiata.** Il `@CrossOrigin` senza origine su `AgentController` è
stato **rimosso** da TASK-007: era già TD-11, ma quel controller adesso non legge soltanto, e
riportarlo avrebbe aperto creazione, modifica e transizioni a qualunque origine come effetto
collaterale. Non chiude TD-11 — la policy CORS resta una decisione da prendere — ma la superficie
oggi è più stretta di quanto il debito descrivesse.

Rilievi della review TASK-001 ancora aperti: **R3** (`.env` non configura il processo Maven),
**R5** (`server.address` non vincolato a loopback), **R6** (tag immagine mobile), **R7**
(`.gitignore` non copre `.env.*`). Correzioni documentali ai file `docs/audit/*` di TASK-000:
da applicare. `docs/RUNNING.md` non documenta `/api/projects` né gli endpoint di TASK-003.

## Failure aperti

**Nessuno.** 179/179 verdi (`./mvnw -B clean test`, 2026-09-16).

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
- Agent Registry ✅ *fondazione (TASK-007). Relazione con i task: TASK-009*
- Skills / Rules / Subagents / Tools / MCP Registry
- Model Gateway and local/cloud routing
- Context / Prompt / Harness / Loop / Graph Engineering
- Visual Code Architecture Graph, Knowledge Vault, Memory Graph
- Planner
- GitHub / GitLab, ClickUp, Google Drive / Gmail, software adapters
- Template Hub, Framework Explorer, External AI workspaces
- Voice Interaction Layer, Payments / quota monitoring, 3D Omniverse integration

## Immediate goal
Completare **PHASE 2** in modo autonomo su `autonomous/phase-2-assignment`, poi aggiornare
l'handoff per la review umana. **Nessun merge in `master`**, che resta fermo a `d5ff121` con
PHASE 1 ancora in attesa di accettazione.
