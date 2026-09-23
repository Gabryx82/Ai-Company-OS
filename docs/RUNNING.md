# Running AI Company OS locally

Stato attuale: control plane Spring Boot + PostgreSQL. Frontend e servizio AI non esistono ancora.

## Prerequisiti
- JDK 21
- Docker Desktop in esecuzione (il daemon deve essere attivo, non solo installato)
- Nessuna installazione di Maven: si usa il wrapper `./mvnw`

## 1. Avviare il database

Dalla radice del repository:

```bash
docker compose up -d
```

Questo avvia PostgreSQL 17 sulla porta `5432`, **vincolata a `127.0.0.1`**: il backend non ha ancora autenticazione, quindi il database non deve essere raggiungibile dalla rete.

I dati vivono nel volume Docker `aicompany_postgres_data` e sopravvivono alla ricreazione del container.

Configurazione opzionale: copiare `.env.example` in `.env` e modificarlo. Senza `.env` valgono i default locali del `docker-compose.yml`. **Il file `.env` non va committato.**

Verifica dello stato:

```bash
docker compose ps
```

## 2. Avviare il backend

```bash
cd backend
./mvnw spring-boot:run
```

⚠️ **`BUILD SUCCESS` di `spring-boot:run` non significa che l'applicazione sia partita.** Con
`spring-boot-devtools` sul classpath un fallimento di avvio avviene sul thread
`restartedMain` e Maven esce comunque con codice `0`. Per uno smoke test affidabile,
disattivare il restart nella JVM — il flag nel file properties non basta:

```bash
cd backend
./mvnw -B spring-boot:run "-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false"
```

Così un errore di avvio produce `BUILD FAILURE` ed exit code `1`. Non usare l'exit code di
`spring-boot:run` come prova di readiness in nessuno script.

Il profilo di default è `dev`. All'avvio Flyway applica le migrazioni e, solo in `dev`, il seed dimostrativo di tre agent.

L'applicazione risponde su `http://localhost:8080`.

### Prima di tutto: ogni mutazione richiede `If-Match`

**Se una richiesta di scrittura su una risorsa esistente risponde `428`, non è un errore del
server: manca l'header.** È il protocollo P0–P4 di ADR-009, e vale per **tutte e tre** le
risorse.

Il ciclo è sempre lo stesso: si legge la risorsa singola, si prende l'`ETag` dalla risposta, lo si
rimanda in `If-Match`.

```bash
# 1. leggere, e tenere l'ETag
curl -i http://localhost:8080/api/tasks/1
# ... HTTP/1.1 200
# ... ETag: "0"

# 2. scrivere, citandolo
curl -i -X PUT http://localhost:8080/api/tasks/1/project \
  -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{"projectId":1}'
```

| Risposta | Significato |
|---|---|
| `428` | `If-Match` assente. Leggere la risorsa e riprovare |
| `412` | L'`ETag` inviato è **stantio**: qualcun altro ha scritto nel frattempo. Rileggere |
| `400` | L'`If-Match` è illeggibile, oppure è `*` |

I **listati non portano `ETag`** (è un limite dichiarato, TD-33): per scrivere si legge sempre la
risorsa singola. `POST` e le mutazioni riuscite restituiscono l'`ETag` nuovo, quindi una sequenza
di scritture non ha bisogno di rileggere ogni volta.

### Task

| Endpoint | Descrizione |
|---|---|
| `GET /api/tasks` | elenco. **Senza `ETag`** |
| `GET /api/tasks/{id}` | una task, **con `ETag`** — il percorso canonico per ottenerlo |
| `POST /api/tasks` | creazione (`201` + `Location` + `ETag`) |
| `PUT /api/tasks/{id}/project` | assegna o sposta di progetto. **`If-Match`** |
| `PUT /api/tasks/{id}/agent` | assegna o cambia agente. **`If-Match`** |

Non esiste `DELETE /api/tasks/{id}` (risponde `405`, «non per questa via»), né un `PUT` generale
sulla task: le due associazioni sono sotto-risorse.

```bash
curl -i -X POST http://localhost:8080/api/tasks -H 'Content-Type: application/json' \
  -d '{"title":"Primo task","description":"descrizione","status":"OPEN","priority":"HIGH"}'
```

