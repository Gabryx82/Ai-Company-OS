# CODEX_REVIEW — Review architetturale differenziale di TASK-000

Data: 2026-09-11. Base verificata: `task-000-audit`, HEAD `503663c`.

## Esito

**Audit utilizzabile con correzioni; approvazione consigliata dell'indirizzo incrementale, non della sequenza attuale senza modifiche.** Mantenere Spring Boot è ragionevole. Il servizio Python è una buona opzione per il runtime AI, ma il solo fatto che il backend compili non dimostra che due servizi siano la soluzione ottimale. TASK-001 è il prossimo passo corretto se ristretto a persistenza riproducibile, migrazioni e relative verifiche, senza M-2 come estensione implicita.

Prima dell'apertura di TASK-001 vanno chiariti baseline Git, significato della migrazione iniziale e trattamento del seed. Prima di M-2 servono test e contratti che isolino le entità. Prima di chiamate AI reali servono un confine di sicurezza e un contratto di esecuzione.

## Perimetro ed evidenze

Letti i documenti richiesti, il prompt di review e `tasks/TASK-000/{CONTEXT.yaml,TASK.md}`. Approfondimenti limitati a `REUSABLE_COMPONENTS.md`, `TECHNICAL_DEBT.md`, direzione architetturale e master prompt; verificati soltanto POM, configurazione, entità Agent/Task, initializer e percorso di creazione Task per risolvere contraddizioni specifiche. Consultati stato Git, rami, remote, storia e statistiche dei due commit successivi a master.

Nessun inventario ricorsivo, nuova sonda runtime o build. Gli esiti runtime dell'audit restano evidenze riportate da Claude, non verifiche ripetute da questa review. Non è possibile ricostruire dal solo Git il contenuto dell'index precedente al primo commit del backend, né dimostrare l'assenza di backup esterni. Nessuna modifica applicativa o operazione Git di scrittura eseguita.

## Rilievi prioritari

### R1 — Alto: stato Git obsoleto e baseline di lavoro ambigua

`PROJECT_STATE.md`, handoff e M-0 dicono che il backend è staged ma non committato. Alla review, prima della creazione di questo file, working tree e index risultavano puliti:

| Riferimento | Stato verificato |
|---|---|
| `master` | `930f70f`, Initial project structure |
| Backend | `ea25bee`, Add Spring Boot backend with agent, task and orchestrator contexts |
| `task-000-audit` / HEAD | `503663c`, Add Company OS governance and TASK-000 repository audit |
| Remote | Nessuno configurato |
| `backend/HELP.md` | Già tracciato e committato |
| `.gitignore` di radice | Non presente tra i file tracciati |

Il ramo di audit comprende quindi **anche l'introduzione del backend rispetto a master**. Il commit `503663c` non include file applicativi; questo supporta la separazione dell'audit dal codice, ma non prova che il commit precedente sia identico al contenuto pre-audit non versionato. Nessuna evidenza sufficiente per accusare Claude di aver modificato il codice preesistente.

**Correzione:** M-0 deve verificare e documentare questa base, senza ricommittare il backend o rimuovere file dall'index alla cieca. Un futuro ramo TASK-001 deve partire dalla base che contiene entrambi i commit, non da `master` nudo; fare cherry-pick del solo audit perderebbe il backend. L'eventuale integrazione su master resta separata e non autorizzata qui. Conservare la storia; nessun reset, rebase o rinomina necessari per questa review. Un remote configurato non equivale a un backup: occorre un push verificato o un altro backup verificato. L'assenza di remote non dimostra che il disco locale sia l'unica copia esistente. M-0 non ha rischio «nullo», perché selezione dei file e destinazione remota hanno conseguenze reali.

### R2 — Alto: test e contratti sono ordinati dopo i cambiamenti che dovrebbero proteggere

`GAP_ANALYSIS.md` §4 e TD-09 richiedono test prima del refactoring; `MIGRATION_MAP.md` §4 mette invece M-4 dopo M-2/M-3. Inoltre M-2 introduce relazioni JPA mentre le entità sono ancora il contratto HTTP: possibili cambiamenti involontari della risposta, caricamenti lazy e, se le relazioni saranno bidirezionali, cicli di serializzazione.

