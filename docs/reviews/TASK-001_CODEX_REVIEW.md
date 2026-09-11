# TASK-001 — Review differenziale Codex

Data: 2026-09-11. Branch: `task-001-persistence-foundation`, HEAD `b6e0b81`.
Perimetro Git: `2f01194..b6e0b81`, commit `5e164f5`, `c295189`, `b6e0b81`.

**Verdetto: PASS WITH FIXES. Il branch non è pronto per il merge su `master`.**

La fondazione è sostanzialmente corretta: PostgreSQL, schema iniziale Flyway, Hibernate `validate`, DTO e test di integrazione sono coerenti con TASK-001. Prima del merge occorre correggere la strategia di versionamento del seed, la garanzia dichiarata sull'isolamento dev/prod e la procedura di configurazione locale; aggiungere le relative regressioni e rieseguire la suite con Docker funzionante. Non è necessaria una riscrittura.

## Metodo e limiti della verifica

Letti PROJECT_STATE, protocollo, policy, TASK/CONTEXT/IMPLEMENTATION/ARTIFACT/HANDOFF della TASK-001 e ADR-001/ADR-002. Esaminati esclusivamente il diff indicato, i file modificati e le istruzioni RUNNING. Nessun nuovo audit o riesame di componenti applicativi estranei.

Verifiche eseguite:

| Verifica | Esito della review |
|---|---|
| Stato Git iniziale | Working tree pulito; HEAD e branch corrispondenti alla consegna |
| `master` / remote | `master` a `930f70f`; nessun remote configurato |
| `git diff --check 2f01194..b6e0b81` | Nessun errore |
| `docker compose --env-file .env.example config --format json` | Configurazione valida: `postgres:17-alpine`, bind `127.0.0.1:5432`, volume `aicompany_postgres_data` |
| Maven test | Dopo l'avvio manuale di Docker da parte dell'utente: **17 test, 0 failure, 0 errori, 0 skip; BUILD SUCCESS; exit 0**, circa 24 secondi |
| Disponibilità Docker | Primo tentativo bloccato da un errore interno Desktop; successivamente Docker Engine `29.6.2` disponibile. Nessun reset o intervento sui dati eseguito |
| Default della dipendenza Flyway effettiva, `12.4.0` | Sonda Java senza DB: `outOfOrder=false`, `ignoreMigrationPatterns=[*:future]` |
| Hibernate effettivo, `7.4.1.Final` | Ispezione mirata del bytecode del validatore per delimitare le garanzie sullo schema |
| Upgrade da V1 + seed V1000 a V2, PostgreSQL isolato | **Fallisce**: `Detected resolved migration not applied to database: 2` |
| Location prod su database con seed dev | Flyway validate **true**, migrate **0**; avvio effettivo del contesto Spring con profilo **prod riuscito** |
| Drift NOT NULL, PostgreSQL isolato | Rimosso NOT NULL da `tasks.title`: Hibernate **avvia** il contesto |
| Colonna mancante, PostgreSQL isolato | Rimossa `tasks.priority`: contesto **fallito** con SchemaManagementException; colonna ancora assente dopo il tentativo |
| Startup Maven con datasource deliberatamente irraggiungibile | Con devtools restart: fallimento su `restartedMain`, exit Maven **0**; restart disabilitato nella JVM: fallimento su `main`, exit **1** |

Il wrapper Windows ha richiesto l'aggiunta di Windows PowerShell al PATH del solo processo di verifica: limite dell'ambiente dell'agente, non modifica del progetto. Le sonde e i log sono temporanei in `backend/target/`, ignorato da Git. I probe PostgreSQL usano un container Testcontainers proprio, eliminato al termine, e gli script originali del progetto; V2 è soltanto una fixture temporanea, non una migrazione aggiunta al prodotto. Per la sonda Spring prod le credenziali del container vengono fornite come override runtime, senza cambiare il file di profilo.

**I 17 test verdi sono stati riconfermati.** Le verifiche manuali di riavvio/volume riportate da Claude non sono state ripetute. Il primo fallimento ambientale è superato e non è conteggiato come difetto del codice. Non sono stati modificati sorgenti/configurazioni applicative né database o volumi del progetto. Nessun merge, push o modifica dei remote; TASK-002 non avviata.

## Rilievi

