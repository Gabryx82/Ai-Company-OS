# TASK-004 — Archival Consistency & Project Lock Protocol

> **Stato: APPROVED — scope approvato il 2026-09-12; delta L0 approvato il 2026-09-14.**
> Revisione 4. In implementazione su `task-004-archival-consistency`.
> Da qui in avanti il perimetro è vincolante: quello che è fuori scope resta fuori, e le
> condizioni di stop valgono come scritte.

## Owner proposto
Claude Code / Opus 5

## Reviewer proposto
Codex (review differenziale)

## Baseline
- Branch di lavoro proposto: `task-004-archival-consistency` da `master`.
- HEAD di `master` al momento della proposta: `d5ff121` — `docs(project-state): record TASK-003 merged`.
- Nessun remote configurato. Nessun merge, nessun push.

## Il nodo

`PROJECT_STATE.md` indica come nodo successivo la cascata `archive`/`restore` verso i task,
con una condizione: **TD-19 e TD-25 vanno sciolti prima di scriverne il codice, non dopo.**

ADR-005 §4 aveva lasciato lo stato intermedio dichiarato: un progetto archiviato può avere task
attaccati, «leggibili e — oggi — modificabili». La conseguenza osservabile è che l'archiviazione
è una barriera in entrata e una porta aperta in uscita:

| Oggi | |
|---|---|
| `POST /api/tasks` con `projectId` archiviato | `409` |
| `PUT /api/tasks/{id}/project` **verso** un progetto archiviato | `409` |
| `PUT /api/tasks/{id}/project` di un task **dentro** un progetto archiviato | **`200`** |

## Decisioni approvate

Motivazioni complete in `docs/adr/ADR-006-archival-consistency-and-project-serialization.md`
(**Stato: Accettata**).

| # | Decisione | Rif. |
|---|---|---|
| 1 | **Archival consistency derivata.** Nessuna cascata materializzata: `archive`/`restore` non scrivono nessuna riga di `tasks`. `restore` è l'inverso esatto per costruzione | ADR-006 §1 |
| 2 | **Nessuna migrazione.** Lo schema resta a `V3` | ADR-006 §6 |
| 3 | **Un task in un progetto `ARCHIVED` è congelato in scrittura** | ADR-006 §2 |
| 4 | **Le letture restano consentite** su task e progetti archiviati | ADR-006 §2 |
| 5 | **`PUT` idempotente verso lo stesso progetto archiviato → `200` no-op**: il congelamento riguarda le mutazioni, e una conferma non muta niente | ADR-006 §2 |
| 6 | **Locking pessimistico**, formalizzato come protocollo **L0–L7**: riga `tasks` esclusiva prima, righe `projects` dopo, ordine globale per id crescente | ADR-006 §4 |
| 7 | **L0 e l'ordine globale** (approvati il 2026-09-14, dopo che un test ha riprodotto un bypass della regola di congelamento per staleness) | ADR-006 §4, §8 |

## Comportamento esatto dei Task su archive/restore

### `POST /api/projects/{id}/archive`
- Scrive **una sola riga**: `projects`. Zero `UPDATE` su `tasks`.
- I task del progetto restano identici: `project_id`, `title`, `description`, `status`,
  `priority` invariati.
- Da quel momento quei task sono **congelati in scrittura** e **aperti in lettura**.
- Archiviare un progetto che ha task resta consentito (ADR-005 §4, invariato).

### `POST /api/projects/{id}/restore`
- Scrive **una sola riga**: `projects`. Zero `UPDATE` su `tasks`.
- I task tornano scrivibili immediatamente, senza nessuna operazione di ripristino, perché
  non era stato tolto loro niente.
- Un task assegnato, spostato o creato **mentre** il progetto era archiviato non esiste: era
  rifiutato con `409`. Non c'è nessun caso da riconciliare.

### Matrice operazione × stato del progetto

