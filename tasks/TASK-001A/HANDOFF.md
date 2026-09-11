# TASK-001A — HANDOFF → Codex (review dei soli fix)

## Cosa è stato fatto

Chiuso il rilievo **R1 (HIGH)** della review di TASK-001. Schema e seed di sviluppo sono ora
**due stream Flyway indipendenti**, separati per location **e** per tabella di storia:

| Stream | Location | Storia | Chi lo applica |
|---|---|---|---|
| Schema | `classpath:db/migration` | `flyway_schema_history` | tutti i profili |
| Seed dev | `classpath:db/dev` | `flyway_dev_seed_history` | solo `dev` |

Il seed riparte da `V1` nel proprio stream ed è applicato da un
`FlywayMigrationStrategy` sotto `@Profile("dev")`, prima che Hibernate validi.
`application-dev.properties` non sovrascrive più `spring.flyway.locations`: lo stream di
schema è identico in dev, test e prod.

Corretta anche la parte di **R2** che dipendeva da R1: la garanzia «un database seminato in
dev non è avviabile in prod» **non esisteva** e non è stata reintrodotta come tale. Dopo il
fix la produzione è *indipendente* dal seed — si avvia correttamente sia su un database
seminato sia su uno mai seminato — e la policy «non promuovere un database di sviluppo»
resta documentata come **policy operativa, non come controllo tecnico**.

Recepite in via documentale anche le delimitazioni di **R4** (portata di `validate`) e il
comando di smoke test affidabile con devtools disabilitato.

## Stato Git

| Voce | Valore |
|---|---|
| Branch | `task-001-persistence-foundation` (invariato) |
| HEAD prima di TASK-001A | `b6e0b81` |
| Working tree | pulito |
| Remote | **nessuno configurato** — nessun push |
| `master` | intatto a `930f70f`, nessun merge |
| Storia | non riscritta; nessun force push, reset, rebase o cancellazione di branch |

Commit di TASK-001A:

| Hash | Messaggio |
|---|---|
| `dba676b` | `fix(persistence): separate the dev seed from the schema migration stream` |
| `2a9912b` | `test(persistence): cover migration stream separation and legacy recovery` |
| HEAD | `docs(task-001a): record ADR-003 and correct the dev/prod seed claims` (questo commit) |

Il commit di documentazione include anche `docs/reviews/TASK-001_CODEX_REVIEW.md`, che era
presente nel working tree ma non ancora versionato.

## Verifiche eseguite

```
./mvnw -B clean test  →  Tests run: 26, Failures: 0, Errors: 0, Skipped: 0  —  BUILD SUCCESS
```

| Verifica | Esito |
|---|---|
| DB vuoto migrabile dal solo stream di schema | storia `["1"]`, zero righe, nessuna tabella di seed |
| Seed dev applicato e non duplicato | 3 agent dopo rilancio degli stream **e** dopo replay diretto dell'`INSERT` |
| Futura `V2` su DB seminato | applicata; storia `["1","2"]`; seed e task preesistente conservati |
| Prod su DB seminato in dev | `validate` passa, `migrate` 0 migrazioni, `V2` applicabile |
| Prod su DB mai seminato | `V1`+`V2`, nessun artefatto di seed, zero agent |
| DB legacy con `V1000` | fallimento R1 riprodotto, poi recuperato senza duplicati |
| Database dev **reale** (volume `aicompany_postgres_data`) | transizione automatica: `flyway_schema_history` torna a `1`, seed nel proprio stream, 3 agent, dati intatti, volume non cancellato |
| `GET /api/agents` | `200`, 3 agent |
| `POST /api/tasks` valido | `201` + `Location` |
| `POST /api/tasks {}` | `400` |
| Secondo avvio | nessuna pulizia legacy ripetuta, `Successfully validated 1 migration` |

## Punti su cui è più utile un parere indipendente

1. **Pulizia automatica della riga `V1000`** in `dev`. È una scrittura su
   `flyway_schema_history`, per quanto circoscritta a una riga identificata da versione **e**
   nome script, confinata al profilo `dev` e tracciata a `WARN`. L'alternativa era una
   procedura manuale documentata, che però fa fallire l'avvio a chiunque aggiorni il branch.
   È il compromesso giusto, o la scrittura automatica va rimossa?
2. **`baselineOnMigrate=true` + `baselineVersion=0`** sullo stream del seed. Serve perché lo
   schema è già popolato quando il seed parte. Effetto collaterale: una riga `0 | << Flyway
   Baseline >>` nella storia del seed. Esiste un modo più pulito?
3. **Fixture di test in `src/test/resources/db/fixture/**`**: `V2__task_001a_probe.sql` e
   `V1000__dev_seed_agents.sql`. Non sono risolte dall'applicazione, ma sono file di
   migrazione nel repository. Collocazione accettabile?
4. **Prod resta avviabile su un database seminato in dev.** È la definizione di «indipendente»
   che ho scelto. L'alternativa — far fallire prod di fronte a tracce di seed — sarebbe un
   controllo reale ma renderebbe prod *dipendente* dal seed. Concordi con la scelta?
5. **Copertura della transizione legacy**: il test la esercita su schema PostgreSQL isolato e
   la verifica manuale l'ha esercitata sul volume dev reale. Serve altro?

## Rilievi della review ancora aperti, fuori scope TASK-001A

Nessuno dipende dalla strategia di migrazione; tracciati in `.company-os/PROJECT_STATE.md`.

| Rilievo | Contenuto |
|---|---|
| **R3** (MEDIUM) | `.env` configura Compose ma non il processo Maven del backend |
| **R5** (MEDIUM) | `server.address` non vincolato a loopback: la protezione loopback riguarda il DB, non il backend |
| **R6** (LOW) | `postgres:17-alpine` è un tag mobile; nomi Compose fissi fanno collidere due checkout |
| **R7** (LOW) | `.gitignore` non copre `.env.prod` / `.env.test` |
| devtools | Rimozione/ristrutturazione della dipendenza. In TASK-001A è stato solo **documentato** il comando affidabile in `docs/RUNNING.md` §2 |

Restano aperte anche le correzioni documentali ai file `docs/audit/*` di TASK-000 (TD-06,
TD-07, TD-14, percentuali di copertura da dichiarare come stime).

## Vincolo

TASK-002 **non avviata**. Nessun merge, push, remote o riscrittura di storia.
