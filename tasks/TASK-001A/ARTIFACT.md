# TASK-001A — Artefatto ed evidenze

## Esito

Il rilievo **R1 (HIGH)** è chiuso: lo stream delle migrazioni di schema e il seed di
sviluppo sono separati per location **e** per tabella di storia. Una futura `V2` è
applicabile su un database dev già seminato, dimostrato da un test che prima riproduce il
fallimento originale.

## File

**Nuovi**

| File | Ruolo |
|---|---|
| `backend/src/main/java/com/aicompany/backend/persistence/DevSeedFlyway.java` | Lo stream del seed: location, tabella di storia, `apply()`, rimozione della riga legacy |
| `backend/src/main/java/com/aicompany/backend/persistence/DevSeedFlywayConfiguration.java` | `FlywayMigrationStrategy` attiva solo con `@Profile("dev")` |
| `backend/src/test/java/com/aicompany/backend/persistence/MigrationStreamTest.java` | 7 regressioni sulla strategia di migrazione |
| `backend/src/test/resources/db/fixture/v2/V2__task_001a_probe.sql` | Fixture: «la prossima migrazione di schema» |
| `backend/src/test/resources/db/fixture/legacy/V1000__dev_seed_agents.sql` | Fixture: layout pre-fix, per ricostruire un database legacy |
| `docs/adr/ADR-003-dev-seed-separate-migration-stream.md` | Decisione architetturale |

**Rinominati**

| Da | A |
|---|---|
| `backend/src/main/resources/db/dev/V1000__dev_seed_agents.sql` | `backend/src/main/resources/db/dev/V1__dev_seed_agents.sql` (contenuto reso idempotente) |

**Modificati**

`application-dev.properties`, `application-prod.properties`, `application-test.properties`,
`SchemaMigrationTest.java`, `DevSeedMigrationTest.java`, `docs/RUNNING.md`,
`docs/adr/ADR-002-postgresql-flyway-persistence.md`, `.company-os/PROJECT_STATE.md`.

## Evidenze

### Suite automatica

```
./mvnw -B clean test  →  Tests run: 26, Failures: 0, Errors: 0, Skipped: 0  —  BUILD SUCCESS
```

Da 17 a 26 test, tutti contro PostgreSQL reale. Nessun test saltato, nessun fallback
embedded.

| Classe | Test |
|---|---|
| `MigrationStreamTest` | 7 |
| `TaskApiValidationTest` | 6 |
| `SchemaMigrationTest` | 5 |
| `TaskPersistenceTest` | 4 |
| `DevSeedMigrationTest` | 3 |
| `BackendApplicationTests` | 1 |

### Requisiti di test richiesti dalla task

| Richiesta | Test | Esito |
|---|---|---|
| DB vuoto migrabile | `emptyDatabaseIsMigratedByTheSchemaStreamAlone` | ✅ storia di schema `["1"]`, zero righe |
| Seed dev funzionante e non duplicato | `devSeedAppliesInItsOwnHistoryAndDoesNotDuplicate` | ✅ 3 agent dopo rilancio degli stream e dopo replay diretto dell'`INSERT` |
| Futura `V2` applicabile | `futureSchemaMigrationAppliesToASeededDatabase` | ✅ storia `["1","2"]`, dati preesistenti conservati |
| Produzione indipendente dal seed dev | `productionStreamIsIndependentOfTheDevelopmentSeed`, `productionStreamOnAnUnseededDatabaseHasNoSeedArtefacts` | ✅ `validate` passa su DB seminato, `V2` applicabile; su DB mai seminato nessun artefatto di seed |
| Recupero di un DB legacy con `V1000` | `legacyDatabaseCarryingV1000IsRecoverable` | ✅ fallimento R1 riprodotto, poi risolto, senza duplicati |

### Verifica sul database dev reale

Il volume locale `aicompany_postgres_data` era in stato legacy. Stato **prima**:

```
 version |             script              | success
---------+---------------------------------+---------
 1       | V1__create_agents_and_tasks.sql | t
 1000    | V1000__dev_seed_agents.sql      | t
agents: 3
```

Avvio con `./mvnw -B spring-boot:run "-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false"`:

```
WARN  c.a.b.p.DevSeedFlywayConfiguration : Removed the legacy V1000__dev_seed_agents.sql entry
      from flyway_schema_history: the development seed now lives in its own Flyway stream
      (flyway_dev_seed_history). Seeded rows were left untouched.
INFO  c.a.b.p.DevSeedFlywayConfiguration : Development seed stream up to date: 1 migration(s)
      applied, schema version 1
```

Stato **dopo**:

```
flyway_schema_history      →  1 | V1__create_agents_and_tasks.sql
flyway_dev_seed_history    →  0 | << Flyway Baseline >> (BASELINE)
                              1 | V1__dev_seed_agents.sql (SQL)
agents: 3
```

| Verifica manuale | Esito |
|---|---|
| `GET /api/agents` dopo la transizione | `200`, 3 agent, nessun duplicato |
| `POST /api/tasks` valido | `201` + `Location: /api/tasks/2` |
| `POST /api/tasks {}` | `400` |
| Secondo avvio | nessuna pulizia legacy nel log, `Successfully validated 1 migration`, 3 agent, task conservati |

Il volume **non** è stato cancellato; nessun `docker compose down -v`. La riga di task creata
dallo smoke test è stata rimossa al termine, lasciando il database dev nello stato
precedente più la transizione di storia.

## Note d'ambiente

`mvnw.cmd` richiede Windows PowerShell nel `PATH` del processo che lo lancia. È un limite
dell'ambiente dell'agente, non del progetto: nessun file del repository è stato modificato
per aggirarlo. Stesso rilievo già riportato dalla review Codex.

## Stato Git

Branch `task-001-persistence-foundation`. Nessun merge, push, remote o riscrittura di
storia. TASK-002 non avviata.