`status` ha un **vocabolario chiuso**: `OPEN`, `IN_PROGRESS`, `DONE`, confrontati
**esattamente** — `"open"` è rifiutato quanto `"banana"`, con `400` e il campo nominato in
`errors.status` (ADR-011). `priority` è ancora una stringa libera.

**Non esiste un modo di cambiare lo `status` di una task esistente**: si sceglie alla creazione.
È un limite dichiarato, TD-37.

`projectId` e `agentId` sono facoltativi nel corpo della `POST`: se presenti e rifiutati, **la
task non viene creata affatto**.

### Progetti

| Endpoint | Descrizione |
|---|---|
| `GET /api/projects` | elenco. Filtro facoltativo `?status=ACTIVE\|ARCHIVED`; **senza filtro include gli archiviati** |
| `GET /api/projects/{id}` | un progetto, con `ETag` |
| `POST /api/projects` | creazione (`201` + `Location` + `ETag`) |
| `PUT /api/projects/{id}` | aggiorna i campi descrittivi. **`If-Match`** |
| `POST /api/projects/{id}/archive` | archivia. **`If-Match`** |
| `POST /api/projects/{id}/restore` | ripristina. **`If-Match`** |
| `GET /api/projects/{projectId}/tasks` | le task del progetto. Un progetto inesistente è `404`, non una lista vuota |

**Non si cancella, si archivia** (ADR-004): non esiste `DELETE`. Un progetto `ARCHIVED` non
riceve task nuove e le task che contiene **non si spostano** — `409` in entrambi i casi. Le
**letture** restano aperte: archiviare non rende illeggibile la storia.

### Agent

| Endpoint | Descrizione |
|---|---|
| `GET /api/agents` | elenco. Filtro facoltativo `?active=true\|false` |
| `GET /api/agents/{id}` | un agent, con `ETag` |
| `POST /api/agents` | creazione (`201` + `Location` + `ETag`) |
| `PUT /api/agents/{id}` | aggiorna. **`If-Match`** |
| `POST /api/agents/{id}/deactivate` | disattiva. **`If-Match`** |
| `POST /api/agents/{id}/activate` | riattiva. **`If-Match`** |
| `GET /api/agents/{agentId}/tasks` | le task dell'agent |

Il nome è **unico senza distinzione di maiuscole**, imposto dal database. Un agent disattivato
non riceve lavoro nuovo (`409`), ma **le task che già tiene restano pienamente riassegnabili** —
è deliberato (ADR-010 D3): è proprio il momento in cui bisogna poterle dare a qualcun altro.

La risposta porta **due** campi di ciclo di vita, `active` (booleano) e `status`
(`ACTIVE`/`INACTIVE`), e continua a portarli entrambi. Da `V8` quello memorizzato è `status` e
`active` è derivato — prima era l'inverso — ma **il JSON è identico** e il filtro resta
`?active=true|false`. ADR-012 §4.

### Orchestrator

| Endpoint | Descrizione |
|---|---|
| `POST /api/orchestrator` | ⚠️ **placeholder.** Quattro `if` su `contains()` che restituiscono nomi di agent **che non esistono nel database**. Non tocca né task né agent. Da sostituire, non da usare (TD-08) |

### Errori

Ogni risposta d'errore è un `ProblemDetail` (RFC 9457) con un `type` **stabile**
`urn:ai-company-os:problem:<slug>` — ADR-007. **Il `type` è il contratto; il `title` è prosa** e
può essere riscritto: un client deve ramificare sul primo.

```json
{
  "type": "urn:ai-company-os:problem:validation-failed",
  "title": "Invalid request payload",
  "status": 400,
  "detail": "The request body failed validation",
  "errors": { "status": "status must be one of OPEN, IN_PROGRESS, DONE" }
}
```

Un body invalido (per esempio `{}`) è rifiutato con `400` e **nulla viene scritto** sul database.

## 3. Eseguire i test

```bash
cd backend
./mvnw test
```

I test girano contro un **PostgreSQL reale** avviato da Testcontainers, non su database embedded. **Docker deve essere in esecuzione**, altrimenti i test falliscono all'avvio del container.

