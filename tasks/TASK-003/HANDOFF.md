# TASK-003 — HANDOFF → Codex (review differenziale)

## Cosa è stato fatto

La prima relazione persistente del Company OS: **`Task` → `Project`**, chiave esterna
PostgreSQL, mapping JPA, API per assegnare, listato per progetto.

Vincolo di scope rispettato: la relazione **esiste ed è imposta dal database**, ma
`archive`/`restore` di un progetto **non producono ancora nessun effetto** sui suoi task.

## Le tre decisioni che ADR-004 §1 aveva rimandato

Erano il prerequisito dichiarato della task. Prese prima di scrivere codice, motivate per
esteso in `docs/adr/ADR-005-task-project-association.md`.

| # | Decisione | In una riga |
|---|---|---|
| 1 | **`project_id` nullable** | Non esiste un valore corretto per le righe preesistenti. Un progetto «Unassigned» di backfill sarebbe un workaround indelebile che asserisce il falso |
| 2 | **Nessuna migrazione dei task esistenti** | `V3` non ha `UPDATE`. Restano `NULL`, lo stato è visibile (`projectId: null`) ed è recuperabile con `PUT /api/tasks/{id}/project` |
| 3 | **Progetto `ARCHIVED` → `409`** | Un contenitore fuori dal registro operativo non riceve lavoro nuovo. `409` e non `403` perché è il chiamante a poter togliere il rifiuto: `restore`, e la stessa richiesta passa |

## Decisioni conseguenti, meno ovvie

| Scelta | Motivo |
|---|---|
| Relazione **unidirezionale**, nessuna `@OneToMany` su `Project` | Una collezione mappata fa sembrare la cascata un flag di configurazione. Senza, darle un effetto richiede di scrivere il codice, cioè di deciderlo |
| Chiave esterna **senza `ON DELETE`** | `NO ACTION` è la decisione: una `DELETE` a mano su un progetto con task viene rifiutata invece di cascare o staccare in silenzio |
| **Nessun** `DELETE /api/tasks/{id}/project` | Pubblicare un endpoint è irreversibile, non pubblicarlo no. Un task assegnato per errore si sposta |
| `PUT /api/tasks/{id}/project` **idempotente**, al contrario di `archive` | `PUT` dichiara lo stato finale desiderato; se è già quello la richiesta ha ragione. Non c'è nessun errore del chiamante da esporre |
| `GET /api/projects/{id}/tasks`, progetto inesistente → **`404` non lista vuota** | «Non ha task» e «non esiste» sono risposte diverse e un client agisce diversamente |
| `ProjectTaskController` nel package **`task`** | La dipendenza fra i moduli corre in una direzione sola; montarla su `ProjectController` l'avrebbe resa bidirezionale per un prefisso di URL |
| `TaskService` restituisce **DTO** | `Task.project` è `LAZY` e `open-in-view=false`: la mappatura deve avvenire dentro la transazione. `ProjectService` non ha associazioni e resta com'era |
| Advice sui task che gestisce **solo** le eccezioni nuove | Gestire anche la validazione cambierebbe la forma del `400` preesistente. TD-07 non viene né chiuso né allargato |

## Verifiche

```
./mvnw -B clean test  ->  Tests run: 107, Failures: 0, Errors: 0, Skipped: 0  -  BUILD SUCCESS
```

77 prima, 107 adesso: 20 di contratto HTTP, 7 di persistenza, 2 di schema, 1 di upgrade
incrementale.

**Upgrade `V2 → V3` verificato due volte**, come richiesto:

1. *Automatico* — `MigrationStreamTest.taskProjectRelationIsAddedToAPopulatedV2Database`:
   Flyway fermato a `V2` con `target`, righe scritte, poi `migrate`. Una sola migrazione
   eseguita; righe preesistenti intatte con `project_id NULL`; la FK rifiuta un id che non
   risolve; lo stream avanza ancora oltre `V3`.
2. *Reale* — volume `aicompany_postgres_data`, **non cancellato**. Il task preesistente
   (`id 1`, «ok») è sopravvissuto con `project_id NULL`, seed intatto, `validate` di
   Hibernate soddisfatto. Smoke test HTTP completo su tutti i percorsi nuovi e sulle
   invarianti di TASK-001 e TASK-002. Righe di smoke test rimosse al termine.

