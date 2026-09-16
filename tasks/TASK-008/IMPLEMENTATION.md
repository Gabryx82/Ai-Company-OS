# TASK-008 — Implementazione

Le decisioni sono in `docs/adr/ADR-009-optimistic-concurrency-http-contract.md`. Qui c'è come si
realizzano, e le trappole che il piano ha già identificato.

## 1. Ordine di lavoro

1. Test rossi che **dimostrano il problema** sulla baseline (§5).
2. `V5__add_row_version.sql` + `@Version` sulle tre entità.
3. `api/Precondition` e `api/EntityVersion` — interpretazione, verifica, rendering dell'ETag.
4. Tre `ApiProblem` + handler.
5. Firme dei service e dei controller.
6. `GET /api/tasks/{id}`.
7. Suite completa, review avversariale, verifica per mutazione.

## 2. Migrazione

```sql
-- V5__add_row_version.sql
ALTER TABLE tasks    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE projects ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agents   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

Additiva: nessuna colonna rimossa, nessuna tabella creata, nessun vincolo su dati esistenti.

**`DEFAULT 0` non è cosmetico, ed è la lezione di `V4`.** Il seed di sviluppo (`db/dev/V1`) è una
migrazione **già applicata** che inserisce in `agents` senza nominare le colonne nuove;
modificarla ne cambierebbe il checksum e Flyway rifiuterebbe di partire. Il default copre sia le
righe preesistenti sia gli insert che non conoscono la colonna. Trovato dalla suite in TASK-007,
non riscoperto qui.

`NOT NULL` perché Hibernate mappa `@Version` su un `long` primitivo; una colonna nullable
darebbe un `null` su una riga preesistente e il caricamento fallirebbe.

## 3. `@Version` — che cosa fa e che cosa **non** fa

```java
@Version
@Column(nullable = false)
private long version;
```

Fa: incrementare il contatore a ogni `UPDATE` della riga, e solo di quella riga (**P3**).

**Non** fa: rilevare l'intento stantio. Il motivo è in ADR-009 §2.2 e va ripetuto qui perché è la
trappola che rende questa task facile da sbagliare:

> L'entità è caricata con `PESSIMISTIC_WRITE`. Sotto `READ COMMITTED`, una transazione che ha
> **atteso** su quel lock rilegge l'ultima versione committata: carica già `N+1` e scrive
> `N+1 → N+2`. Le versioni coincidono e `OptimisticLockException` non viene mai sollevata.

Conseguenza operativa: **non esiste nessun handler per `OptimisticLockException`**, e non deve
esistere. Aggiungerne uno che risponde `412` sarebbe un `412` che non scatta mai — cioè un pezzo
di contratto che i test possono solo verificare per assenza.

Conseguenza di progetto: `OPTIMISTIC_FORCE_INCREMENT` non si usa da nessuna parte. Se comparisse,
assegnare un task inciderebbe sulla versione del progetto e I-5 diventerebbe falso.

## 4. Il codice

### 4.1 `api/EntityVersion` e `api/Precondition`

```
api/
  EntityVersion.java   // il valore e la sua forma come entity-tag:  7  <->  "7"
  Precondition.java    // le versioni che il chiamante dichiara di aver visto
  PreconditionRequiredException.java
  PreconditionFailedException.java
  InvalidPreconditionException.java
```

`Precondition` porta l'insieme delle versioni accettate e **una sola operazione utile**:

```java
void requireSatisfiedBy(long currentVersion);   // altrimenti PreconditionFailedException
```

L'interpretazione dell'header sta in una factory statica:

| Valore di `If-Match` | Esito |
|---|---|
| assente, vuoto | `PreconditionRequiredException` → `428` |
| `*` | `InvalidPreconditionException` → `400` |
| `W/"7"` | `InvalidPreconditionException` → `400` — RFC 9110: `If-Match` usa il confronto forte |
| `"7"` | `Precondition{7}` |
| `"7", "9"` | `Precondition{7, 9}` — corrisponde se **una** è quella corrente |
| `"abc"`, `7`, `"7` | `InvalidPreconditionException` → `400` |

### 4.2 Controller

Interpreta l'header e passa il risultato al service. Non verifica niente: non possiede il lock.

```java
@PutMapping("/{id}/project")
ResponseEntity<TaskResponse> assignToProject(
        @PathVariable Long id,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @Valid @RequestBody TaskProjectAssignmentRequest request) {

    TaskResponse updated = service.assignToProject(
            id, request.projectId(), Precondition.fromHeader(ifMatch));

    return ResponseEntity.ok().eTag(updated.etag()).body(updated.response());
}
```

`required = false` è deliberato: con `required = true` Spring solleverebbe
`MissingRequestHeaderException`, che l'advice tradurrebbe in un `400` generico. Il `428` è una
decisione nostra e va presa da noi.

### 4.3 Service — dove va la riga

L'unico posto che rispetta **P1** e **P2**:

```java
public TaskResponse assignToProject(Long taskId, Long projectId, Precondition precondition) {

    // L0. Prima di leggere qualunque cosa su dove vive questo task.
    Task task = repository.findByIdForUpdate(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));

    // P1, P2. Dopo il lock, prima delle guardie, prima del no-op idempotente.
    precondition.requireSatisfiedBy(task.getVersion());

    ... // L4, L5, L2 e le guardie, invariati
}
```

