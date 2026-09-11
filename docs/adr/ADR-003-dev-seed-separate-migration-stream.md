# ADR-003 — Il seed di sviluppo è uno stream Flyway separato dallo schema

- **Stato**: Accettata
- **Data**: 2026-09-11
- **Task**: TASK-001A — Fix Flyway Migration and Dev Seed Strategy
- **Sostituisce**: il punto 6 di [ADR-002](ADR-002-postgresql-flyway-persistence.md)

## Contesto

TASK-001 aveva collocato il seed di sviluppo in `db/dev/V1000__dev_seed_agents.sql`:
stessa storia di Flyway dello schema, numero di versione alto per tenerlo «in fondo».

La review differenziale Codex (`docs/reviews/TASK-001_CODEX_REVIEW.md`, R1 — HIGH) ha
dimostrato che questa scelta rompe l'evoluzione dello schema. Flyway tratta schema e seed
come **un unico insieme versionato**: dopo `V1 + V1000` il database di sviluppo è alla
versione 1000, e una successiva `V2` è una versione inferiore a quella applicata. Con
`outOfOrder=false` (il default, confermato sulla dipendenza effettiva) `migrate` fallisce:

```
Detected resolved migration not applied to database: 2
```

Un database nuovo e un database di sviluppo persistente seguono quindi percorsi diversi:
il difetto non si manifesta al primo avvio, ma alla prima migrazione successiva.

La stessa review (R2 — MEDIUM) ha mostrato che la garanzia dichiarata «un database
seminato in dev non è avviabile in prod» non esisteva: `V1000` è **superiore** all'ultima
migrazione risolta in prod (`V1`), quindi Flyway la classifica come *future* e la
configurazione effettiva ignora `*:future`. Il contesto Spring con profilo `prod` si
avviava senza errori su un database seminato in dev.

## Decisione

1. **Due stream Flyway indipendenti**, distinti per location **e** per tabella di storia:

   | Stream | Location | Tabella di storia | Chi lo applica |
   |---|---|---|---|
   | Schema | `classpath:db/migration` | `flyway_schema_history` | tutti i profili |
   | Seed dev | `classpath:db/dev` | `flyway_dev_seed_history` | solo il profilo `dev` |

2. Il seed **riparte da `V1`** nel proprio stream (`db/dev/V1__dev_seed_agents.sql`). Il
   numero di versione del seed non vincola più quello dello schema, in nessuna direzione.

3. Lo stream del seed è applicato da `DevSeedFlywayConfiguration`, un bean
   `FlywayMigrationStrategy` attivo **solo** sotto il profilo `dev`. L'ordine è esplicito:
   pulizia della storia legacy → migrazione dello schema → migrazione del seed. Tutto
   avviene prima della costruzione dell'`EntityManagerFactory`, quindi Hibernate continua a
   validare uno schema già migrato.

4. `application-dev.properties` **non** sovrascrive più `spring.flyway.locations`: lo
   stream di schema è identico in dev, test e prod.

5. Il seed è idempotente **due volte**: per la storia di Flyway e per un `WHERE NOT EXISTS`
   nel SQL. Il secondo livello serve al replay durante la transizione legacy.

6. **Transizione per i database dev che contengono già `V1000`**: il bean dev rimuove la
   singola riga `version = '1000' AND script = 'V1000__dev_seed_agents.sql'` dalla storia di
   schema, con log a `WARN`. Nessuna tabella, colonna o riga applicativa viene toccata e il
   volume non viene cancellato. Il seed si registra poi nel proprio stream senza duplicare
   nulla. La pulizia è confinata a `dev`: in produzione `V1000` non deve mai essere stata
   applicata, e correggere in silenzio una storia di produzione nasconderebbe un errore di
   deploy reale.

## Motivazione

- Lo stream dello schema resta **lineare e proseguibile** (`V2`, `V3`, …) indipendentemente
  dal fatto che un database sia stato seminato.
- Il seed resta **un solo proprietario**, versionato e revisionabile, senza tornare a un
  inizializzatore applicativo con `count()==0`.
- La produzione diventa **realmente indipendente** dal seed: non ne legge né ne scrive la
  storia, quindi non lo esegue e non lo segnala come mancante.
- `outOfOrder` resta **disattivato**. Abilitarlo avrebbe aggirato il sintomo accettando
  migrazioni fuori ordine ovunque, produzione compresa.

## Conseguenze

**Positive**
- Regressione dimostrata e coperta: `MigrationStreamTest` ricostruisce un database legacy,
  riproduce il fallimento di `V2` e verifica il recupero.
- Un database dev esistente si aggiorna da solo al primo avvio, senza perdita di dati e
  senza `docker compose down -v`.

**Negative, accettate**
- Il database dev contiene **due** tabelle di storia Flyway. È il prezzo della separazione
  ed è visibile solo in sviluppo.
- Lo stream del seed usa `baselineOnMigrate=true` con `baselineVersion=0`: quando parte, lo
  schema è già popolato dallo stream di schema, quindi Flyway rifiuterebbe di creare la
  propria storia. La riga di baseline `0` compare nella storia del seed.
- La pulizia automatica della riga legacy è una scrittura sulla storia di Flyway. È
  circoscritta al profilo `dev`, a una sola riga identificata da versione **e** nome
  script, ed è tracciata a log.

## Correzione esplicita di una garanzia dichiarata in ADR-002

ADR-002 affermava che un database seminato in dev non fosse promuovibile a produzione
perché Flyway avrebbe segnalato `V1000` come applicata ma mancante. **È falso**: la review
ha verificato che veniva trattata come *future* e ignorata.

Dopo TASK-001A la separazione è di **responsabilità, non di sicurezza**. La produzione è
indipendente dal seed: si avvia correttamente sia su un database mai seminato sia su uno
che lo è stato, perché non guarda la storia del seed. Nessun meccanismo impedisce
tecnicamente di puntare `prod` a un database di sviluppo; resta una **policy operativa**,
documentata in `docs/RUNNING.md`, non un controllo applicativo.

## Alternative scartate

- **Abilitare `flyway.outOfOrder`**: aggira il sintomo e indebolisce la garanzia d'ordine su
  tutti gli ambienti. Esplicitamente sconsigliato dalla review.
- **Migrazione ripetibile `R__dev_seed_agents.sql` nello stesso stream**: non vincolerebbe
  le versioni, ma resterebbe registrata in `flyway_schema_history`, dove la produzione la
  classificherebbe come applicata-ma-mancante e fallirebbe la validazione. Rende la
  produzione *dipendente* dal seed, l'opposto dell'obiettivo.
- **Rinumerare il seed a `V1.1` nello stream dello schema**: sposta il problema di un
  gradino, senza separare le responsabilità.
- **Tornare a un seed applicativo `@Profile("dev")`**: nessun percorso versionato, nessuna
  storia, e reintroduce la classe rimossa in TASK-001.
- **Cancellare il volume dev**: distruttivo e inutile, dato che i dati sono recuperabili.

## Non deciso qui

Restano aperti i rilievi indipendenti della review: R3 (`.env` non letto dal backend), R5
(`server.address` non vincolato a loopback), R6 (tag immagine mobile, nomi Compose fissi),
R7 (regole `.gitignore` per i file `.env.*`). Nessuno dipende dalla strategia di migrazione.