**Correzione:** introdurre caratterizzazione dei comportamenti validi e test PostgreSQL/migrazioni dentro M-1. Non cristallizzare come requisito il POST vuoto accettato. Anticipare DTO, validazione ed errori minimi prima o insieme alle relazioni di M-2; OpenAPI e CRUD aggiuntivo possono seguire. M-4 diventa consolidamento della CI e della copertura, non la prima rete di sicurezza.

### R3 — Alto: M-5/M-6 promettono esecuzioni senza definirne stato e autorizzazione

M-5 introduce credenziali e provider, M-6 promette il lancio di un'esecuzione, ma M-7 colloca successivamente la sicurezza. La clausola «anticipare se vengono introdotte credenziali» è già attivata dal contenuto di M-5. Inoltre invocare un modello non equivale a implementare un Task Engine: mancano identità dell'esecuzione, risultato persistito, errori, retry e collegamento con task/progetto.

**Correzione:** sicurezza minima obbligatoria prima di M-5 o prima di qualsiasi esposizione condivisa, se precedente: identità del chiamante, autorizzazione sul progetto, autenticazione tra servizi, segreti esclusi dai DTO/log, limiti di richieste e spesa. CORS non sostituisce l'autorizzazione. Fino a quel punto sviluppo locale con bind esplicito a loopback, incluso PostgreSQL; non dedurre l'esposizione Internet dall'assenza di auth.

Definire una `Execution/Run` distinta dalla definizione di `Task`, con ID, stato, tentativo, correlazione, output/errore e consumo. La prima versione può essere una singola inferenza con timeout, senza introdurre subito code o LangGraph. Se si promettono retry e recupero dopo crash, occorrono idempotenza e persistenza del dispatch; non presumere una transazione unica tra DB, HTTP e provider. L'approvazione umana applicativa deve precedere strumenti con effetti esterni; le policy Markdown attuali non sono un gate runtime. RBAC esteso può essere incrementale, questo confine minimo no.

### R4 — Medio: migrazione iniziale e seed non sono ancora una specifica eseguibile

M-1 parla di «baseline dallo schema Hibernate corrente». La configurazione verificata usa H2 in memoria: per un PostgreSQL nuovo serve una **migrazione iniziale versionata che crei lo schema**, non soltanto marcare uno schema esistente come baseline. Lo schema H2 non va assunto equivalente al DDL PostgreSQL; verificare identità, tipi, nullabilità e vincoli. Se esistessero dati in una sessione H2 ancora attiva da preservare, occorrerebbe prima concordare un export; l'audit non ne dimostra la necessità né l'assenza.

Il criterio «sopravvive al riavvio dell'applicazione» è insufficiente per Docker: serve un volume PostgreSQL persistente e una verifica anche dopo ricreazione del container con riuso del volume. H2 può restare per test rapidi, ma non certifica le migrazioni PostgreSQL.

`AgentInitializer.run()` usa `count()==0` e tre salvataggi separati: non garantisce recupero di un seed parziale né assenza di duplicati con avvii concorrenti. La classificazione «seed idempotente» è troppo forte. I tre agent sembrano dati dimostrativi, non un requisito di produzione accertato. Decidere se conservarli soltanto in dev/test o promuoverli a dati di riferimento. La proposta di migrare il seed e contemporaneamente spostare l'initializer lascia due responsabilità sovrapposte: scegliere un solo proprietario del seed e disattivare il vecchio percorso.

### R5 — Medio: cardinalità di dominio e necessità di riscrittura sono assunte

M-2 impone `Project 1—N Agent` e `Task N—1 Agent` senza requisiti che escludano agent condivisi, template o più agent nella stessa esecuzione. La direzione iniziale separa agent, modelli, provider e subagent; non giustifica un agent globale appartenente esclusivamente a un progetto.

**Correzione:** distinguere definizione riutilizzabile di agent, associazione/configurazione per progetto ed esecutore di un tentativo. Decidere le cardinalità in un task di dominio; non aggiungere ora join table speculative. L'assegnatario di un task e gli agent partecipanti a una run possono avere significati diversi. Prevedere transizioni e controllo degli aggiornamenti concorrenti quando si implementerà l'esecuzione.

La catena «Docker → persistenza → tutto il dominio → qualunque AI» è una sequenza di consegna consigliata, non una dipendenza tecnica assoluta. Docker facilita l'ambiente; prototipi AI e interfacce provider possono essere riutilizzabili prima di Project. Sostituire «ogni cosa andrà rifatta» con il rischio concreto di rework su schema, stato e integrazione. Confermata comunque la priorità della persistenza per questo prodotto.