### R1 — HIGH — Il seed V1000 impedisce la normale evoluzione V2, V3… del database dev

**Dove:** `backend/src/main/resources/db/dev/V1000__dev_seed_agents.sql`; `application-dev.properties:9`; `DevSeedMigrationTest`.

Flyway tratta schema e seed come un unico insieme versionato. Dopo V1 + V1000, il database dev è alla versione 1000. Una successiva migrazione V2 è inferiore alla versione applicata: con `outOfOrder=false` la validazione **blocca `migrate`**, come verificato nella sonda. Un database nuovo e uno dev persistente possono quindi seguire percorsi diversi. È un difetto della fondazione di upgrade, anche se la creazione iniziale e il secondo avvio senza nuove migrazioni funzionano.

**Evidenza runtime:** applicati gli script originali V1/V1000 a PostgreSQL effimero; aggiunta una fixture `V2__review_probe.sql` in una location temporanea; `migrate()` fallisce con `Detected resolved migration not applied to database: 2`. La [documentazione Redgate su outOfOrder](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-out-of-order-setting) conferma il default e il trattamento delle versioni intermedie.

**Fix richiesto prima del merge:** scegliere una strategia seed che non porti lo stream delle migrazioni di schema alla versione 1000. Possibili soluzioni sono una numerazione iniziale coerente prima della prossima migrazione oppure un meccanismo dev idempotente separato, mantenendo un solo proprietario del seed. Non abilitare globalmente `outOfOrder` solo per aggirare il problema. Per database già contenenti V1000 serve una procedura esplicita di transizione: rinominare il file da solo non aggiorna la history esistente; non prescrivere cancellazioni indiscriminate del volume.

**Regressione necessaria:** database con schema e seed della versione precedente → nuova migrazione di schema → dati preesistenti conservati e nessun duplicato. Il test corrente ripete soltanto `migrate()` con gli stessi file: non intercetta questo problema.

### R2 — MEDIUM — Il presunto rifiuto del database dev da parte del profilo prod non è garantito

**Dove:** `V1000__dev_seed_agents.sql:11`, `application-prod.properties:9`, ADR-002 «Conseguenze», RUNNING §6, HANDOFF punto 4.

La location prod esclude correttamente i file seed, ma **escludere il file non rimuove i dati già presenti e non garantisce un errore di startup**. Con le sole versioni attuali, V1000 è superiore all'ultima migrazione risolta in prod, V1, ed è trattata come migrazione futura. La configurazione effettiva ignora `*:future`. Nella sonda il contesto **prod si è avviato sul database seminato dev**. È quindi errata l'affermazione categorica che Flyway bloccherebbe il passaggio da dev a prod.

**Evidenza:** Flyway 12.4.0 restituisce `validationSuccessful=true` e zero nuove migrazioni usando la sola location schema dopo il seed; il successivo avvio Spring con profilo prod riesce. Risultato coerente con le [regole ufficiali di ignoreMigrationPatterns](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-ignore-migration-patterns-setting).

**Fix richiesto:** correggere ADR, commento SQL e istruzioni distinguendo la policy «non promuovere database dev» dalla protezione effettivamente implementata. Se il rifiuto deve essere una garanzia, implementare una verifica esplicita o una policy Flyway rigorosa compatibile con la strategia scelta in R1, e provarla. Non basta rinominare il profilo. Verificare inoltre prod su database vuoto e test senza alcun seed.

La normale separazione delle location dev/test/prod è corretta; il difetto riguarda la garanzia su database già seminati. Usare un solo profilo operativo per avvio: combinazioni come `dev,prod` non sono un confine di sicurezza.

### R3 — MEDIUM — La personalizzazione tramite .env configura Compose ma non il backend Maven

**Dove:** `.env.example:1`, RUNNING §1–2, `application-dev.properties:3`.

Le istruzioni invitano a copiare/modificare `.env`, avviare Compose e poi `mvnw spring-boot:run`. Compose importa quel file; il backend usa variabili dell'ambiente del processo e non ha un import di `.env`. Modificando soltanto password, porta o nome DB nel file, il database e il backend possono usare valori diversi. Con i default uguali il problema rimane nascosto.

