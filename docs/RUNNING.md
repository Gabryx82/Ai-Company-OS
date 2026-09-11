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

| Endpoint | Descrizione |
|---|---|
| `GET /api/agents` | elenco agent |
| `GET /api/tasks` | elenco task |
| `POST /api/tasks` | creazione task (`201` + header `Location`) |

Esempio di creazione valida:

```bash
curl -X POST http://localhost:8080/api/tasks -H "Content-Type: application/json" -d "{\"title\":\"Primo task\",\"description\":\"descrizione\",\"status\":\"OPEN\",\"priority\":\"HIGH\"}"
```

Un body invalido (per esempio `{}`) viene rifiutato con `400` e nulla viene scritto sul database.

## 3. Eseguire i test

```bash
cd backend
./mvnw test
```

I test girano contro un **PostgreSQL reale** avviato da Testcontainers, non su database embedded. **Docker deve essere in esecuzione**, altrimenti i test falliscono all'avvio del container.

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

**La prossima migrazione di schema si chiama `V2__...sql` e va in `db/migration`.** I numeri
del seed sono indipendenti e non vanno considerati.

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

Dopo la transizione deve contenere **solo** `1 | V1__create_agents_and_tasks.sql`.

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
