# ADR-005 — La relazione `Task` → `Project`: appartenenza opzionale e senza effetti di cascata

- **Stato**: Accettata
- **Data**: 2026-09-11
- **Task**: TASK-003
- **Rapporto con le precedenti**: non supera nessuna ADR. Scioglie le tre domande che
  **ADR-004 §1** aveva esplicitamente rimandato, e si appoggia a ADR-002 (schema di
  proprietà di Flyway) e ADR-004 (ciclo di vita per archiviazione).

## Contesto

ADR-004 ha creato il contenitore e si è fermata lì, con una motivazione precisa: collegare
`Task` a `Project` richiede tre decisioni di dominio che non erano state prese, e una
migrazione che aggiunge una chiave esterna a una tabella già popolata non si annulla con la
stessa facilità di una `CREATE TABLE`.

Quelle tre domande sono l'oggetto di questa ADR:

1. l'appartenenza a un progetto è obbligatoria?
2. cosa si fa delle righe di `tasks` che esistono già?
3. cosa significa archiviare un progetto che ha task?

La terza ha una risposta parziale in questa task e una completa più avanti — la differenza è
il punto §4.

## Decisioni

### 1. `tasks.project_id` è nullable, e lo è per una ragione dichiarata

La colonna è `BIGINT` nullable, con chiave esterna verso `projects(id)`. `NULL` significa
una cosa sola: **questo task non è ancora assegnato a un progetto**.

*Perché non `NOT NULL`.* Un vincolo `NOT NULL` su una tabella già popolata ha bisogno di un
valore per ogni riga esistente, e non esiste un progetto corretto a cui puntare: quelle
righe sono state create quando i progetti non c'erano. Le due strade per soddisfarlo
comunque sono entrambe peggiori del `NULL`:

| Strada | Perché no |
|---|---|
| Creare un progetto sintetico «Unassigned» e puntarci le righe | È il workaround che non si toglie più. Diventa una riga che nessuno può archiviare né cancellare, che ogni listato deve filtrare, che ogni feature successiva deve trattare come caso speciale, e che asserisce una cosa falsa — che quei task appartengano a un progetto |
| Cancellare i task preesistenti | Butta via dati per far entrare un vincolo. L'ordine dei fattori è sbagliato |

*Perché non è un rinvio.* `NULL` qui non è «non abbiamo ancora deciso»: è un'affermazione
vera su quelle righe, e resta vera. Stringere in futuro a `NOT NULL` sarà una decisione a sé
— serviranno un percorso di assegnazione per tutto ciò che è rimasto scoperto e una
migrazione che lo verifichi — e questa ADR non la anticipa.

*Conseguenza operativa.* `ADD COLUMN` senza `DEFAULT` non riscrive la tabella su PostgreSQL:
`V3` è una modifica di soli metadati, istantanea anche su una `tasks` grande, e non può
restare a metà.

### 2. I task esistenti non vengono migrati. Restano senza progetto, e si vede

`V3` non contiene nessuna `UPDATE`. Le righe che c'erano prima restano esattamente come
erano, con `project_id NULL`.

*Perché.* Vale quanto sopra: non c'è un valore corretto da scrivere. Inventarne uno
significherebbe registrare nel database un'informazione che nessuno ha fornito.

*Perché è esplicito e non implicito.* Lo stato è visibile nel contratto — `GET /api/tasks`
restituisce `projectId: null` — ed è recuperabile: `PUT /api/tasks/{id}/project` assegna un
task esistente a un progetto, che è precisamente il percorso di cui quelle righe hanno
bisogno. Un test verifica l'upgrade `V2 → V3` su un database popolato e asserisce che le
righe preesistenti sopravvivano con `project_id NULL`.

### 3. Associare un task a un progetto `ARCHIVED` è `409`

`Task.assignTo(Project)` rifiuta un progetto archiviato. Vale per la creazione
(`POST /api/tasks` con `projectId`) e per l'assegnazione (`PUT /api/tasks/{id}/project`).