**Fix richiesto:** documentare un avvio in cui gli stessi valori vengano forniti anche al processo Java, oppure introdurre un caricamento dev esplicito con percorso non ambiguo. Aggiungere una verifica con valori diversi dai default. Non esportare la password nei log o nella command line condivisa. Spiegare anche che cambiare le variabili di inizializzazione PostgreSQL non modifica automaticamente utenti/password di un volume già inizializzato.

### R4 — MEDIUM — Le garanzie attribuite a Hibernate validate e ai test sono troppo estese

**Dove:** IMPLEMENTATION §1.1/P4, ADR-002 punti 3 e motivazione; `SchemaMigrationTest:31`.

La configurazione comune contiene davvero `ddl-auto=validate`; nei profili modificati non esiste un override a `update`, `create` o `create-drop`. H2 e la relativa configurazione sono rimossi. La creazione di V1 è una migrazione iniziale autentica, non una baseline vuota.

Tuttavia `validate` non è un confronto completo di ogni proprietà dello schema. Nel validatore Hibernate 7.4.1 verificato, il percorso delle colonne controlla presenza e compatibilità dei tipi, non la nullabilità dichiarata da `@Column(nullable=false)`. La sonda runtime lo conferma: dopo `ALTER TABLE drift.tasks ALTER COLUMN title DROP NOT NULL`, il contesto si avvia. Dopo la rimozione di `priority` fallisce invece con `missing column [priority]`; la colonna resta assente, quindi Hibernate non la ricrea. Non promettere che qualunque deriva dei NOT NULL venga rilevata all'avvio. I test SQL sui vincoli sono utili proprio perché aggiungono una verifica diversa.

Inoltre contare le tre tabelle attese **non dimostra da solo** che Hibernate non abbia modificato colonne di tabelle esistenti. Un cambio accidentale della proprietà a `update` potrebbe non far fallire quel test su uno schema inizialmente corretto.

**Fix richiesto:** restringere le affermazioni documentali e aggiungere una regressione mirata: su PostgreSQL isolato, colonna richiesta assente → inizializzazione Spring fallita → colonna ancora assente dopo il tentativo. Assert esplicito della modalità `validate`. Non occorre testare ogni comportamento interno di Hibernate.

### R5 — MEDIUM — La protezione loopback riguarda il DB, non il backend

**Dove:** `application-dev.properties`; RUNNING §6; commenti Compose.

Il mapping PostgreSQL è correttamente limitato a `127.0.0.1`. Nei profili modificati manca invece `server.address`: le istruzioni «non esporre backend fuori da localhost» non impongono il bind del server HTTP. L'effettiva raggiungibilità da rete dipende anche da firewall e ambiente; non è stata sondata qui. La mancanza di autenticazione è preesistente, ma ora gli endpoint scrivono dati durevoli.

**Correzione consigliata nella configurazione locale:** bind esplicito del backend dev a loopback. Non richiede introdurre autenticazione completa o ampliare il dominio. Non considerare la porta DB su loopback una protezione degli endpoint HTTP.

### R6 — LOW — Immagine e nomi Compose non garantiscono build identiche o istanze parallele

**Dove:** `docker-compose.yml:8–9,30`, `PostgresTestcontainerConfig`.

`postgres:17-alpine` fissa la major, ma è un tag mobile: due pull in momenti diversi possono usare immagini diverse. Dev e test dichiarano lo stesso tag, non necessariamente lo stesso digest. Per riproducibilità stretta usare una versione/digest concordata e aggiornamenti espliciti; non è un errore della scelta PostgreSQL 17.

`container_name`, nome progetto, porta e nome volume fissi fanno collidere due checkout che avviano Compose e possono far condividere dati senza volerlo. È accettabile per un'unica istanza locale, da documentare. La futura CI deve usare i container isolati di Testcontainers, o nomi/volumi Compose per job: non riusare `aicompany_postgres_data` per test concorrenti.

### R7 — LOW — Esclusione dei file segreti migliorabile; nessun segreto reale individuato nel diff

**Dove:** `.gitignore:2–4`, `.env.example`, profili datasource.

I valori versionati sono chiaramente default dimostrativi di sviluppo, non evidenza di credenziali reali. Prod non contiene fallback di connessione. `.env` è ignorato, ma nomi comuni come `.env.prod` e `.env.test` non rientrano nelle regole aggiunte. Valutare `.env.*` con eccezione esplicita per `.env.example`; aggiungere un controllo segreti in CI. La review è limitata ai commit richiesti, non certifica l'intera storia Git.

