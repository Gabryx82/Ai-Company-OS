# TASK-004 — Implementation Plan (APPROVED, non eseguito)

> **Piano approvato il 2026-09-12. Nessuna riga è stata eseguita.**
> Revisione 3. Diventa un `IMPLEMENTATION.md` nel senso di `AGENT_PROTOCOL.md` §5 — cioè un
> resoconto — solo dopo l'esecuzione. Decisioni in
> `docs/adr/ADR-006-archival-consistency-and-project-serialization.md` (**Stato: Accettata**);
> scope, invarianti e acceptance criteria in `TASK.md`.

## Forma della soluzione in una frase

Non si aggiunge un dato e non si allarga il contratto: si aggiunge una **regola** (un task il
cui progetto è `ARCHIVED` non si sposta) e un **protocollo di lock** (L1–L7, sulla riga
`projects`), e la stessa riga di database che rende la regola vera rende impossibile aggirarla
in concorrenza.

## Superficie toccata

Sei file di produzione. Nessuna migrazione, nessun file di configurazione, **nessun DTO**.

### 1. `project/repository/ProjectRepository.java` — le due ricerche con lock

Sono l'intera implementazione del protocollo:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)   // L1 — SELECT … FOR UPDATE
@Query("SELECT p FROM Project p WHERE p.id = :id")
Optional<Project> findByIdForUpdate(@Param("id") Long id);

@Lock(LockModeType.PESSIMISTIC_READ)    // L2 — SELECT … FOR SHARE
@Query("SELECT p FROM Project p WHERE p.id = :id")
Optional<Project> findByIdForShare(@Param("id") Long id);
```

Il commento sul codice deve dire *quale ruolo* prende quale lock — chi cambia lo stato di ciclo
di vita, chi lo legge per agire, chi legge e basta — e rimandare a L1/L2/L6, non spiegare
l'annotazione.

**Verifica obbligatoria, non rimandabile.** Che `PESSIMISTIC_READ` produca davvero `FOR SHARE`
e non `FOR UPDATE` sul dialetto PostgreSQL in uso va **dimostrato**, non assunto: è la
differenza fra AC-11 che passa e un collo di bottiglia silenzioso per progetto. La prova è
AC-11 stesso (due assegnazioni concorrenti allo stesso progetto attivo riescono entrambe); se
fallisce, il lock condiviso non è condiviso.

### 2. `project/service/ProjectService.java` — L1 sui percorsi di scrittura, senza self-invocation

`update`, `archive`, `restore` smettono di chiamare `findById(id)` e passano a un lookup basato
su `findByIdForUpdate`, che lancia la stessa `ProjectNotFoundException`.

**Vincolo di forma, non di stile — è AC-18.** Il lookup usato dalle scritture deve essere
`private` e **senza** `@Transactional`: un metodo privato non può portare un attributo
transazionale proprio, quindi il problema di TD-24 — una scrittura che funziona solo perché il
self-invocation aggira il proxy e ignora un `readOnly = true` — non è più esprimibile, non
soltanto non più presente.

`findById` pubblica resta com'è, `readOnly`, e **non viene più invocata dall'interno**.

Il `save` esplicito di `archive`/`restore` diventa superfluo (l'entità è gestita nella
transazione). Si toglie solo se non cambia niente di osservabile; non è il punto di questa task.

**TD-24 si chiude qui se e solo se AC-18 passa** — test strutturale + verifica per mutazione.
Altrimenti resta aperto e lo si dichiara.

### 3. `task/model/Task.java` — la guardia, sull'entità

`assignTo(Project target)` acquista un primo controllo, **prima** di quello esistente:

```
se target è lo stesso progetto già associato  → nessuna mutazione, si esce: no-op (§2, AC-6)
se this.project != null && this.project.isArchived()
    → ArchivedProjectTaskIsImmutableException(this.project.getId())