*Perché rifiutare.* ADR-004 §3 definisce l'archiviazione come «fuori dal registro
operativo», e ADR-004 §8 ne ha già tratto la conseguenza per il progetto stesso: quello che
è fuori dal registro non si modifica. Un contenitore fuori dal registro che continua ad
accettare lavoro nuovo non è fuori da niente. Consentirlo renderebbe l'archiviazione una
proprietà decorativa.

*Perché `409` e non `403`.* Identica a ADR-004 §8: il rifiuto dipende dallo stato della
risorsa, non dai permessi di chi chiama, ed è il chiamante stesso a poterlo togliere —
`restore` e la stessa richiesta passa. Un test percorre esattamente quella sequenza.

*Perché sull'entità e non nel service.* Identica a ADR-004 §4: se la regola stesse nel
service, ogni nuovo punto di ingresso — un importer, il Planner, un agente — dovrebbe
ricordarsi di riapplicarla. Sull'entità non è aggirabile, perché `project` non ha un setter.

*Cosa questa decisione non dice.* Non dice niente su un task che è **già** in un progetto
quando il progetto viene archiviato. Quello è il punto §4.

### 4. Archiviare un progetto non tocca i suoi task. Non ancora, e non per omissione

`POST /api/projects/{id}/archive` continua a fare esattamente quello che faceva in
TASK-002: cambia lo stato del progetto. I task che vi appartengono restano dove sono, con lo
stato che avevano.

*Perché non adesso.* La cascata `archive`/`restore` verso le entità figlie è una decisione
con conseguenze che il vincolo di scope di questa task esclude, e ha un prerequisito
tecnico esplicito: **TD-19** — l'assenza di controllo di concorrenza su `Project` — è stato
registrato dalla review di TASK-002 con la condizione «da rivalutare prima di dare ad
`archive`/`restore` qualunque effetto su entità figlie». Finché `archive` non tocca nulla
oltre la propria riga, due `archive` concorrenti costano un `200` di troppo. Nel momento in
cui `archive` riscrive anche i task, lo stesso difetto diventa un lost update con
conseguenze sui dati. Le due decisioni vanno prese insieme.

*Perché la relazione è unidirezionale.* `Project` non ha una collezione di task. Una
collezione mappata è precisamente ciò che fa sembrare la cascata un flag di configurazione
invece della decisione di dominio che è: `cascade = ALL` è una parola, e le sue conseguenze
non lo sono. Senza la collezione, dare a `archive` un effetto sui task richiede di scrivere
il codice che lo fa — cioè di deciderlo.

*Conseguenza da conoscere.* Un progetto archiviato può avere task attaccati, e quei task
restano leggibili e — oggi — modificabili per tutto ciò che non è la loro appartenenza. È lo
stato intermedio dichiarato di questa fase, non un difetto: chiuderlo è esattamente il lavoro
della task che scioglierà TD-19.

### 5. Il contratto: assegnare sì, disassegnare no; e l'assegnazione è idempotente

`PUT /api/tasks/{id}/project` con `{"projectId": N}` assegna o sposta. Non esiste un modo
per riportare un task a «senza progetto».

*Perché l'asimmetria.* Aggiungere più tardi un `DELETE /api/tasks/{id}/project` è additivo e
non rompe nessun client. Pubblicarlo ora e ritirarlo dopo rompe ogni client che l'ha trovato.
Fra le due, quella reversibile è non pubblicarlo, e nessun requisito lo chiede: un task
assegnato per errore si corregge spostandolo, non svuotandolo. L'unica porta a senso unico
che resta è `NULL → non NULL`, ed è dichiarata qui.

*Perché idempotente, al contrario di `archive`.* ADR-004 §4 rifiuta un `archive` ripetuto
perché è quasi sempre un errore del chiamante e nasconderlo rende la macchina a stati non
verificabile. Qui non c'è nessuna macchina a stati e nessun errore da esporre: `PUT` su
un'associazione dichiara lo stato finale desiderato, e se è già quello la richiesta ha
ragione. Il ragionamento di ADR-004 §4 non si applica, e applicarlo per simmetria sarebbe
coerenza formale contro il senso del verbo.

