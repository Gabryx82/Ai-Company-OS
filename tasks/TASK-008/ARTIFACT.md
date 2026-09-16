# TASK-008 — Artefatto

**Optimistic concurrency nel contratto HTTP.** Chiude **TD-28** e **TD-30**, apre **TD-32** e
**TD-33**. Suite **158 → 179**, schema **`V4` → `V5`**.

## 1. Che cosa esiste adesso che prima non c'era

I lock di ADR-006 serializzano. Non rilevano. Da oggi il sistema fa entrambe le cose, con due
meccanismi distinti che non si sostituiscono a vicenda:

| | Consistenza interna | Intento stantio del client |
|---|---|---|
| Meccanismo | **L0–L7**, ADR-006 | **`ETag` / `If-Match`**, ADR-009 |
| Domanda | la regola è stata applicata a dati veri? | il chiamante sapeva su cosa stava scrivendo? |
| Invariato da questa task | ✅ nessuna riga del protocollo di lock è cambiata | — |

Il lock rende **atomico** il confronto; il confronto rende **visibile** la staleness.

## 2. Il protocollo, in una riga per regola

- **P0** — ogni mutazione di risorsa esistente richiede `If-Match`; assente → `428`.
- **P1** — il confronto sta **dentro la transazione, dopo il lock esclusivo, prima delle regole**.
- **P2** — prima di ogni regola di dominio e di ogni scorciatoia idempotente.
- **P3** — la versione conta la **propria** riga e nessun'altra; `OPTIMISTIC_FORCE_INCREMENT` vietato.
- **P4** — clausola di chiusura: ogni write path futuro eredita P0–P3, **TASK-009 incluso**.

## 3. Superficie cambiata

| Rotta | Cambia |
|---|---|
| `GET /api/tasks/{id}` | **nuova**. `200` + `ETag`, o `404 task-not-found` |
| `GET /api/{projects,agents}/{id}` | header `ETag` |
| `POST /api/{tasks,projects,agents}` | header `ETag` sul `201`. Nessun `If-Match`: non c'è stato precedente |
| `PUT /api/tasks/{id}/project` | **`If-Match` obbligatorio** + `ETag` |
| `PUT /api/projects/{id}`, `POST /{id}/archive`, `/restore` | **`If-Match` obbligatorio** + `ETag` |
| `PUT /api/agents/{id}`, `POST /{id}/activate`, `/deactivate` | **`If-Match` obbligatorio** + `ETag` |
| Listati | invariati, **nessun `ETag`** (TD-33) |

Tre `type` nuovi, dentro ADR-007 senza eccezioni: `precondition-required` (428),
`precondition-failed` (412), `invalid-precondition` (400).

**`V5` additiva**: `version BIGINT NOT NULL DEFAULT 0` su `tasks`, `projects`, `agents`. Il
`DEFAULT` è la lezione di `V4`: il seed di sviluppo è una migrazione già applicata che non può
imparare una colonna nuova senza cambiare checksum.

## 4. Rotture dichiarate

1. **Ogni mutazione senza `If-Match` passa da `200` a `428`.** È la rottura più grande della fase,
   ed è deliberata: con `If-Match` facoltativo TD-28 e TD-30 non si chiudono, si rendono
   *evitabili*. Nessun client reale esiste ancora, quindi il costo è oggi il più basso che sarà mai.
2. **Il perdente di due transizioni concorrenti vede `412` dove vedeva `409`.** Conseguenza non
   prevista dal piano, trovata dai test di concorrenza di TASK-004 e TASK-007. La transizione
   illegale resta ciò che riceve un chiamante **aggiornato**, ed è esercitata sequenzialmente.
3. `PUT` idempotente con ETag stantio: da `200` no-op a `412` (**P2**).
4. `DELETE /api/tasks/{id}`: da `404` a `405`, effetto collaterale della rotta nuova.

## 5. Che cosa la verifica per mutazione ha detto

Tre mutazioni. **Due hanno smentito il piano**, e sono la parte che vale la pena leggere.

| Mutazione | Esito |
|---|---|
| Confronto neutralizzato | **Rosso**, 4 test |
| Confronto **sopra** il lock | **Rosso**, e **solo** `PreconditionConcurrencyTest` — il contract test resta 18 verdi |
| Confronto **sotto** le regole | **Verde.** Il test che doveva coprirlo non copriva niente |

**La terza è la scoperta.** Il piano diceva che sotto `assignTo` il confronto non sarebbe girato
sul percorso idempotente, «perché quel metodo esce presto». `Task.assignTo` esce presto da **sé**,
non dal service: il confronto gira lo stesso e il `412` arriva lo stesso. Il test di I-3 stava
asserendo qualcosa di vero in entrambi i mondi.

Ciò che la posizione decide davvero è **quale rifiuto** riceve un chiamante stantio: `412` prima
delle regole, oppure il `409` del dominio dopo — una risposta sullo stato nuovo, a chi non sa
nemmeno che la risorsa si è mossa. Da lì il test nuovo, che sotto mutazione diventa rosso con
`expected:<412> but was:<409>`, e **P2 riscritta**.