Il ruolo creato dall'immagine PostgreSQL locale ha privilegi elevati: non assumere che Compose costituisca una configurazione di produzione. Separazione dei ruoli migration/runtime e gestione dei segreti sono requisiti del futuro deploy.

## Valutazione dei due cambi di contratto

| Cambiamento | Decisione | Motivazione |
|---|---|---|
| `POST /api/tasks`: `200` → `201 Created` | **KEEP** | La risorsa è creata e viene restituita nel body; 201 esprime correttamente il risultato. Nessun motivo per reintrodurre 200. È comunque un cambio osservabile, da dichiarare ai consumatori; assenza di frontend non dimostra assenza assoluta di client/script. |
| `status` e `priority` obbligatori | **KEEP** | `@NotBlank` e limiti di lunghezza sono coerenti con i NOT NULL del nuovo schema e consentono record completi senza introdurre enum o valori di default arbitrari. È un contratto provvisorio ragionevole nella fondazione di persistenza. |

La seconda scelta **non è retrocompatibile con tutti i body prima accettati**: ad esempio un titolo senza status/priority poteva essere persistito prima e ora viene rifiutato. Correggere IMPLEMENTATION §2.4 («compatibile con ogni payload valido precedente») e qualificare AC-5 come preservazione dei payload completi del contratto aggiornato. L'argomento «richiederli evita una decisione di dominio» è incompleto: rendere obbligatori i campi è a sua volta una decisione di contratto, qui valutata favorevolmente. Non sono necessari default impliciti né rollback della validazione.

**LOW, header Location:** `TaskController:43` emette `/api/tasks/{id}`, ma la task non introduce un GET individuale. Non aggiungere adesso un endpoint fuori scope. Documentare il limite, oppure omettere l'header finché la risorsa non è consultabile a quell'URI; conservare 201 e body. Il test controlla solo l'esistenza dell'header, non destinazione o corrispondenza con l'ID.

## Devtools: esito e priorità

**MEDIUM, technical debt per l'automazione di startup; non richiede rimozione immediata della dipendenza.**

Riproduzione non distruttiva: datasource forzato a `jdbc:postgresql://127.0.0.1:1/review?connectTimeout=2`, senza contattare il DB del progetto.

| Lancio | Fallimento | Exit Maven |
|---|---|---|
| `mvnw.cmd -B spring-boot:run` con override datasource | `Application run failed`, `restartedMain`, BUILD SUCCESS | **0** |
| Stesso lancio, più `-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false` | `Application run failed`, `main`, BUILD FAILURE | **1** |

La causa del fallimento riprodotto è la connessione, non un drift di schema; conferma comunque il problema di propagazione dell'errore segnalato da Claude. Non significa che Hibernate abbia accettato lo schema errato o che l'applicazione sia partita. La suite `mvn test` eseguita qui ha restituito BUILD FAILURE davanti agli errori del contesto: il falso positivo di `spring-boot:run` non si estende automaticamente a JUnit/Surefire.

**Azione immediata documentale:** aggiungere a RUNNING il comando affidabile per gli smoke test e vietare di interpretare BUILD SUCCESS di `spring-boot:run` come readiness. **Prima della futura CI/CD:** usare test di contesto oppure artefatto confezionato senza devtools, verificare exit code e readiness con timeout e controllare un caso negativo. Se si automatizza già ora lo startup, il workaround deve essere applicato ora; se non esiste quell'automazione, la rimozione/ristrutturazione della dipendenza può restare debito. Il semplice flag nel file properties non equivale a disabilitare completamente il restart nella JVM, come chiarisce la [documentazione Spring Boot](https://docs.spring.io/spring-boot/reference/using/devtools.html).

## Test: valore reale e lacune

La struttura usa PostgreSQL reale tramite `@ServiceConnection`, senza dipendenza da porte/credenziali del Compose locale. Il contesto dev è distinto dal contesto test. Non ci sono fallback H2 o test saltati deliberatamente quando Docker manca.

