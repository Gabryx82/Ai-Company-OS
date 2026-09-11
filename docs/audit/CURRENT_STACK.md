# CURRENT_STACK — Stack reale del repository (TASK-000)

Fonte: ispezione diretta del repository + build/test eseguiti il 2026-09-11.

## 1. Sintesi

| Livello | Dichiarato in `README.md` | Realmente presente | Delta |
|---|---|---|---|
| Frontend | React + TypeScript + Vercel | **assente** | 100% da costruire |
| Backend | Java 21 + Spring Boot | **presente e funzionante** | allineato |
| AI Engine | Python + LangGraph | **assente** | 100% da costruire |
| Database | Supabase (PostgreSQL) | **H2 in-memory**; driver PostgreSQL presente ma non configurato | non allineato |

Il `README.md` descrive quindi un'architettura *target*, non lo stato reale. Solo 1 dei 4 livelli esiste.

## 2. Backend — stack verificato

- **Linguaggio**: Java 21 (`java.version=21`); JDK locale rilevato: `21.0.12 LTS`.
- **Framework**: Spring Boot **4.1.0** (parent POM), Spring Framework 7.x, Hibernate ORM **7.4.1.Final**.
- **Build**: Maven via wrapper (`mvnw`, `distributionType=only-script`, Maven 3.9.16). Maven **non** è su PATH: si usa esclusivamente il wrapper.
- **Starter dichiarati** in `backend/pom.xml`:
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-validation`
  - `spring-boot-starter-webmvc` (naming Spring Boot 4, sostituisce `-web`)
  - runtime: `h2`, `postgresql`, `spring-boot-devtools`
  - opzionale: `lombok`
  - test: `spring-boot-starter-data-jpa-test`, `-validation-test`, `-webmvc-test`
- **Connection pool**: HikariCP (default Spring Boot).
- **Serializzazione**: Jackson 3 (default in Spring Boot 4).

## 3. Persistenza — stato reale

`backend/src/main/resources/application.properties`:

```
spring.datasource.url=jdbc:h2:mem:aicompany
spring.jpa.hibernate.ddl-auto=update
spring.h2.console.enabled=true
```

- Database **H2 in-memory**: ogni riavvio azzera i dati.
- Schema generato da Hibernate (`ddl-auto=update`): **nessuna migrazione versionata** (né Flyway né Liquibase).
- Il driver PostgreSQL è in `pom.xml` ma **non esiste alcun profilo o configurazione Postgres**.
- Nessuna traccia di Supabase, pgvector, o connessione remota.
- `spring.h2.console.enabled=true` è **inerte**: verificato a runtime, `GET /h2-console` risponde **404** (in Spring Boot 4 la console richiede il modulo dedicato, non incluso).

## 4. Verifica di build e test

Comando eseguito: `./mvnw -B test` in `backend/`.

- Esito: **BUILD SUCCESS**, exit code 0.
- Test eseguiti: 1 (`BackendApplicationTests.contextLoads`). Il contesto Spring si avvia correttamente.
- Build **offline non riproducibile**: `./mvnw -o test` fallisce perché `maven-resources-plugin:3.5.0` e dipendenze non sono nella cache locale `~/.m2`. Il progetto oggi richiede rete per buildare da pulito.
- Warning rilevati durante il test:
  - `spring.jpa.open-in-view is enabled by default` (non configurato esplicitamente).
  - Mockito self-attaching agent (deprecato per JDK futuri).

## 5. Verifica runtime degli endpoint

App avviata con `./mvnw spring-boot:run` (porta 8080 default, nessuna `server.port` configurata).

| Richiesta | Risultato |
|---|---|
| `GET /api/agents` | `200` — 3 agent seed restituiti |
| `POST /api/tasks` con payload completo | `200` — task creato e persistito correttamente |
| `GET /api/tasks` | `200` — task restituito |
| `POST /api/orchestrator` body `build me an ecommerce` | `200` — `"Backend Agent + Database Agent"` |
| `POST /api/tasks` con body `{}` | `200` — **riga creata con tutti i campi null** |
| `GET /h2-console` | `404` |

## 6. Infrastruttura e tooling assenti

- Nessun `Dockerfile`, `docker-compose.yml`.
- Nessuna pipeline CI (`.github/workflows`, `.gitlab-ci.yml`).
- Nessun linter/formatter configurato (Checkstyle, Spotless, EditorConfig).
- Nessun file di variabili d'ambiente o gestione segreti.
- Nessun `.gitignore` alla radice del repository (esiste solo `backend/.gitignore`).
- Nessun remote Git configurato; un solo commit (`930f70f Initial project structure`) e lavoro applicativo **staged ma non committato**.

## 7. Valutazione dello stack

Lo stack backend esistente è **moderno, coerente e funzionante**. Non ci sono ragioni tecniche per sostituirlo.

`docs/MASTER_PROMPT.md` indica FastAPI come backend preferito: questa preferenza è dichiarata "subject to TASK-000 validation". L'audit propone una **deviazione documentata** (vedi `MIGRATION_MAP.md` §5): mantenere Spring Boot come control plane e introdurre un servizio Python separato per il livello AI/LangGraph, coerentemente con lo stack poliglotta già dichiarato nel `README.md`.