Dettaglio in `ARTIFACT.md`.

## Punti su cui è più utile un parere indipendente

1. **`project_id` nullable è la scelta giusta per questa fase?** L'alternativa `NOT NULL`
   richiede un progetto sintetico di backfill, che è il workaround che il vincolo di scope
   escludeva. Ma il prezzo è che d'ora in poi ogni lettura deve considerare il caso «task
   senza progetto», e quel caso non scompare da solo: servirà una task che lo chiuda
   deliberatamente. Il momento giusto per stringere è quando esisterà il percorso che
   assegna tutto ciò che è rimasto scoperto — o c'è una ragione per farlo prima?

2. **`404` per un `projectId` che non risolve dentro un `POST /api/tasks`.** Lo stato dice
   «non trovato» di una risorsa che non è quella dell'URL. Ho scelto la coerenza — stessa
   causa, stesso stato, ovunque compaia — e lasciato al `title` il compito di dire *quale*
   dei due identificatori era il problema. `422 Unprocessable Entity` sarebbe più preciso
   sulla semantica ma introduce un quarto codice; `400` si confonderebbe con la Bean
   Validation. Concordi, o `422` vale la sua asimmetria?

3. **Nessun `DELETE /api/tasks/{id}/project`.** Significa che `NULL → non NULL` è una porta a
   senso unico: un task assegnato non torna mai «senza progetto». È dichiarato in ADR-005 §5
   e giustificato dall'asimmetria fra aggiungere e ritirare un endpoint, ma è comunque una
   capacità che manca. Vale la pena anticiparla?

4. **`PUT` idempotente contro `archive` non idempotente.** Nella stessa API due verbi hanno
   politiche opposte sul «ripetere la stessa richiesta». Ho una ragione per ciascuna, ma
   sono due ragioni, non una regola: un lettore potrebbe legittimamente leggerla come
   incoerenza.

5. **`TaskService` restituisce DTO, `ProjectService` entità.** La causa è reale (il `LAZY`),
   ma il risultato è che due service dello stesso backend hanno due forme. Meglio
   allineare `ProjectService` adesso, o lasciare che la forma segua la causa?

6. **`GET /api/tasks` non ha filtri.** Non c'è modo di chiedere «i task senza progetto» se
   non leggendoli tutti. È esattamente il caso d'uso di chi deve sistemare le righe
   preesistenti. Un `?unassigned=true` sarebbe stato in scope; l'ho lasciato fuori per non
   aggiungere superficie prima che serva.

## Debito nuovo, da registrare

**TD-25 — finestra di concorrenza sull'assegnazione.** Fra il controllo «il progetto è
`ACTIVE`» e il `commit` dell'assegnazione, un altro thread può archiviare il progetto. Il
risultato è un task attaccato a un progetto archiviato — uno stato che il sistema già ammette
(un progetto si archivia liberamente con task dentro, ADR-005 §4) e che oggi nessuna regola
successiva usa. **Va chiusa insieme a TD-19, con lo stesso meccanismo**, nel momento in cui
`archive` acquisterà effetti sui figli.

## TD-19: stato invariato, e perché

TD-19 chiede controllo di concorrenza su `Project` e porta la condizione «da rivalutare prima
di dare ad `archive`/`restore` qualunque effetto su entità figlie».

**Questa task non gliene ha dato nessuno**, deliberatamente: `archive` fa esattamente quello
che faceva in TASK-002, cambia lo stato della propria riga. Quindi la condizione non è
scattata e TD-19 resta aperto, come richiesto dal vincolo di scope.

Resta vero che è il prossimo nodo: la task che introdurrà la cascata deve sciogliere TD-19
**e** TD-25 prima di scriverne il codice, non dopo.

## Rilievi e debito ancora aperti, non toccati