## Correzioni fattuali e componenti riutilizzabili

1. **Jackson e immutabilità:** `Task` e `Agent` hanno campi non final e un costruttore pubblico senza argomenti. La risposta positiva al POST non dimostra che Jackson abbia usato il costruttore a quattro argomenti. Il meccanismo preciso richiederebbe una verifica dedicata; non è necessario per scegliere DTO espliciti. Rimuovere la causalità non dimostrata da CURRENT_ARCHITECTURE §3.2 e TD-06. L'assenza di setter non impone setter pubblici o builder: metodi di dominio controllati possono consentire modifiche. Oggi manca un percorso applicativo di update, non una possibilità assoluta di aggiornare l'entità.
2. **Input e ID:** `TaskController` passa l'entità ricevuta direttamente a `TaskService.save()`. Non viene filtrato esplicitamente l'ID. Un body con ID potrebbe alterare la semantica create/save a seconda della deserializzazione e persistenza: rischio da verificare con un test mirato in implementazione, non exploit accertato qui. Il DTO create deve escludere l'identità assegnata dal server.
3. **Errori:** assenza di handler personalizzati non prova che i client ricevano stacktrace. TD-07 deve distinguere contratto non controllato da divulgazione effettivamente osservata.
4. **Offline:** plugin mancanti dalla cache spiegano il fallimento offline; non dimostrano una build online non riproducibile. Conservare Maven wrapper e starter di test già presenti. La CI deve verificare una build pulita con JDK e dipendenze definiti; Docker non è da solo garanzia di riproducibilità.
5. **Riuso:** non emergono sottosistemi applicativi importanti trascurati. Sono sottovalutati il driver PostgreSQL, lo starter Validation e gli starter di test già nel POM: attivarli progressivamente. Repository, entry point e stratificazione restano riutilizzabili. Anche la rotta orchestrator può essere conservata come adattatore con contratto esplicito; sostituire la logica keyword non richiede cancellare integralmente il controller. Un contratto String è poco strutturato, non «non tipizzabile».
6. **Governance:** CONTEXT.yaml, handoff e policy sono requisiti ed esempi utili per futuri contratti di task/context/approval, non implementazioni già funzionanti di quei motori.
7. **Precisione delle conclusioni:** percentuali ~10%, ~8%, <2% non hanno criteri misurabili; trattarle come stime illustrative, non avanzamento misurato. Package per feature non dimostrano bounded context DDD validati. «Un solo REPLACE» va limitato alla logica applicativa, poiché REUSABLE_COMPONENTS classifica anche H2 e `ddl-auto` come REPLACE. I domini successivi non sono automaticamente «estensioni ordinarie»: esecuzione, memoria, integrazioni e pagamenti richiedono decisioni proprie.

## ADR-001: Spring Boot control plane + Python AI

**Parere favorevole condizionato.** Conservare Spring evita una sostituzione senza beneficio dimostrato e riusa JPA, servizi e struttura di test. Il costo irrecuperabile di circa 350 righe è però modesto: «distrugge l'unico asset» non basta a scartare FastAPI. L'ADR deve confrontare familiarità del manutentore, costo di due runtime, debug distribuito, deploy e necessità concreta dell'ecosistema Python. Non occorre oggi una riscrittura per risolvere questi dubbi.

Confine proposto per rendere la scelta verificabile:

| Responsabilità | Proprietario proposto |
|---|---|
| Progetti, definizioni agent/task, autorizzazioni, approvazioni, stato canonico delle run | Spring Boot |
| Chiamate provider, adattatori modelli, eventuale runtime LangGraph/embedding | Servizio Python |
| Catalogo configurazioni e policy di quota | Control plane; enforcement concordato con il runtime |
| Tabelle del dominio | Scritture attraverso Spring, nessuna scrittura Python indipendente sulle stesse tabelle |
| Stato/checkpoint interni AI eventualmente necessari | Storage/schema di proprietà del runtime, referenziato tramite ID |

Contratto HTTP versionato con DTO espliciti, ID di correlazione/esecuzione, timeout, errori e autenticazione tra servizi. Evitare routing, quota e retry implementati due volte: il componente che può consumare credenziali deve applicare i limiti autorizzati anche quando il chiamante si disconnette. Una run interrotta dopo l'accettazione del provider non è automaticamente sicura da ritentare.