| Operazione | progetto `null` | progetto `ACTIVE` | progetto `ARCHIVED` |
|---|---|---|---|
| `GET /api/tasks` | `200` | `200` | `200` |
| `GET /api/projects/{id}/tasks` | n/a | `200` | `200` |
| `POST /api/tasks` con quel `projectId` | n/a | `201` | `409` (invariato) |
| `PUT /api/tasks/{id}/project` — **destinazione** | n/a | `200` | `409` (invariato) |
| `PUT /api/tasks/{id}/project` — **origine**, spostamento | `200` | `200` | **`409` — nuovo** |
| `PUT /api/tasks/{id}/project` — origine == destinazione | n/a | `200` | **`200` no-op** |

Quando origine e destinazione sono entrambe archiviate e diverse, si valuta **prima
l'origine**: il task è congelato prima ancora che si guardi dove sta andando.

## Invarianti

| ID | Invariante | Come si verifica |
|---|---|---|
| **I-1** | `archive` e `restore` non scrivono nessuna riga di `tasks` | `entityUpdateCount` su `Task` nelle statistiche Hibernate = 0 |
| **I-2** | `restore` riporta il sistema esattamente allo stato precedente l'`archive`, senza stato ricordato | Confronto riga-per-riga di `tasks` prima e dopo il ciclo |
| **I-3** | Un task in un progetto `ARCHIVED` è immutabile (`409` su mutazione) e leggibile (`200`) | Test di API su entrambi i lati |
| **I-4** | **Al commit di un'assegnazione, ogni progetto da cui dipendeva era nello stato su cui la decisione è stata presa** — non «era stato controllato»: *era* | Test a due thread, `assign` ∥ `archive`, in entrambi gli ordini |
| **I-5** | Due transizioni di ciclo di vita concorrenti sullo stesso progetto: esattamente una riesce, l'altra è `409` | Test a due thread, `archive` ∥ `archive` |
| **I-6** | Due assegnazioni concorrenti allo stesso progetto `ACTIVE` riescono entrambe | Test a due thread; dimostra anche che il lock condiviso è davvero condiviso e non un `FOR UPDATE` travestito |
| **I-7** | I `GET` non prendono lock (L6) | Lettura concorrente a un `archive` in transazione aperta |
| **I-8** | Lo schema resta a `V3`: nessuna migrazione nuova | `SchemaMigrationTest` invariato |
| **I-9** | **Ogni percorso di scrittura che dipende da `Project.status` applica il protocollo, e acquisisce le righe in ordine di `id` crescente** (L2, L5, L7) | Test unitario sull'ordine di acquisizione + tabella dei percorsi in ADR-006 §4 |
| **I-10** | Il contratto pubblico non cambia: `TaskResponse` è identico a quello di TASK-003 | Asserzione sulla forma della risposta |
| **I-11** | **Nessuna scrittura su un task committa contro uno stato che un'altra transazione ha già cambiato**: la regola di congelamento è valutata sull'associazione vera al commit, non su una letta prima (L0) | Test a due transazioni con riassegnazione stantia |

## Protocollo di lock — formalizzato

Regola unica per TD-19 (componente ciclo di vita) e TD-25. Enunciato completo in ADR-006 §4.

| Regola | Contenuto |
|---|---|
| **L0** | Chi **muta un task esistente** prende `PESSIMISTIC_WRITE` (`FOR UPDATE`) sulla riga del task **prima** di leggerne l'associazione, e la tiene fino al commit. L'insieme dei progetti si **deriva dopo**, dalla riga letta sotto lock |
| **L1** | Chi **scrive** una riga di `projects` prende `PESSIMISTIC_WRITE` (`FOR UPDATE`) su quella riga prima di leggerne lo stato, e la tiene fino al commit |
| **L2** | Ogni percorso di **scrittura** la cui correttezza dipende da `Project.status` prende `PESSIMISTIC_READ` (`FOR SHARE`) su **ogni** riga di progetto da cui dipende, prima di deciderlo, e la tiene fino al commit |
| **L3** | **Creazione / assegnazione da `NULL`**: un solo progetto in gioco, la destinazione → un `FOR SHARE` sulla destinazione. `POST /api/tasks` senza `projectId`: nessun lock |
| **L4** | **Riassegnazione A → B**: `FOR SHARE` su **entrambi**, origine A (congelamento) e destinazione B (non riceve lavoro nuovo). Se A == B, una riga sola |
| **L5** | **Ordine globale**: prima `tasks` per `id` crescente, poi `projects` per `id` crescente. Dentro ogni classe l'insieme si costruisce, si deduplica, si ordina e si blocca in quell'ordine, qualunque sia il ruolo di ciascuna riga. L'ordine fra le classi non è una convenzione: quali progetti servano lo dice la riga del task |
| **L6** | Le **letture** non prendono lock |
| **L7** | **Clausola di chiusura**: il protocollo è universale. Ogni percorso di scrittura, presente o futuro, che dipende da `Project.status` lo applica. Nessuna eccezione «tanto questo caso è innocuo» — è il ragionamento che ha prodotto TD-25 |