*Perché un sotto-risorsa e non un campo di un `PUT /api/tasks/{id}`.* Perché
`PUT /api/tasks/{id}` non esiste: `Task` non ha ancora un aggiornamento generale. Un
endpoint dedicato all'associazione dice nell'URL cosa sta cambiando, e resta la forma giusta
anche quando il resto del task diventerà modificabile.

### 6. `GET /api/projects/{id}/tasks`, e il progetto sconosciuto è `404`

I task di un progetto si leggono come sotto-risorsa del progetto.

*Perché `404` e non una lista vuota.* «Questo progetto non ha task» e «non esiste un
progetto con questo id» sono risposte diverse e un client agisce diversamente: la prima è
uno stato normale, la seconda è un identificatore sbagliato. Il service risolve il progetto
prima di interrogare i task.

*Perché un progetto archiviato risponde normalmente.* Archiviare toglie dal registro
operativo; non rende illeggibile la storia. ADR-004 §8 vincola le **scritture**, non le
letture, e un progetto di cui non si possono più leggere i task sarebbe archiviazione
travestita da cancellazione.

*Perché il controller sta nel package `task`.* La dipendenza fra i due moduli corre in una
direzione sola — un task conosce il proprio progetto, un progetto non sa niente dei task — e
montare questa rotta su `ProjectController` l'avrebbe resa bidirezionale in cambio di un
prefisso di URL.

### 7. La chiave esterna non ha `ON DELETE`, e questa è la decisione

`tasks_project_id_fkey` usa il `NO ACTION` predefinito.

*Perché non `CASCADE`.* Cancellare un progetto distruggerebbe in silenzio i suoi task. Il
Company OS non cancella i progetti (ADR-004 §3): l'endpoint non esiste. Ma una `DELETE`
scritta a mano in `psql` è sempre possibile, e in quel caso il comportamento giusto è che il
database si rifiuti, non che obbedisca.

*Perché non `SET NULL`.* Staccherebbe i task in silenzio, che è lo stesso problema con meno
rumore.

*Conseguenza.* Il rifiuto della `DELETE` è la conversazione che vogliamo avere: chi prova a
cancellare un progetto con task scopre in quel momento che la decisione su cosa farne non è
stata presa. Un test lo asserisce contro PostgreSQL reale.

### 8. Le nuove risposte di errore sono `ProblemDetail`; quelle vecchie non cambiano

`TaskExceptionHandler` è un advice limitato a `TaskController` e `ProjectTaskController`, e
gestisce **solo** le eccezioni introdotte da TASK-003: task non trovato, progetto non
trovato, progetto archiviato. In particolare non gestisce `MethodArgumentNotValidException`.

*Perché quell'omissione è il punto.* Gestirla cambierebbe la forma del `400` che
`POST /api/tasks` produce da TASK-001, cioè un cambio di contratto osservabile su un
endpoint fuori scope. Lasciandola a Spring, gli endpoint preesistenti rispondono esattamente
come prima, e un test lo verifica.

*Conseguenza.* Sotto `/api/tasks` convivono due forme di errore: `ProblemDetail` per le
risposte nuove, il default di Spring per quelle che c'erano già. È la stessa disomogeneità
che TASK-002 ha accettato e registrato come **TD-07**, deliberatamente non allargata qui —
uniformare il contratto di errore di tutta l'API è una decisione a sé che tocca anche
`agents`.

### 9. Il service dei task restituisce DTO, quello dei progetti no

`TaskService` è annotato `@Transactional` e restituisce `TaskResponse`; `ProjectService`
continua a restituire entità che il controller mappa.