### La stessa suite in CI

`.github/workflows/ci.yml` esegue **esattamente questo comando** — `./mvnw -B clean test` in
`backend/` — su `ubuntu-latest` con JDK 21 (Temurin), a ogni push e a ogni pull request, e a
richiesta con *Run workflow*.

**Non c'è un blocco `services: postgres`, ed è deliberato.** È la cosa ovvia da aggiungere a un
workflow per un progetto i cui test usano PostgreSQL, e qui sarebbe sbagliata: nessuno si
collegherebbe a quel servizio. La suite non prende il database dall'ambiente, se lo **avvia da
sé** con Testcontainers. Quello che serve davvero è un **daemon Docker**, che i runner
`ubuntu-latest` hanno, e un passo del workflow lo verifica esplicitamente perché la sua assenza
altrimenti fallirebbe dentro Testcontainers con un messaggio che non nomina la causa.

I report di Surefire sono caricati come artefatto **anche quando il job fallisce** — soprattutto
allora, perché sono l'unico modo di vedere quale test è rosso senza rieseguire il job.

> ✅ **Attiva dal 2026-09-23.** Prima run verde: `35863517006` — 9 step su 9 `success` su
> `ubuntu-latest` in 1m26s, `./mvnw -B clean test` compreso. **TD-14 chiuso**:
> `tasks/TASK-012/EVIDENCE_TD14.md` §11.

## 4. Profili

| Profilo | Uso | Sorgente della configurazione |
|---|---|---|
| `dev` (default) | sviluppo locale | `application-dev.properties`, default sovrascrivibili da variabili d'ambiente |
| `test` | test automatici | datasource fornito da Testcontainers; solo lo stream di schema, nessun seed |
| `prod` | produzione | **solo** variabili d'ambiente (`POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`); nessun default, solo lo stream di schema |

Avvio con profilo esplicito:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

## 5. Schema del database

Lo schema è di proprietà di Flyway, non di Hibernate. Schema e seed sono **due stream
Flyway indipendenti**, con tabelle di storia distinte (ADR-003):

| Stream | Cartella | Tabella di storia | Applicato da |
|---|---|---|---|
| Schema | `backend/src/main/resources/db/migration` | `flyway_schema_history` | tutti i profili |
| Seed di sviluppo | `backend/src/main/resources/db/dev` | `flyway_dev_seed_history` | solo il profilo `dev` |

Perché separati: finché il seed viveva nello stream dello schema come `V1000`, il database
dev era alla versione 1000 e una successiva `V2` non era più applicabile
(`Detected resolved migration not applied to database: 2`). Con due stream lo schema resta
lineare — `V1`, `V2`, `V3`, … — sia che il database sia stato seminato sia che non lo sia.

### Due numeri diversi: lo stream e il tuo database

Non vanno confusi, ed è un equivoco che è già costato una diagnosi sbagliata:

| | Che cos'è | Come si legge |
|---|---|---|
| **Migration stream** | La migrazione più alta che **esiste nel repository**. Proprietà del codice | `ls backend/src/main/resources/db/migration` |
| **Versione del tuo database** | Ciò che è stato realmente **applicato** a quell'installazione. Proprietà del volume | `docker exec aicompany-postgres psql -U aicompany -d aicompany -c "SELECT version, script FROM flyway_schema_history WHERE success ORDER BY installed_rank;"` |

Al 2026-09-19: lo **stream è a `V8`**; il **database di sviluppo locale è a `V3`** e non ha mai
visto `V4`…`V8`. Non è un difetto — quel database non viene avviato da un po'. Al primo avvio in
profilo `dev` le cinque migrazioni si applicheranno in ordine. **Verificato su un clone di quel
database**: `V7` passa perché l'unica riga di `tasks` ha `status = 'OPEN'`, e `V8` converte i tre
agenti da `active = TRUE` a `status = 'ACTIVE'` senza perdere righe. **Non serve
`docker compose down -v`.**

Quando un documento dice «schema a `V8`» **senza qualificatore, intende lo stream**, mai un
database.