TASK-001 non deve introdurre Python, LangGraph o un gateway proprietario. La scelta del runtime e dell'eventuale gateway esistente può essere validata in M-5 con un esperimento limitato e criteri misurabili, senza rendere obbligatorio LangGraph per la prima inferenza. ADR-001 resta una proposta da approvare, non una decisione adottata dalla review.

## TASK-001 corretto e sequenza proposta

**Titolo consigliato:** «Fondazione di persistenza riproducibile». **Scope:** M-1 e verifiche pertinenti, più la parte residua e approvata di M-0. Nessuna estensione opzionale a M-2; il titolo attuale promette dominio che lo scope non contiene. Backup/remote dipendono da una destinazione autorizzata e non devono essere confusi con la modifica del database.

Acceptance criteria proposti:

- Base Git dichiarata e lavoro preesistente preservato; nessun merge su master.
- PostgreSQL locale con volume e procedura di avvio documentata; configurazioni ambiente senza credenziali reali nel repository. Profili espliciti; passare da properties a YAML è facoltativo.
- Database vuoto creato esclusivamente da migrazioni versionate; Hibernate valida senza modificare lo schema. Secondo avvio senza drift; mismatch di schema segnalato.
- Comportamenti validi di lettura/creazione preservati; decisione sul seed esplicita, senza due inizializzatori o duplicati al riavvio.
- Test contro PostgreSQL reale per migrazioni e persistenza; dati conservati al riavvio applicativo e alla ricreazione del container con stesso volume. Non basta un test H2 o `contextLoads`.
- Build/test documentati e ripetibili; nessun Project, nuova relazione, provider, frontend o nuovo CRUD incluso implicitamente.

Sequenza rivista: **M-0 residuo → M-1 con test → contratti minimi e M-2 con test → completamento API/CI → sicurezza minima e contratto Run → M-5 → M-6**. La CI va anticipata appena disponibile una destinazione Git appropriata; i test locali non devono aspettarla. M-7 diventa evoluzione della sicurezza, non il suo primo ingresso.

## Modifiche precise richieste agli artefatti dell'audit

Questa review le elenca senza riscrivere gli artefatti di Claude:

| Artefatto / sezione | Correzione richiesta |
|---|---|
| PROJECT_STATE, HANDOFF, TECHNICAL_DEBT TD-20, MIGRATION_MAP M-0 | Aggiornare commit/ramo/index/remote secondo R1; distinguere snapshot precedente e stato attuale; eliminare «unica copia» non verificato |
| CURRENT_ARCHITECTURE §2–3; TD-06; REUSABLE_COMPONENTS entità | Qualificare immutabilità e creator Jackson; proporre mutazioni controllate senza obbligo di setter |
| TD-07, TD-14; GAP_ANALYSIS G-08 | Non dichiarare stacktrace esposti o irriproducibilità online senza evidenze |
| REUSABLE_COMPONENTS AgentInitializer; MIGRATION_MAP M-1 e §6 | Correggere idempotenza; distinguere seed demo/reference, un solo proprietario; migrazione iniziale PostgreSQL e test del volume |
| MIGRATION_MAP M-2/M-3/M-4 e §4 | Anticipare test e isolamento API; lasciare cardinalità alla decisione di dominio |
| MIGRATION_MAP M-5/M-6/M-7 e §5 | Rendere sicurezza minima e Run prerequisiti; esplicitare proprietà dei dati e confine Spring/Python |
| GAP_ANALYSIS §4; TD-01 e rischi finali | Sostituire dipendenze assolute e riscrittura inevitabile con rischi circoscritti |
| MIGRATION_MAP §6; PROJECT_STATE proposta TASK-001 | Rinominare e restringere a persistenza, senza M-2 opzionale; aggiungere acceptance criteria sopra |
| Sintesi di copertura e «un solo REPLACE» | Dichiarare stime non misurate e distinguere logica applicativa da configurazione/persistenza |

**Handoff:** review conclusa; TASK-001 non avviato. Richiesti allineamento documentale e approvazione umana dello scope corretto e dell'ADR prima delle rispettive implementazioni. Unico artefatto scritto da Codex: questo file.