Della review TASK-002: **TD-20** (JSON malformato su `/api/projects` non produce
`ProblemDetail`), **TD-21** (asserzione debole sul contratto di errore), **TD-22** (nessun
test del percorso incrementale `V1 → V2` — questa task ne aggiunge uno per `V2 → V3`, ma non
copre `V1 → V2`), **TD-23** (elenco delle tabelle fisso in `MigrationStreamTest`), **TD-24**
(`ProjectService.findById` `readOnly` chiamato via `this.`).

Le tre scelte di contratto lasciate aperte da TASK-002 restano tali: `archive` non
idempotente, `GET /api/projects` senza filtro include gli archiviati, advice limitato a un
controller.

Della review TASK-001: **R3**, **R5**, **R6**, **R7**, più il debito devtools sull'exit code
di `spring-boot:run`. Aperto il LOW su `GET /api/tasks/{id}`.

Debito di progetto: TD-04, TD-07, TD-08, TD-11, TD-12/TD-13, TD-14, TD-15, TD-17/TD-18.
Correzioni documentali ai file `docs/audit/*` di TASK-000: ancora da applicare.
`docs/RUNNING.md` non documenta né `/api/projects` né gli endpoint nuovi.

## Esito della review

Review indipendente eseguita, verdetto `FIX REQUIRED` con **un solo fix obbligatorio**.
Nessun difetto di comportamento: migrazione, FK, indice, assenza di cascade, tutti i percorsi
HTTP, i confini transazionali, l'assenza di N+1 e la compatibilità del contratto preesistente
sono stati verificati su database e istanza reali, e reggono. I due rilievi corretti sono
entrambi di qualità dei test.

| Rilievo | Cosa era | Cosa è ora |
|---|---|---|
| **M-1** | `theValidationContractOfTheTaskApiIsUnchanged` asseriva `$.errors` e `$.title` assenti sulla risposta di `POST /api/tasks {}`. Sotto MockMvc il body è **vuoto** — `sendError` senza dispatch a `/error` — quindi ogni `doesNotExist()` passava a vuoto. L'API reale restituisce invece `errors`: il test asseriva l'assenza di qualcosa che c'è, e sarebbe rimasto verde anche riscrivendo il contratto che dichiarava di proteggere | Rimosso, sostituito da `TaskExceptionHandlerScopeTest`: 3 asserzioni strutturali sulle annotazioni, dove la decisione è presa davvero. Insieme esatto delle eccezioni gestite; nessun tipo gestito assegnabile da `MethodArgumentNotValidException` (copre anche `BindException`, `RuntimeException`, `Exception`); `assignableTypes` esattamente i due controller, con `basePackages` e `annotations` vuoti |
| **M-2** | `associationSurvivesAWriteAndReadCycle` non eseguiva nessun read cycle. Con `@Transactional`, `findById` restituisce l'istanza del first-level cache: eseguito con `show-sql`, **nessuna `SELECT` su `tasks` dopo l'insert**. Nemmeno il path lazy veniva esercitato | `entityManager.clear()` prima della rilettura, più `isNotSameAs(task)` che fa fallire il test se qualcuno toglie il `clear`. Ora la riga viene riletta dal database e `getProject()` inizializza un proxy reale |

**Entrambe le guardie sono state verificate per mutazione**, non solo eseguite: introdotto in
`TaskExceptionHandler` un handler per `MethodArgumentNotValidException` → falliscono
`beanValidationFailuresAreLeftToSpring` e `theAdviceHandlesOnlyTheExceptionsTaskThreeIntroduces`;
rimosso `entityManager.clear()` → fallisce `isNotSameAs`. Mutazioni rimosse.

Corretta anche **ADR-005 §9**: la motivazione originale del mapping DTO nel service diceva
che leggere `projectId` nel controller solleverebbe `LazyInitializationException`. Non è vero
con le query di oggi, che usano tutte `join fetch`. La ragione vera — difesa contro la
prossima query scritta senza `join fetch` — è più debole e ora è scritta per quella che è.

Rilievi LOW-1…LOW-6 **non corretti**, per scelta: due sono stati registrati come debito
(TD-26, TD-27), gli altri sono osservazioni di forma senza conseguenza raggiungibile oggi.

## Vincolo

TASK-004 **non avviata**. Nessun merge, push, remote o riscrittura di storia.