*Perché la differenza.* `Task.project` è `LAZY` e `spring.jpa.open-in-view` è `false`: il
contesto di persistenza è chiuso quando il controller vede l'entità, quindi leggere
`projectId` fuori dal service solleverebbe `LazyInitializationException`. La mappatura deve
avvenire dove i dati ci sono. `Project` non ha associazioni e non ha il problema.

*Effetto collaterale voluto.* Con l'entità confinata nel service, l'unico modo per cambiare
il progetto di un task è `assignToProject`, cioè il punto in cui le regole sono.

*Perché `LAZY` e non `EAGER`.* `EAGER` avrebbe evitato il problema caricando sempre il
progetto, anche per le letture che non lo guardano. Le due query che attraversano la
relazione usano un `join fetch` esplicito, che risolve il caso N+1 dove serve senza imporlo
ovunque.

## Alternative scartate

| Alternativa | Perché no |
|---|---|
| `project_id NOT NULL` subito, con progetto «Unassigned» di backfill | Riga sintetica indelebile, caso speciale permanente in ogni feature futura, e asserisce il falso (§1) |
| `project_id NOT NULL` cancellando i task preesistenti | Butta via dati per far entrare un vincolo (§1) |
| Cascata `archive` → task in questa task | Prerequisito esplicito non soddisfatto: TD-19 va sciolto prima (§4) |
| `@OneToMany` su `Project` | Rende la cascata un flag invece di una decisione (§4) |
| `ON DELETE CASCADE` | Una `DELETE` che nessuno ha progettato distruggerebbe i task (§7) |
| `ON DELETE SET NULL` | Stessa perdita, più silenziosa (§7) |
| `DELETE /api/tasks/{id}/project` da subito | Pubblicarlo è irreversibile, non pubblicarlo no, e nessun requisito lo chiede (§5) |
| `PUT /api/tasks/{id}/project` non idempotente, per simmetria con `archive` | Coerenza formale contro il senso del verbo: non c'è nessun errore del chiamante da esporre (§5) |
| `GET /api/tasks?projectId=N` invece della sotto-risorsa | Un progetto inesistente diventerebbe una lista vuota invece di un `404` (§6) |
| Rotta dei task del progetto su `ProjectController` | Renderebbe bidirezionale una dipendenza che oggi corre in una direzione sola (§6) |
| Advice globale, chiudendo TD-07 qui | Cambia il contratto di `agents` e `tasks`, fuori scope (§8) |
| `projectId` come colonna semplice senza `@ManyToOne` | Perde la relazione nel modello e lascia la regola sull'archiviato senza un posto naturale dove vivere |

## Conseguenze operative

- `V3` si applica su un database già a `V2` senza toccare i dati: verificato da un test
  automatico che ferma Flyway a `V2`, scrive righe, e poi migra — e sul volume di sviluppo
  reale `aicompany_postgres_data`, dove il task preesistente è sopravvissuto con
  `project_id NULL`.
- I test che cancellano progetti devono ora cancellare prima i task: la chiave esterna lo
  impone, ed è esattamente il comportamento che un altro test asserisce.
- `SchemaMigrationTest` e `DevSeedMigrationTest` elencano ancora le versioni a mano
  (`"1", "2", "3"`): è deliberato — chi aggiunge una `V4` deve dichiararla. `TD-23` resta
  aperto sull'elenco delle **tabelle**, che questa task non ha cambiato.
- `TaskResponse` ha un campo in più. È un'aggiunta, non una rottura: un client che ignora i
  campi sconosciuti non se ne accorge.
- Resta aperta una finestra di concorrenza nuova, registrata come **TD-25**: fra il
  controllo «il progetto è attivo» e il `commit` dell'assegnazione, un altro thread può
  archiviare il progetto. Oggi la conseguenza si esaurisce in un task attaccato a un
  progetto archiviato — uno stato che il sistema già ammette (§4) e che nessuna regola
  successiva usa. Va chiusa insieme a TD-19, con lo stesso meccanismo, quando `archive`
  acquisterà effetti sui figli.
