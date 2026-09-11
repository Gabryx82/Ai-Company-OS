# TASK-001A — Implementazione

## 1. Il difetto

`docs/reviews/TASK-001_CODEX_REVIEW.md`, R1 (HIGH).

TASK-001 collocava il seed di sviluppo in `db/dev/V1000__dev_seed_agents.sql`: cartella
separata, **ma stessa storia di Flyway dello schema**. La separazione era quindi solo di
location, non di stream.

Flyway versiona schema e seed insieme. Dopo `V1 + V1000` la storia di un database dev è:

| version | script |
|---|---|
| 1 | `V1__create_agents_and_tasks.sql` |
| 1000 | `V1000__dev_seed_agents.sql` |

Una successiva `V2` è **inferiore** alla versione applicata. Con `outOfOrder=false` — il
default, confermato sulla dipendenza effettiva — `migrate` si rifiuta di procedere:

```
Detected resolved migration not applied to database: 2
```

Il difetto è invisibile al primo avvio e al secondo: si manifesta soltanto alla prima
migrazione di schema successiva, cioè esattamente quando la fondazione dovrebbe reggere.

Conseguenza collegata (R2, MEDIUM): la garanzia dichiarata «un database seminato in dev non
è avviabile in prod» **non esisteva**. `V1000` è superiore all'ultima migrazione risolta in
prod (`V1`), quindi Flyway la classifica come *future* e
`ignoreMigrationPatterns=[*:future]` la ignora. La review ha avviato un contesto Spring
`prod` su un database seminato in dev: è partito.

## 2. La correzione

### 2.1 Due stream Flyway indipendenti

| Stream | Location | Tabella di storia | Chi lo applica |
|---|---|---|---|
| Schema | `classpath:db/migration` | `flyway_schema_history` | tutti i profili |
| Seed dev | `classpath:db/dev` | `flyway_dev_seed_history` | solo `dev` |

La separazione è ora **per storia**, non solo per cartella. Nessuno dei due stream vede le
versioni dell'altro, in nessuna direzione.

### 2.2 File

| File | Cambiamento |
|---|---|
| `db/dev/V1000__dev_seed_agents.sql` → `db/dev/V1__dev_seed_agents.sql` | Il seed riparte da `V1` nel proprio stream. Il numero non vincola più lo schema. |
| `persistence/DevSeedFlyway.java` (nuovo) | Lo stream del seed come oggetto: location, tabella di storia, `apply()`, e la rimozione della riga legacy. Senza annotazioni Spring, così i test lo guidano su schemi PostgreSQL isolati. |
| `persistence/DevSeedFlywayConfiguration.java` (nuovo) | `FlywayMigrationStrategy` attivo **solo** con `@Profile("dev")`. Ordina: pulizia legacy → `schemaFlyway.migrate()` → seed. |
| `application-dev.properties` | Rimosso l'override di `spring.flyway.locations`: lo stream di schema è identico in tutti i profili. |
| `application-prod.properties` | Commento corretto: separazione di responsabilità, **non** confine di sicurezza. |
| `application-test.properties` | Commento allineato agli stream. |

### 2.3 Perché un `FlywayMigrationStrategy` e non un secondo bean Flyway

Spring Boot configura **un solo** bean Flyway, e la costruzione dell'`EntityManagerFactory`
dipende dal suo initializer. Un secondo bean, un `CommandLineRunner` o un
`@PostConstruct` correrebbero il rischio di eseguire il seed **dopo** la validazione di
Hibernate, o in un ordine non dichiarato. La `FlywayMigrationStrategy` sostituisce il solo
corpo dell'initializer: l'ordine è esplicito nel codice e tutto avviene, come prima, prima
che Hibernate validi.

### 2.4 `baselineOnMigrate` sullo stream del seed

Quando il seed parte, lo stream di schema ha già creato le tabelle: dal punto di vista del
seed lo schema non è vuoto, e Flyway rifiuta di crearvi la propria storia
(`Found non-empty schema(s) ... but no schema history table`). Lo stream del seed usa quindi
`baselineOnMigrate=true` con `baselineVersion=0`: la baseline di default, `1`, avrebbe
marcato il seed `V1` come già applicato e non lo avrebbe mai eseguito. Nella storia del seed
compare perciò una riga `0 | << Flyway Baseline >>`.

### 2.5 Idempotenza a due livelli

1. La storia di Flyway: una migrazione versionata gira una volta per database.
2. Un `WHERE NOT EXISTS` sul nome dell'agent, dentro lo script. Serve al replay durante la
   transizione legacy, quando il seed viene registrato in uno stream nuovo su dati già
   presenti.

Il secondo livello non ripristina agent cancellati a mano nello stesso database: accettato
per dati dimostrativi.

### 2.6 Transizione dei database dev esistenti

