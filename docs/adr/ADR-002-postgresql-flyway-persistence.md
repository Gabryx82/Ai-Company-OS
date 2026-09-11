# ADR-002 — PostgreSQL con schema di proprietà di Flyway, verificato su database reale

- **Stato**: Accettata, **parzialmente superata**
- **Data**: 2026-09-11
- **Task**: TASK-001 — Reproducible Persistence Foundation
- **Superata in parte da**: [ADR-003](ADR-003-dev-seed-separate-migration-stream.md) —
  il punto 6 (seed come migrazione versionata in `db/dev`, dentro lo stream dello schema) e
  la conseguenza sulla non promuovibilità dev→prod sono stati corretti in TASK-001A.

## Contesto

Lo stato precedente era H2 in-memory con `spring.jpa.hibernate.ddl-auto=update`: nessun dato sopravviveva al riavvio e lo schema veniva dedotto dalle entità a ogni avvio, senza storia né possibilità di rollback (TD-01, TD-02 dell'audit).

La review Codex (R4) ha inoltre chiarito che una "baseline dello schema Hibernate corrente" non è applicabile: il database PostgreSQL di partenza è vuoto, quindi serve una migrazione che **crei** lo schema, e i tipi non vanno ereditati dal DDL H2.

## Decisione

1. **PostgreSQL** è il database applicativo, avviato localmente da `docker-compose.yml` con volume nominato.
2. **Lo schema è di proprietà di Flyway.** La migrazione `V1` crea le tabelle da zero, con tipi e identità scelti per PostgreSQL.
3. **Hibernate gira in `validate`** e non modifica mai lo schema. La garanzia va però delimitata, come chiarito dalla review (R4): il validatore verifica presenza e compatibilità di tipo delle colonne mappate — una colonna richiesta e assente **blocca l'avvio** e non viene ricreata — ma **non** confronta ogni proprietà dello schema: la rimozione di un `NOT NULL` non fa fallire l'avvio. I vincoli sono verificati dai test SQL, che aggiungono una garanzia diversa.
4. **H2 è rimosso dal progetto.** Un database embedded sul classpath permetterebbe a una configurazione errata di ripiegare silenziosamente su di esso invece di fallire.
5. **I test girano contro PostgreSQL reale** via Testcontainers. Un test su database embedded non verificherebbe le migrazioni, i tipi e i vincoli effettivamente usati.
6. ~~**Il seed di sviluppo ha un solo proprietario**: una migrazione versionata in `db/dev`, inclusa solo dal profilo `dev`.~~ **Superato da ADR-003.** Il seed resta con un solo proprietario e resta una migrazione versionata in `db/dev`, ma in uno **stream Flyway separato**, con tabella di storia propria (`flyway_dev_seed_history`), perché tenerlo nello stream dello schema impediva le migrazioni successive alla `V1`. `AgentInitializer` è stato rimosso ed è rimasto rimosso.

## Motivazione

- Lo schema diventa un artefatto versionato e revisionabile, allineato al resto del codice.
- `validate` trasforma il drift da guasto silenzioso in errore di avvio immediato.
- L'idempotenza del seed poggia sulla storia di Flyway, non su un controllo applicativo `count()==0` che — come osservato dalla review — non garantisce nulla con avvii concorrenti o seed parziali.

## Conseguenze

**Positive**
- Persistenza reale e riproducibile; ambiente identico fra sviluppo e test.
- Ogni modifica di schema passa da una migrazione esplicita.

**Negative, accettate**
- **Docker diventa un prerequisito per eseguire i test.** Senza daemon attivo la suite non parte. È un costo accettato in cambio di test che verificano il database vero.
- I test sono più lenti dell'equivalente in-memory.
- ~~**Un database seminato in `dev` non è promuovibile a produzione**: conterrebbe `V1000`, che il profilo `prod` non risolve e che Flyway segnalerebbe come applicata ma mancante.~~ **Affermazione errata, corretta da ADR-003.** La review Codex (R2) ha verificato che `V1000`, essendo superiore all'ultima migrazione risolta in prod, veniva classificata come *future* e ignorata da `ignoreMigrationPatterns=[*:future]`: il profilo `prod` si avviava senza errori. Dopo TASK-001A la separazione dev/prod è di **responsabilità, non di sicurezza**; la policy «non promuovere un database di sviluppo» resta documentata in `docs/RUNNING.md` ma non è imposta da Flyway.

## Alternative scartate

- **Mantenere H2 per i test e PostgreSQL per lo sviluppo**: i test smetterebbero di verificare il database reale, esattamente la garanzia che questa task doveva introdurre.
- **`ddl-auto=update` con PostgreSQL**: conserverebbe il drift silenzioso, il problema centrale di TD-02.
- **Seed applicativo con profilo `dev`**: scartato perché rinuncia a un percorso versionato e revisionabile del seed e reintroduce la classe rimossa in questa task. La motivazione originaria — «lascerebbe due proprietari del seed» — era imprecisa, come osservato dalla review: con il seed SQL rimosso il proprietario sarebbe stato comunque uno solo.

## Non deciso qui

Enum di dominio, macchine a stati, relazioni fra entità ed entità `Project` restano rinviati al task di dominio, come richiesto dalla review (R5). `status` e `priority` restano stringhe libere, ora obbligatorie.