| Gruppo | Valore effettivo | Limite rilevante |
|---|---|---|
| SchemaMigrationTest, 4 test | History V1, tabelle attese, alcuni NOT NULL e lunghezza description | Non prova upgrade, rifiuto del drift o assenza del seed: `contains("1")` ammette anche V1000 |
| TaskPersistenceTest, 4 test | Round-trip, ID distinti, violazioni DB tramite SQL diretto | Non simula restart/volume; non copre NOT NULL priority |
| TaskApiValidationTest, 6 test | Rifiuto input con assenza di scritture, creazione/rilettura, ID client ignorato | Mancano priority assente, limiti description/status/priority, risposta Agent; Location solo presente |
| DevSeedMigrationTest, 2 test | Tre nomi attesi e seconda esecuzione senza duplicati | Non prova esclusione in prod/test né upgrade dopo V1000 |
| ContextLoads, 1 test | Integrazione iniziale Spring/JPA/Flyway | È un caso positivo, non una prova di fallimento controllato |

La verifica manuale di ricreazione del container è accettabile per AC-9 in questa task, se l'evidenza di Claude viene conservata; non serve imporre subito un test automatico distruttivo sul volume locale. Testcontainers può anche essere configurato con storage persistente, quindi «usa container effimeri» descrive la suite attuale, non un'impossibilità tecnica generale.

Prima del merge dare priorità ai test di R1/R2/R4 e a priority obbligatoria, non a un aumento indiscriminato del numero di test. La suite condivide dati tra alcune classi e TaskApiValidationTest usa `deleteAll`: l'esecuzione attuale sequenziale è ragionevole; non abilitare parallelismo JUnit senza isolamento dei dati. Il fallimento per Docker assente deve restare un fallimento, non essere trasformato in skip in CI.

## Schema, profili e ADR: giudizio complessivo

- **Schema:** BIGINT identity/Long, VARCHAR 255, description 5000 opzionale e BOOLEAN sono coerenti con i mapping modificati. NOT NULL di title/status/priority allineati al DTO. Nessuna relazione o enum introdotti impropriamente. I NOT NULL non impediscono stringhe vuote da SQL diretto: la garanzia `NotBlank` è al confine HTTP; non dichiarare integrità semantica completa del dominio.
- **Flyway/Hibernate:** corretta ownership DDL, `open-in-view=false`, nessun `ddl-auto=update` residuo nelle configurazioni esaminate. R1 riguarda gli upgrade, non la correttezza SQL di V1. R4 delimita le garanzie di validate.
- **Seed:** un solo proprietario effettivo dopo la rimozione di AgentInitializer; la history rende ripetibile l'avvio senza duplicazioni. Non ripristina agent cancellati manualmente: comportamento accettabile per dati demo.
- **Profili:** prod usa variabili senza default e non include il seed; test usa datasource gestito dal container; dev è il default globale. Un deploy deve selezionare esplicitamente prod: omettere il profilo non deve essere una procedura di produzione. Restano R2/R3 e il bind locale R5.
- **ADR-001:** coerente con il perimetro approvato; Python resta rinviato. Nessun nuovo riesame della decisione di stack.
- **ADR-002:** correggere promessa di rifiuto dev→prod, equivalenza assoluta degli ambienti e portata di validate. L'alternativa «seed applicativo dev» non comporta inevitabilmente due proprietari: ne avrebbe uno se il seed SQL fosse rimosso. La scelta attuale va motivata con costi e proprietà reali, non con quella falsa necessità.
- **Git/CI:** i tre commit rispettano la separazione persistenza/test/documentazione. Il ramo contiene anche la base TASK-000; un futuro merge su master includerà tale storia, non soltanto TASK-001. Nessun requisito tecnico impone un remote prima di un merge locale; destinazione remota e CI restano decisioni separate e non eseguite in questa review.

## Condizioni di chiusura

Nessun **BLOCKER** che imponga abbandono della soluzione. **HIGH R1** e **MEDIUM R2/R3** richiedono fix mirati prima del merge. Integrare anche la regressione/documentazione di R4, chiarire i breaking change e documentare il lancio senza restart; applicare il bind dev di R5 prima di considerare l'avvio documentato confinato a localhost. LOW e miglioramenti più ampi della CI possono essere tracciati separatamente.

Dopo i fix, eseguire la suite e i nuovi casi su PostgreSQL reale con Docker funzionante, senza distruggere dati locali. Una review dei soli fix è sufficiente: non serve ripetere l'audit del repository.

**Verdetto finale: PASS WITH FIXES. Branch NON pronto per il merge su `master` nello stato `b6e0b81`.**