Applicazione ai percorsi che esistono oggi — interamente determinata, niente da decidere caso
per caso:

| Percorso di scrittura | Dipende da `status`? | Regole | Righe bloccate |
|---|---|---|---|
| `POST /api/projects` | no | — | nessuna |
| `PUT /api/projects/{id}` | sì (ADR-004 §8) | L1 | `{id}` `FOR UPDATE` |
| `POST /api/projects/{id}/archive` | sì (ADR-004 §4) | L1 | `{id}` `FOR UPDATE` |
| `POST /api/projects/{id}/restore` | sì | L1 | `{id}` `FOR UPDATE` |
| `POST /api/tasks` senza `projectId` | no | L6 | nessuna |
| `POST /api/tasks` con `projectId` | sì | L2, L3 | `{target}` `FOR SHARE` |
| `PUT /api/tasks/{id}/project`, task senza progetto | sì | **L0**, L2, L3 | `tasks{id}` `FOR UPDATE` → `projects{target}` `FOR SHARE` |
| `PUT /api/tasks/{id}/project`, A → B | sì | **L0**, L2, L4, L5 | `tasks{id}` `FOR UPDATE` → `projects{A, B}` `FOR SHARE`, id crescente |
| `PUT /api/tasks/{id}/project`, A → A | sì | **L0**, L2, L4 | `tasks{id}` `FOR UPDATE` → `projects{A}` `FOR SHARE` |
| Ogni `GET` | — | L6 | nessuna |