Le due righe **non** sono invertibili e **non** sono spostabili:

- **prima** del lock → check-then-act: due richieste con lo stesso ETag valido passano entrambe;
- **dopo** `assignTo` → la scorciatoia idempotente ha già restituito `200` a un chiamante che
  aveva letto uno stato che non esiste più (**P2**, e I-3 lo asserisce).

Lo stesso innesto, dopo `lockForWrite`, in `ProjectService.update/archive/restore` e in
`AgentService.update/activate/deactivate`.

### 4.4 La trappola del flush

`TaskService` compone la risposta **dentro** la transazione, perché `Task.project` è lazy. La
versione, però, viene incrementata al **flush**, che di default arriva al commit — cioè *dopo*.
Una risposta composta ingenuamente porterebbe la versione **vecchia**, e il client la userebbe
come `If-Match` per la chiamata successiva, ricevendo un `412` per una scrittura di cui è l'unico
autore.

Rimedio: `saveAndFlush` sui percorsi di mutazione, così il contatore è già quello nuovo quando la
risposta si compone. **I-7 esiste per questo**, e è il test che tiene onesto questo paragrafo:
*l'ETag restituito da una mutazione è identico a quello che la `GET` successiva restituisce.*

`ProjectService` e `AgentService` restituiscono l'entità e il controller la mappa dopo il commit,
quindi lì il problema non si pone — ma `saveAndFlush` era già in uso per la traduzione della
violazione dell'indice, e resta.

### 4.5 `GET /api/tasks/{id}`

Nuovo, e necessario: senza di esso l'unico modo di conoscere la versione di un task sarebbe
scorrere il listato completo. `200` con l'ETag, `404` con `task-not-found`. Nessun lock (**L6**).

Effetto collaterale sul contratto: `DELETE /api/tasks/{id}` passa da `404` a `405`, che dice «non
per questa via» invece di «non c'è niente qui» — la stessa cosa che `ProjectController` fa già di
proposito.

### 4.6 Rendering dell'ETag

Su `GET /{id}`, sulle creazioni (`201`) e sulle mutazioni (`200`), per tutte e tre le risorse.
Non sui listati (**TD-33**), non nei corpi JSON (ADR-009 §7).

## 5. I test rossi, e che cosa devono provare

Prima del codice, e rossi **per la ragione attesa**. Un test che passa subito è un test sbagliato.

| Test | Che cosa dimostra sulla baseline |
|---|---|
| `mutationWithoutIfMatchIsRejected` (×7 rotte) | Oggi è `200`: la precondizione non esiste |
| `mutationWithStaleIfMatchIsRejected` (×7) | Oggi è `200` **e la scrittura passa** — il lost update di TD-28/TD-30, osservabile |
| `stalePreconditionIsCheckedBeforeTheIdempotentNoOp` | Oggi è `200` no-op |
| `twoConcurrentReassignmentsWithTheSameEtagProduceOneSuccessAndOneFailure` | Oggi sono due `200`: **è TD-30** |
| `assigningATaskDoesNotChangeTheProjectVersion` | Oggi non c'è versione da confrontare |
| `theEtagOfAMutationIsTheEtagOfTheNextRead` | Oggi non c'è ETag |
| `getTaskByIdReturnsTheTaskAndItsEtag` | Oggi `GET /api/tasks/{id}` è `405` |
| `wildcardIfMatchIsRejected` | Oggi l'header è ignorato |
| `upgradeToV5PreservesRowsAndDefaultsVersionToZero` | Oggi `V5` non esiste |

Il test di concorrenza segue la forma già in uso: due thread, transazioni controllate a mano,
sincronizzazione a latch, **mai** `sleep`.

## 6. Verifica per mutazione

Tre mutazioni, una per proprietà portante. Ciascuna toglie **esattamente una cosa**.

| Mutazione | Test che deve diventare rosso |
|---|---|
| Rimuovere `precondition.requireSatisfiedBy(...)` | I-1, I-2 |
| Spostarla **prima** di `findByIdForUpdate` | I-4 — il test di concorrenza, e solo quello: è la prova che il lock serve al confronto |
| Spostarla **dopo** `task.assignTo(...)` | I-3 — la precondizione contro il no-op |

La seconda è la più informativa: se I-4 restasse verde con la precondizione fuori dal lock,
significherebbe che il test non sta osservando l'interleaving che dichiara.

## 7. Rischi

| Rischio | Mitigazione |
|---|---|
| L'ETag della risposta è quello pre-flush | §4.4, e I-7 lo asserisce su ogni rotta |
| Un `412` che non scatta mai perché ci si è affidati a `OptimisticLockException` | Nessun handler per essa, §3. Il rilevatore è il confronto esplicito |
| La precondizione finisce fuori transazione in un refactor futuro | La firma del service la richiede come parametro: non c'è un percorso che la salti senza cancellare un argomento |
| `V5` fallisce sul seed di sviluppo | `DEFAULT 0`, §2 |
| Il test di concorrenza è un falso verde | Verifica per mutazione, §6, riga 2 |