**La seconda ha aggiunto un fatto.** Sopra il lock il mutante non perde la riga: Hibernate solleva
`StaleObjectStateException` alla lettura bloccante. Perde la **risposta** — `500` invece di `412`.
Quindi il contatore compra una seconda cosa, più piccola e dichiarata come tale: sbagliare
l'ordine fallisce **rumorosamente** invece di perdere una scrittura in silenzio. È una rete, non
il meccanismo (ADR-009 §2.3).

**E un test scritto oggi era sbagliato.** `PreconditionCoverageTest` tentava di dedurre «è davvero
una creazione» dalla firma, e sbagliava sul primo metodo: `TaskService.create` prende un
`projectId`, cioè l'id di **un'altra** riga. Un'euristica che non sa distinguere è peggio di
nessuna — fallisce su codice corretto e insegna a modificare il test finché passa. Sostituita da
un insieme pinnato.

## 6. Invarianti e come sono verificati

| ID | Verifica |
|---|---|
| I-1 `428` senza `If-Match`, e nulla cambia | `everyMutationOfAnExistingResourceRequiresIfMatch`, 7 rotte, con rilettura |
| I-2 `412` con tag stantio, e nulla cambia | `everyMutationRejectsAStaleEntityTagAndWritesNothing` |
| I-3 precondizione prima del rifiuto di dominio | `aStaleCallerIsToldItIsStaleAndNotWhatIsWrongWithTheNewState` — **e solo questo** discrimina |
| I-4 confronto **sotto lock** | `PreconditionConcurrencyTest`, due thread, latch, mutazione |
| I-5 la versione conta la propria riga | `assigningATaskDoesNotChangeTheProjectsEntityTag` |
| I-6 un no-op non consuma il tag | `aMutationThatChangesNothingDoesNotConsumeTheEntityTag` |
| I-7 l'ETag di una mutazione è quello della lettura successiva | `theEntityTagReturnedByAMutationIsTheOneTheNextReadReports`, 4 rotte — è il test che coglie la trappola del flush |
| I-8 `V5` additiva | il test sulle coppie consecutive di TASK-006, **senza aggiungere un caso** |
| I-9 `*` rifiutato | `theWildcardPreconditionIsRefused` |
| I-10 ogni `type` enumerato | `ApiProblemCoverageTest`, esteso alle eccezioni di protocollo |
| I-11 il protocollo di lock non si indebolisce | i test di concorrenza di TASK-004 e TASK-007, verdi |
| **P4 eseguibile** | `PreconditionCoverageTest`: l'insieme dei write path è pinnato, e aggiungerne uno obbliga a decidere |

## 7. Rilievi della review del proprio diff

**HIGH / MEDIUM**: corretti nella task, e sono i tre di §5 — due claim falsi nei documenti e
un'euristica sbagliata in un test.

**LOW, registrati e non fatti:**

| # | Rilievo |
|---|---|
| L-1 | `TaskService.findById` non fa `join fetch`: leggere un task singolo costa due query, perché il proxy lazy si inizializza mappando la risposta. Una riga sola, e il percorso di listato usa già `findAllWithProject` |
| L-2 | `If-Match` inviato su una creazione viene **ignorato** invece che rifiutato. Non è un bypass — non c'è niente da proteggere — ma è silenzio dove ci potrebbe essere un `400` |
| L-3 | `Versioned<T>` esiste solo per i task. Asimmetria reale e documentata sulla classe: progetto e agente arrivano al controller come entità e la versione si legge da lì |
| L-4 | Lo split su `,` in `Precondition.fromHeader` non gestisce un entity-tag che contenga una virgola. Nessun tag emesso da questa API può contenerne una, e un tag estraneo finisce in `400`, che è la risposta giusta |

## 8. File

```
backend/src/main/resources/db/migration/V5__add_row_version.sql        nuovo
backend/src/main/java/com/aicompany/backend/api/
    Precondition.java  ETags.java  Versioned.java                      nuovi
    PreconditionRequiredException.java
    PreconditionFailedException.java
    InvalidPreconditionException.java                                  nuovi
    ApiProblem.java  ApiExceptionHandler.java                          +3 problemi
{task,project,agent}/model/*.java                                      @Version
{task,project,agent}/service/*.java                                    P1, P2
{task,project,agent}/controller/*.java                                 If-Match, ETag
backend/src/test/java/com/aicompany/backend/api/
    PreconditionContractTest.java        18 test, scritti rossi
    PreconditionConcurrencyTest.java     I-4, provato per mutazione
    PreconditionCoverageTest.java        P4 eseguibile
backend/src/test/java/com/aicompany/backend/support/Preconditions.java
docs/adr/ADR-009-optimistic-concurrency-http-contract.md               nuovo
tasks/TASK-008/{TASK,CONTEXT,IMPLEMENTATION,ARTIFACT,HANDOFF}
```