se target.isArchived()
    → ArchivedProjectCannotReceiveTasksException(target.getId())   // esistente, invariato
```

Tre punti su cui il codice non deve improvvisare:

- **Il no-op viene per primo.** Il congelamento riguarda le mutazioni: una richiesta che non
  cambia niente non ha niente da rifiutare (ADR-006 §2). È questo ordine che rende `200` la
  risposta al `PUT` idempotente verso lo stesso progetto archiviato, e `409` quella allo
  spostamento — e la differenza è una riga.
- **Poi l'origine, poi la destinazione** (ADR-006 §7): il task è congelato prima ancora che si
  guardi dove sta andando. Deterministico quando entrambi i progetti sono archiviati (AC-5).
- **L'identità si confronta per `id`**, non per riferimento: le due istanze possono venire da
  lookup diversi nella stessa transazione.

La regola sta qui e non nel service per la stessa ragione di ADR-004 §4, ADR-004 §8 e ADR-005
§3: `project` non ha un setter, `assignTo` è l'unico varco, e un punto d'ingresso futuro non
può dimenticare una regola che non ha modo di aggirare.

### 4. `task/exception/ArchivedProjectTaskIsImmutableException.java` — nuovo

Sul modello di `ArchivedProjectCannotReceiveTasksException`. Porta l'id del progetto di
**origine**, che è il progetto da fare `restore` e quindi la parte su cui il chiamante può
agire.

### 5. `task/service/TaskService.java` — L2, L3, L4, L5, L6

- `create(…, projectId)` → **L3**: un solo `findByIdForShare` sulla destinazione. Senza
  `projectId`, nessun lock.
- `assignToProject(taskId, targetId)` → **L4 + L5**:
  1. si carica il task con il progetto risolto (`join fetch`), per leggere l'origine senza una
     seconda query;
  2. si costruisce l'insieme delle righe da bloccare: `{origine se presente, destinazione}`;
  3. lo si **deduplica** (A == B è un lock solo) e lo si **ordina per `id` crescente**;
  4. si acquisiscono i lock in quell'ordine, poi si decide.

  L'ordinamento è esplicito e isolato in un punto solo, perché AC-13 lo osserva e perché è la
  riga che un lettore futuro deve poter trovare.

  **Limite da non mascherare nel codice.** Il lock è sulle righe di `projects`, non sul task:
  due riassegnazioni concorrenti dello stesso task restano last-write-wins (ADR-006 §8, TD-30).
  Il commento su questo metodo deve dirlo — che cosa il protocollo garantisce e che cosa no —
  perché il prossimo lettore non concluda dalla presenza dei lock che il task sia protetto.
  Nessun `@Version` su `Task`, nessun `If-Match`: sarebbe fuori scope.
- `findAllByProject` e `findAll` → **L6**: nessun lock. Un `GET` non ritarda mai un `archive`
  (AC-12).

### 6. `task/controller/TaskExceptionHandler.java` — il solo `409` nuovo

`ArchivedProjectTaskIsImmutableException` → `409` con titolo distinto da quello della
destinazione archiviata. Titoli diversi perché le due situazioni richiedono il `restore` di
**due progetti diversi**.

Resta `@RestControllerAdvice(assignableTypes = …)`. **Nessun handler per i fallimenti di
lock**: niente `503`, niente `Retry-After`, nessun `lock_timeout`, nessuna policy di retry. Un
errore infrastrutturale di locking affiora con la forma di default, come ogni altro errore
infrastrutturale oggi: è una questione di forma della risposta, e appartiene a TD-07 —
tracciata come **TD-29, MINOR subordinato a TD-07** (ADR-006 §5, §7). TD-07, TD-20 e TD-27 non
si toccano (AC-15).

### Nessun settimo file

`Project.java` non cambia. `TaskResponse.java` **non cambia**: il contratto pubblico resta
quello di TASK-003 (AC-8). `V3` resta l'ultima migrazione. Nessun file di configurazione,
nessun cambio di livello di isolamento: resta il `READ COMMITTED` di PostgreSQL, che è
esattamente il livello su cui il ragionamento di ADR-006 §4 è costruito — un
`SELECT … FOR SHARE` che si sblocca rilegge l'ultima versione committata.

## Ordine di lavoro proposto

| # | Passo | Perché in questa posizione |
|---|---|---|
| 1 | I test di concorrenza **prima** del codice, e si guarda che falliscano sul comportamento di oggi | AC-9 e AC-10 devono fallire su `master` così com'è. Se passano subito, è sbagliato il test, non il codice |
| 2 | Repository: le due ricerche con lock | È il protocollo intero |
| 3 | `ProjectService` → L1. AC-10 diventa verde (TD-19 componente a) | Transizioni serializzate |
| 4 | `TaskService` → L2–L6, con l'ordinamento di L5. AC-9, AC-11, AC-13 verdi (TD-25) | Assegnazione serializzata con le transizioni, non con le altre assegnazioni |
| 5 | Guardia sull'entità + eccezione + advice → AC-4, AC-5, AC-6 | La regola, una volta che la concorrenza non può più aggirarla |
| 6 | Test di invariante I-1 / I-2 → AC-1, AC-2 | Dimostra che la cascata non scrive |
| 7 | AC-18: test strutturale su TD-24 e sua mutazione | Decide se TD-24 si chiude o resta aperto |
| 8 | Verifica per mutazione di tutti i test nuovi → AC-17 | Condizione di chiusura |
| 9 | Suite completa, artefatti, `PROJECT_STATE.md` con la contabilità del debito | Fine task |

L'ordine non è cosmetico: **i passi 3 e 4 chiudono TD-19(a) e TD-25 prima che il passo 5
introduca la regola che dipende da loro.** È esattamente la condizione che ADR-005 §4 e
`PROJECT_STATE.md` pongono — sciogliere i due debiti *prima* di scrivere il codice della
cascata, non dopo.

## Come si scrivono i test

### Concorrenza (AC-9, AC-10, AC-11, AC-12)

Due thread, `CountDownLatch`, transazioni aperte a mano (`TransactionTemplate` o
`PlatformTransactionManager`) contro il PostgreSQL reale di Testcontainers — **non**
`@Transactional` sul metodo di test, che confinerebbe tutto in una transazione sola e
renderebbe i test verdi per il motivo sbagliato.

Forma di AC-9, l'unico davvero delicato:

1. T1 apre una transazione, assegna il task, **si ferma prima del commit** sul latch;
2. T2 chiama `archive` e si blocca sul `FOR UPDATE` di L1;
3. il latch si apre, T1 committa;
4. T2 si sblocca, rilegge `ACTIVE`, archivia → `200`.
   Esito: task assegnato, progetto archiviato dopo. Legale (ADR-005 §4).
5. Lo stesso test **nell'ordine inverso** (T2 committa per primo) deve produrre `409` e task
   non assegnato.

Ogni attesa va limitata (latch con timeout, o `awaitility`): un test che si blocca per sempre è
peggio di un test che fallisce. Il limite sta **nel test**, non nella connessione applicativa:
nessun `lock_timeout` di produzione entra in questa task (TD-29).

### Ordine di acquisizione (AC-13) — unitario, non di concorrenza

Un doppio del repository registra la sequenza di chiamate a `findByIdForShare`. Si riassegna un
task da un progetto con id **maggiore** a uno con id **minore** e si asserisce che le chiamate
arrivino in ordine di id crescente, e che siano **due**. Deve fallire se si prende un lock solo,
o se l'ordine segue il ruolo (origine, poi destinazione) invece dell'id.

È un test unitario e non di concorrenza perché con due lock condivisi non c'è oggi un deadlock
da provocare: L5 previene un fallimento che il primo percorso futuro con due lock esclusivi
renderà raggiungibile. Un test che non può fallire non si scrive; questo può, sull'ordine.

### TD-24 (AC-18) — strutturale

Sul modello dei tre test strutturali già presenti in `TaskExceptionHandlerScopeTest`. Asserisce,
per riflessione, che il lookup usato dai percorsi di scrittura di `ProjectService` è `private` e
privo di `@Transactional`, e che i metodi di scrittura non invocano la `findById` pubblica
`readOnly`. Mutazione che deve farlo fallire: riportare `archive` a chiamare `findById`.

**Se il test non si riesce a scrivere in forma che fallisca davvero, TD-24 resta `OPEN`.** Si
dichiara in `PROJECT_STATE.md` e la task si chiude lo stesso. Un debito non si chiude perché il
codice adesso sembra diverso.

### AC-17 — la verifica per mutazione

Per ciascun test nuovo si dimostra il fallimento togliendo esattamente una cosa:

| Test | Mutazione che deve farlo fallire |
|---|---|
| AC-9 | `findByIdForShare` → `findById` |
| AC-10 | `findByIdForUpdate` → `findById` |
| AC-11 | `findByIdForShare` → `findByIdForUpdate` |
| AC-12 | aggiungere un lock a un percorso di lettura |
| AC-13 | bloccare un solo progetto, oppure ordinare per ruolo invece che per id |
| AC-4, AC-5 | togliere la guardia sull'origine in `Task.assignTo` |
| AC-6 | togliere il ramo no-op, così il `PUT` idempotente diventa `409` |
| AC-1 | far scrivere una riga di `tasks` dentro `archive` |
| AC-18 | riportare i percorsi di scrittura a chiamare `findById` |

La review di TASK-003 ha chiuso M-1 e M-2 proprio su questo criterio. Un test di concorrenza è
il posto dove un falso verde è più facile da produrre e più difficile da notare.

## Rischi noti, e cosa si fa

| Rischio | Mitigazione |
|---|---|
| `PESSIMISTIC_READ` mappato a `FOR UPDATE` dal dialetto | AC-11 lo rileva. Se succede, il protocollo va rivisto **in approvazione**, non aggirato nel codice |
| Test di concorrenza fragili o bloccanti | Latch con timeout nel test, nessun `@Transactional` sul metodo di test, nessun timeout di produzione |
| Un test di concorrenza verde per il motivo sbagliato | AC-17, verifica per mutazione obbligatoria |
| AC-18 non scrivibile in forma che fallisca | **TD-24 resta aperto e lo si dichiara.** Non è un fallimento della task |
| Il lock rallenta un `archive` su un progetto molto usato | Dichiarato in ADR-006 §4. Transazioni brevi, una riga sola |
| Un deadlock o una cancellazione amministrativa arriva al client con la forma di errore di default | Dichiarato e tracciato come **TD-29, MINOR subordinato a TD-07**. Nessun timeout, retry o `503` in questa task |
| Si crede, leggendo i lock, che anche il task sia protetto in concorrenza | **TD-30** dichiarato in ADR-006 §8, in `TASK.md` e nel commento del metodo di riassegnazione |
| Emerge la necessità di una migrazione | **Stop e ritorno in approvazione.** Sarebbe il segno che una decisione di ADR-006 era sbagliata |

## Che cosa questo piano **non** fa

Nessuna colonna nuova, nessun flag di archiviazione sui task, **nessun campo nuovo in
`TaskResponse`**, nessuna `@OneToMany`, **nessun `@Version` né su `Project` né su `Task`**,
nessun `ETag`, nessun `If-Match`, **nessun `503`, nessun timeout, nessun retry**, nessun advice
globale, nessun enum di dominio, nessun `DELETE /api/tasks/{id}/project`, nessuna migrazione,
nessun merge, nessun push.

L'elenco completo è in `TASK.md`, sezione «Fuori scope — esplicito».