Rinominare il file non aggiorna una storia già scritta: uno stream di schema che risolve
solo `V1` segnalerebbe `1000` come applicata-ma-mancante e fallirebbe la validazione a ogni
avvio. `DevSeedFlyway.removeLegacySeedHistory` rimuove **la sola riga**
`version = '1000' AND script = 'V1000__dev_seed_agents.sql'` da `flyway_schema_history`, con
log a `WARN`. Nessuna tabella, colonna o riga applicativa viene toccata; il volume non viene
cancellato.

La pulizia è confinata al profilo `dev`. In produzione `V1000` non deve mai essere stata
applicata, e correggerla in silenzio nasconderebbe un errore di deploy reale: lì
l'operazione deve restare manuale e consapevole.

## 3. Test

`MigrationStreamTest` (nuovo, 7 test) guida Flyway direttamente invece di avviare Spring:
ogni caso richiede un database in uno stato storico preciso — vuoto, seminato, o con la riga
`V1000` — che un contesto applicativo non sa esprimere. Ogni caso usa un **proprio schema
PostgreSQL** dentro un unico container Testcontainers, quindi i casi sono indipendenti e il
volume locale non viene mai toccato.

Due fixture sotto `src/test/resources`, mai risolte dall'applicazione:
`db/fixture/v2/V2__task_001a_probe.sql` (la «prossima migrazione») e
`db/fixture/legacy/V1000__dev_seed_agents.sql` (il layout pre-fix, con l'`INSERT` originale
non idempotente).

| Test | Dimostra | AC |
|---|---|---|
| `emptyDatabaseIsMigratedByTheSchemaStreamAlone` | DB vuoto → `V1` applicata, storia di schema esattamente `["1"]`, nessuna tabella di seed, zero righe | AC-1 |
| `devSeedAppliesInItsOwnHistoryAndDoesNotDuplicate` | Seed applicato, registrato in `flyway_dev_seed_history`; rilancio di entrambi gli stream → 0 migrazioni, 3 agent; replay diretto dell'`INSERT` → ancora 3 agent | AC-2 |
| `futureSchemaMigrationAppliesToASeededDatabase` | Su DB seminato: `V2` applicata, colonna creata, storia `["1","2"]`, seed e task preesistente conservati | AC-3 |
| `productionStreamIsIndependentOfTheDevelopmentSeed` | Su DB seminato, con la sola location di schema: `validate` passa, `migrate` 0 migrazioni, `info` non contiene script di seed, `V2` applicabile | AC-4 |
| `productionStreamOnAnUnseededDatabaseHasNoSeedArtefacts` | DB mai seminato: `V1`+`V2`, nessuna tabella di seed, zero agent | AC-4 |
| `legacyDatabaseCarryingV1000IsRecoverable` | Ricostruisce il DB legacy, **riproduce il fallimento R1**, applica la transizione, verifica assenza di duplicati e `V2` di nuovo applicabile | AC-5 |
| `removingLegacyHistoryIsANoOpWhenThereIsNothingToRemove` | Nessuna tabella di storia e storia senza riga legacy → nessuna scrittura | AC-5 |

Test esistenti rafforzati:

| Test | Cambiamento |
|---|---|
| `SchemaMigrationTest.initialMigrationIsApplied` | `contains("1")` → `containsExactly("1")`. L'assert precedente sarebbe passato anche con `V1000` nella storia: non intercettava R1. |
| `SchemaMigrationTest.hibernateOnlyValidatesTheSchema` (nuovo) | Assert esplicito di `ddl-auto=validate`, come chiesto da R4. |
| `SchemaMigrationTest.schemaContainsOnlyTheMigratedTables` | Verifica anche che in profilo `test` non esista `flyway_dev_seed_history`. |
| `DevSeedMigrationTest.theSeedIsRecordedInItsOwnHistoryNotInTheSchemaHistory` (nuovo) | Sul contesto reale: storia di schema `["1"]`, storia del seed `["0","1"]`. |
| `DevSeedMigrationTest.runningBothStreamsAgainDoesNotDuplicateTheSeed` | Rilancia **entrambi** gli stream, non solo quello di schema. |

## 4. Invarianti preservate

| Invariante | Verifica |
|---|---|
| PostgreSQL + Flyway | Nessun cambio di stack; H2 resta assente |
| Hibernate `validate` | `SchemaMigrationTest.hibernateOnlyValidatesTheSchema` |
| `POST /api/tasks` valido → `201` + `Location` | `TaskApiValidationTest` + smoke test manuale |
| `status` e `priority` obbligatori | `TaskApiValidationTest`; `POST {}` → `400` |

## 5. Fuori scope, deliberatamente

R3 (`.env` non letto dal backend), R5 (`server.address` non vincolato a loopback), R6 (tag
immagine mobile, nomi Compose fissi), R7 (`.gitignore` per `.env.*`) sono rilievi reali ma
indipendenti dalla strategia di migrazione. Vanno pianificati come task a sé.

Le correzioni documentali ai file `docs/audit/*` di TASK-000 restano aperte, come già
dichiarato nell'handoff di TASK-001.