Perché pessimistico e non `@Version`: `@Version` **non chiude TD-25** (l'assegnazione non
scrive la riga del progetto, non c'è versione da confrontare), richiede una migrazione, e
trasformerebbe in `409` due assegnazioni concorrenti che non sono in conflitto. ADR-006 §4.

**Perché L0.** Senza, il protocollo protegge le righe da cui si legge lo stato ma non l'input
della decisione — quale progetto contiene il task. Una transazione può leggere «il task è in A,
A è `ACTIVE`», restare aperta mentre un'altra lo sposta in C e C viene archiviato, e poi
committare su un task che in quell'istante appartiene a un progetto archiviato. Nessun lock sui
progetti la intercetta: lo scrittore stantio tiene `{A, B}` e l'archive tocca `C`, insiemi
disgiunti. Riprodotto da un test deterministico sulla baseline prima di scrivere il codice.

**Perché non c'è ciclo.** I percorsi che toccano i task vanno `tasks → projects`; quelli che
toccano solo i progetti non prendono mai un lock su un task, quindi nessuno va
`projects → tasks`. È una conseguenza della consistenza derivata (ADR-006 §1): vale finché
`archive`/`restore` non scrivono righe di `tasks`.

## Contabilità del debito

| ID | Stato alla chiusura di TASK-004 | Nota |
|---|---|---|
| **TD-25** | **CLOSED** | Chiuso interamente da L2+L3+L4. Nessun residuo |
| **TD-19** | **RESOLVED — solo componente (a), ciclo di vita** | «due `archive` concorrenti → `200` + `200`» è risolto da L1; il lost update sui figli che TD-19 anticipava non può esistere perché `archive` non scrive figli (ADR-006 §1). **Il testo del debito va aggiornato** dichiarando che la componente (b) è uscita verso TD-28 |
| **TD-28** | **OPEN — nuovo** | Componente (b) di TD-19, estratta: `PUT /api/projects/{id}` resta esposto alla sovrascrittura con dati stantii. Il lock di riga lo serializza ma non lo rileva. Richiede `ETag`/`If-Match` o un numero di versione **nel contratto HTTP** — decisione sull'API, non sul database |
| **TD-24** | **RESOLVED oppure OPEN**, secondo l'esito di **AC-18** | Si chiude solo se il self-invocation di un metodo `readOnly` è eliminato *e* la cosa è verificata. Altrimenti resta aperto e lo si dichiara |
| **TD-29** | **OPEN — MINOR, subordinato a TD-07** | Assenza di un **contratto API normalizzato** per gli errori infrastrutturali di concorrenza e locking: un deadlock o una cancellazione amministrativa affiora con la forma di errore di default, come ogni altro errore infrastrutturale oggi. **Non implica timeout, retry o `503`** — è una questione di forma della risposta, non di policy. Si chiude dentro TD-07, insieme a TD-20 e TD-27 |
| **TD-30** | **OPEN — MINOR, ristretto** | Due riassegnazioni concorrenti dello stesso task restano last-write-wins, **ma su stato fresco**: L0 le serializza e ciascuna applica le regole ai dati che trova. Manca solo la *rilevazione* dell'intento stantio verso il chiamante. Gemello di TD-28 sull'altra entità |
| TD-07, TD-20, TD-21, TD-22, TD-23, TD-26, TD-27 | **invariati** | Nessuno è sulla strada di questa task |

TD-19 non risulta «chiuso a metà»: le sue due componenti erano problemi di natura diversa —
coerenza fra entità contro contratto HTTP — e vengono tracciate separatamente. Dettaglio in
ADR-006 §5.

## Limite dichiarato, ristretto

La revisione 3 affermava che due riassegnazioni concorrenti dello stesso task sono
last-write-wins e che «lo stato finale è comunque valido». **La seconda metà era falsa**, e un
test l'ha dimostrata falsa prima che il codice esistesse: senza L0 una transazione stantia
committava una scrittura su un task che in quell'istante apparteneva a un progetto archiviato,
violando I-3. Quello era un difetto, non un limite, e L0 lo chiude.

Il limite che **resta** è più stretto:

> Due riassegnazioni concorrenti dello stesso task restano last-write-wins, **ma ciascuna decide
> su stato fresco**. L0 le serializza: la seconda aspetta il commit della prima, rilegge la riga
> e applica tutte le regole allo stato che trova. Il chiamante della prima non viene informato
> che la sua scrittura è stata sostituita.

Nessun invariante è violato: ogni scrittura ha committato su stato letto sotto lock e ancora
vero al commit, e lo stato finale è raggiungibile da una sequenza legale di richieste. Manca
solo la **rilevazione dell'intento stantio** — che è esattamente TD-28 sull'altra entità, e si
chiude con concorrenza ottimistica nel contratto HTTP, non con un lock. Dettaglio in ADR-006 §8.

## Failure semantics

| Situazione | Stato | Forma | Nuovo? |
|---|---|---|---|
| Task inesistente | `404` | `ProblemDetail` | no |
| Progetto (origine o destinazione) inesistente | `404` | `ProblemDetail` | no |
| Assegnazione **verso** un progetto `ARCHIVED` | `409` | `ProblemDetail` — «Archived project cannot receive tasks» | no |
| **Spostamento** di un task dentro un progetto `ARCHIVED` | `409` | `ProblemDetail`, titolo distinto | **sì** |
| `PUT` verso lo stesso progetto archiviato (no-op) | `200` | corpo invariato | **sì** |
| Secondo `archive`/`restore` concorrente | `409` | `ProblemDetail` | garanzia nuova: oggi è `200` |
| Fallimento di lock (deadlock, cancellazione amministrativa) | invariato | forma di default | **non gestito, dichiarato → TD-29** |

Due `409` distinti perché richiedono due azioni diverse dal chiamante: il `restore` di **due
progetti diversi**.

**Nessun `503`, nessun timeout, nessun retry.** TASK-004 usa lock bloccanti ordinari: nessun
`lock_timeout` applicativo, nessun contratto di ritentativo, nessun codice di stato nuovo. Quel
che resta scoperto è più stretto di una policy — la *forma* della risposta quando un errore
infrastrutturale di locking affiora comunque — ed è il perimetro di TD-07. Per questo **TD-29 è
MINOR e subordinato a TD-07**: si chiude lì dentro, non da solo.

Il contratto di errore resta **limitato ai due advice di modulo esistenti**. TD-07, TD-20 e
TD-27 non si toccano.

## Migrazione database

**Nessuna.** Lo schema resta a `V3`. Vedi ADR-006 §6.

## In scope
1. ~~`ADR-006` portata ad **Accettata**~~ — **fatto in approvazione.** Resta da aggiungerle, a
   fine implementazione, la sezione «Conseguenze operative» verificate sul codice reale.
2. Guardia sull'entità `Task`: lo **spostamento** di un task il cui progetto è `ARCHIVED` è
   rifiutato. Nuova eccezione dedicata + mapping `409` in `TaskExceptionHandler`.
3. `ProjectRepository`: due ricerche con lock (`PESSIMISTIC_WRITE`, `PESSIMISTIC_READ`).
4. `ProjectService`: `update`, `archive`, `restore` passano alla ricerca con lock esclusivo (L1)
   **senza self-invocation di un metodo `readOnly`** — condizione verificata da AC-18.
5. `TaskService`: i percorsi di scrittura applicano L2–L5; le letture no (L6).
6. Test: comportamento, invarianti, protocollo di lock, concorrenza (vedi sotto).
7. Artefatti di task (`ARTIFACT.md`, `HANDOFF.md`), aggiornamento di `PROJECT_STATE.md`
   **a fine task**, con la contabilità del debito sopra: TD-25 chiuso, TD-19 aggiornato,
   TD-28 e TD-29 registrati, TD-24 secondo l'esito di AC-18.

## Fuori scope — esplicito
- **Cascata materializzata**: nessuna colonna, flag o timestamp di archiviazione sui task.
- **Qualunque migrazione di database.** Se durante l'implementazione una diventa necessaria, la
  task si ferma e la cosa torna in approvazione: sarebbe il segno che una decisione di ADR-006
  era sbagliata.
- **La scopribilità dello stato congelato**, in via definitiva: nessun `projectStatus`, nessun
  `archived`, nessun campo derivato di altro nome in `TaskResponse`. Il contratto pubblico non
  cambia (I-10). Si decide con le altre domande di contratto aperte in `PROJECT_STATE.md`.
- **`503`, `Retry-After`, `lock_timeout` applicativo, qualunque contratto di retry o policy di
  timeout.** La forma della risposta agli errori infrastrutturali di locking → TD-29, dentro
  TD-07.
- **Rilevazione dell'intento stantio**: nessun `@Version` su `Task`, nessun `If-Match`
  sull'associazione → TD-30, non qui. Il **lock** sulla riga del task (L0) è invece in scope: non
  è rilevazione, è prevenzione di una violazione di invariante.
- **`@OneToMany` su `Project`**: la relazione resta unidirezionale (ADR-005 §4).
- **`@Version` / `ETag` / `If-Match`** → TD-28, non qui.
- **TD-07 / advice globale**, e con lui **TD-20** e **TD-27**.
- **Enum di dominio su `Task.status` / `Task.priority`**: restano stringhe libere.
- **`project_id` verso `NOT NULL`**, e qualunque backfill.
- **`DELETE /api/tasks/{id}/project`** e i filtri su `GET /api/tasks`.
- **`GET /api/tasks/{id}`** (LOW della review TASK-001).
- **TD-21, TD-22, TD-23, TD-26**: nessuno è sulla strada di questa task.
- Cancellazione di progetti, in qualunque forma.
- Planner, agent assignment, graph/orchestration, Agent Registry.
- Frontend, servizio Python/AI, integrazioni esterne, autenticazione, CI, paginazione.
- Debito documentale (`docs/RUNNING.md`, `docs/audit/*`, R3/R5/R6/R7).
- Merge, push, remote, riscrittura di storia.

## Acceptance criteria

**Comportamento**
- **AC-1** — `archive` di un progetto con task: `200`, e **nessuna riga di `tasks` scritta**
  (I-1). Verificato dalle statistiche Hibernate, non solo dal confronto dei valori.
- **AC-2** — Ciclo `archive` → `restore`: le righe di `tasks` sono identiche prima e dopo (I-2).
- **AC-3** — `GET /api/tasks` e `GET /api/projects/{id}/tasks` di un progetto archiviato → `200`
  con i task (I-3, invariato rispetto a TASK-003).
- **AC-4** — `PUT /api/tasks/{id}/project` che **sposta** un task fuori da un progetto
  archiviato → `409` `ProblemDetail`, **task invariato**. Dopo `restore` del progetto di
  origine, la stessa richiesta → `200`.
- **AC-5** — Origine archiviata **e** destinazione archiviata, diverse: `409` dell'**origine**,
  in modo deterministico.
- **AC-6** — `PUT` che riassegna un task al progetto archiviato **in cui è già**: `200`, e
  **nessuna scrittura** (`entityUpdateCount` su `Task` = 0).
- **AC-7** — I `409` preesistenti di TASK-003 (destinazione archiviata, su `POST` e su `PUT`)
  restano identici per codice e forma.
- **AC-8** — `TaskResponse` ha esattamente i campi di TASK-003: nessun campo nuovo, nessuno
  rimosso, stessi nomi (I-10).

**Protocollo di lock e concorrenza** — a due thread contro PostgreSQL reale, transazioni
controllate a mano
- **AC-9** (TD-25, I-4) — `assign` ∥ `archive` sullo stesso progetto, **in entrambi gli
  ordini**: nessun esito in cui un task risulti assegnato a un progetto che era già `ARCHIVED`
  al commit. Gli esiti ammessi sono esattamente due: (task assegnato + progetto poi archiviato)
  oppure (`409` + task non assegnato).
- **AC-10** (TD-19 componente a, I-5) — `archive` ∥ `archive`: esattamente un `200` e un `409`.
- **AC-11** (I-6) — `assign` ∥ `assign` sullo stesso progetto `ACTIVE`: entrambi `200`, entrambi
  i task assegnati. Se questo test fallisce, il lock condiviso è diventato esclusivo.
- **AC-12** (I-7, L6) — `GET` concorrente a un `archive` con transazione aperta: risponde senza
  attendere.
- **AC-13** (I-9, L4+L5) — Riassegnazione A → B: il servizio acquisisce il lock **su entrambe**
  le righe, e **in ordine di `id` crescente** anche quando la destinazione ha id minore
  dell'origine. Test unitario con un doppio del repository che registra l'ordine delle
  chiamate. Deve fallire se un solo lock viene preso, o se l'ordine segue il ruolo invece
  dell'id.

- **AC-19** (I-11, L0) — **Riassegnazione stantia.** Un task T è in A; una transazione inizia a
  spostarlo in B leggendo A come origine e resta aperta; un'altra lo sposta in C e committa; C
  viene archiviato e committa; solo allora la prima committa. La sua scrittura deve essere
  **rifiutata** e il task deve restare in C. Senza L0 la scrittura passa: riprodotto sulla
  baseline, deterministicamente, prima di scrivere il codice.
- **AC-20** (L5) — **Ordine globale.** I lock vengono presi `tasks` prima di `projects`. Test
  unitario con un doppio del repository che registra la sequenza: deve fallire se l'ordine si
  inverte o se il lock sul task manca.

**Regressione**
- **AC-14** — Lo schema resta a `V3`; `SchemaMigrationTest`, `MigrationStreamTest` e
  `DevSeedMigrationTest` passano **senza modifiche** (I-8).
- **AC-15** — La forma del `400` di `POST /api/tasks {}` è ancora quella di Spring; il contratto
  di errore dei moduli non si allarga (`TaskExceptionHandlerScopeTest` esteso al solo `409`
  nuovo).
- **AC-16** — I 109 test esistenti restano verdi. Suite completa verde contro PostgreSQL reale.

**Qualità dei test** — condizioni di chiusura, non un di più
- **AC-17** — Ogni test di concorrenza (AC-9, AC-10, AC-11, AC-12), il test di ordine AC-13 e
  i test di I-1 (AC-1, AC-6) sono **verificati per mutazione**: si dimostra che rimuovendo il
  lock, l'ordinamento o la guardia il test fallisce. Un test di concorrenza che non è mai stato
  visto fallire non dimostra niente — stessa classe di rilievo che la review di TASK-003 ha
  chiuso come M-1 e M-2.
- **AC-18** (TD-24) — **Il self-invocation è eliminato, e lo si dimostra.** Un test strutturale
  asserisce che la ricerca usata dai percorsi di scrittura di `ProjectService` **non** è un
  metodo pubblico annotato `@Transactional(readOnly = true)` invocato via `this.`, e che
  nessun metodo di scrittura dipende da quel comportamento. Il test deve fallire per mutazione,
  cioè riportando i percorsi di scrittura a chiamare `findById`.
  **Se questo criterio non viene soddisfatto, TD-24 resta `OPEN`** e va dichiarato tale in
  `PROJECT_STATE.md`: non si chiude un debito perché il codice adesso sembra diverso.

## Test richiesti — elenco

| File | Contenuto |
|---|---|
| `TaskProjectAssociationApiTest` (esistente, esteso) | AC-4, AC-5, AC-6, AC-7, AC-8 |
| `ProjectArchivalConsistencyTest` (nuovo) | AC-1, AC-2, AC-3 |
| `ProjectConcurrencyTest` (nuovo) | AC-9, AC-10, AC-11, AC-12, AC-19 |
| `ProjectLockProtocolTest` (nuovo, unitario) | AC-13, AC-18, AC-20 |
| `TaskExceptionHandlerScopeTest` (esistente) | AC-15, esteso al solo `409` nuovo |
| `SchemaMigrationTest`, `MigrationStreamTest`, `DevSeedMigrationTest` | AC-14, **invariati** |

## Vincoli operativi
- Nessun merge, nessun push, nessun remote, nessuna riscrittura di storia.
- Il volume di sviluppo `aicompany_postgres_data` non va cancellato. Nessuna migrazione da
  verificarci sopra; le eventuali righe di smoke test vanno rimosse al termine.
- Nessuna modifica a `.company-os/PROJECT_STATE.md` prima della fine della task.

## Stato dell'approvazione

**Nessun punto resta da approvare.** I due che la revisione 2 lasciava in sospeso sono stati
decisi il 2026-09-12:

| # | Punto | Decisione |
|---|---|---|
| 1 | Registrare TD-29 | **Sì**, come MINOR subordinato a TD-07, riformulato: solo l'assenza di un contratto API normalizzato per gli errori infrastrutturali di concorrenza e locking. Niente timeout, retry o `503` |
| 2 | Scopribilità dello stato congelato | **Fuori scope**, definitivo per questa task. Nessun `projectStatus` in `TaskResponse` |

Aggiunto in approvazione: il limite last-write-wins sulle riassegnazioni concorrenti dello
stesso task, tracciato come **TD-30** (sezione «Limiti dichiarati del modello»).

Un solo esito resta **condizionale**, e non è un'approvazione mancante ma un criterio da
verificare: **AC-18**. Se il test strutturale su TD-24 non si riesce a scrivere o a far fallire
per mutazione, **TD-24 resta `OPEN`, lo si dichiara, e TASK-004 si chiude lo stesso.**
