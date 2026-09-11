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
| `test` | test automatici | datasource fornito da Testcontainers; solo migrazioni di schema, nessun seed |
| `prod` | produzione | **solo** variabili d'ambiente (`POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`); nessun default e nessun seed |

Avvio con profilo esplicito:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

## 5. Schema del database

Lo schema è di proprietà di Flyway, non di Hibernate.

- Migrazioni di schema: `backend/src/main/resources/db/migration`
- Seed di sviluppo: `backend/src/main/resources/db/dev` (applicato solo dal profilo `dev`)

Hibernate gira in `validate`: se le entità e le migrazioni divergono, **l'avvio fallisce** con `Schema validation: missing column ...`. È il comportamento voluto — la correzione è una nuova migrazione, mai una modifica automatica dello schema.

Per ripartire da un database pulito:

```bash
docker compose down -v && docker compose up -d
```

⚠️ `-v` cancella il volume e tutti i dati locali.

## 6. Avvertenze

- **Un database seminato in `dev` non è promuovibile a produzione**: contiene la migrazione `V1000` che il profilo `prod` non risolve, e Flyway la segnalerebbe come applicata ma mancante.
- Non esiste ancora autenticazione: non esporre backend o database fuori da `localhost`.
- Il seed di sviluppo è **dato dimostrativo**, non dato di riferimento di produzione.