**La prossima migrazione di schema prende il numero successivo alla testa dello stream, e va in
`db/migration`.** Oggi la testa è **`V8`**, quindi la prossima è `V9__....sql`. I numeri del seed
sono indipendenti e non vanno considerati.

Per leggere la testa invece di fidarsi di questo paragrafo:

```bash
ls backend/src/main/resources/db/migration
```

> Questa riga diceva «la prossima si chiama `V2__...sql`» fino a TASK-011, quando lo stream era
> già a `V7`. Non era documentazione incompleta: **istruiva a sbagliare**, perché una `V2` nuova
> viene rifiutata da Flyway. Da qui il comando qui sopra.

Le migrazioni applicate finora:

| Versione | Che cosa introduce |
|---|---|
| `V1` | `agents`, `tasks` |
| `V2` | `projects`, con `projects_status_check` e unicità del nome |
| `V3` | `tasks.project_id` + FK + indice |
| `V4` | `agents.created_at`/`updated_at`, unicità del nome case-insensitive |
| `V5` | `version` su tutte e tre le tabelle (ADR-009) |
| `V6` | `tasks.agent_id` + FK + indice |
| `V7` | `tasks_status_check`: vocabolario chiuso di `Task.status` (ADR-011) |
| `V8` | `agents.status` + `agents_status_check`, e **`agents.active` eliminata** (ADR-012). **La prima migrazione distruttiva dello stream**: un database che la esegue non torna a `V7` eseguendo SQL al contrario |

Hibernate gira in `validate`: se le entità e le migrazioni divergono, **l'avvio fallisce** con `Schema validation: missing column ...`. È il comportamento voluto — la correzione è una nuova migrazione, mai una modifica automatica dello schema.

Limite da conoscere: `validate` verifica presenza e compatibilità di tipo delle colonne
mappate, **non** ogni proprietà dello schema. La rimozione manuale di un `NOT NULL` non fa
fallire l'avvio; una colonna mancante sì. I vincoli sono coperti dai test SQL.

### Database dev creato prima di TASK-001A

Un database dev che contiene ancora `V1000__dev_seed_agents.sql` in `flyway_schema_history`
si aggiorna **da solo al primo avvio in profilo `dev`**: la riga legacy viene rimossa e il
seed viene registrato nel proprio stream. Nel log compare

```
Removed the legacy V1000__dev_seed_agents.sql entry from flyway_schema_history
```

Nessun dato viene cancellato e **non serve `docker compose down -v`**. Il seed non viene
duplicato: lo script ha un `WHERE NOT EXISTS` sul nome dell'agent. Verifica manuale:

```bash
docker exec aicompany-postgres psql -U aicompany -d aicompany -c "SELECT version, script FROM flyway_schema_history ORDER BY installed_rank;"
```

Dopo la transizione **non deve contenere alcuna riga `1000`**: solo le versioni di schema
applicate, in ordine, a partire da `1 | V1__create_agents_and_tasks.sql`.

> Fino a TASK-011 questa riga diceva «deve contenere **solo** `1 | V1__...`», vero appena dopo
> TASK-001A e falso da `V2` in poi. Ciò che la verifica deve cercare è l'**assenza** della riga
> legacy, non la presenza di una sola riga.

Per ripartire da un database pulito:

```bash
docker compose down -v && docker compose up -d
```

⚠️ `-v` cancella il volume e tutti i dati locali.

## 6. Avvertenze

- **Non promuovere un database di sviluppo a produzione.** È una **policy operativa, non un controllo tecnico**: il profilo `prod` legge solo `flyway_schema_history` e ignora del tutto lo stream del seed, quindi si avvia senza errori sia su un database mai seminato sia su uno seminato in dev. Nulla impedisce a `prod` di puntare a un database di sviluppo — la separazione evita che i due stream interferiscano, non protegge da una configurazione sbagliata.
- **Un solo profilo operativo per avvio.** Combinazioni come `dev,prod` non sono un confine di sicurezza: attivano il bean del seed.
- Non esiste ancora autenticazione: non esporre backend o database fuori da `localhost`.
- Il seed di sviluppo è **dato dimostrativo**, non dato di riferimento di produzione.
