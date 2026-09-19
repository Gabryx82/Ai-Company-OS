# TASK-012 — Implementazione (TD-31)

TD-14 non ha implementazione: è bloccato, e l'evidenza sta in `EVIDENCE_TD14.md`.

## File

### Nuovi

| File | Ruolo |
|---|---|
| `db/migration/V8__unify_agent_lifecycle.sql` | La migrazione. **La prima distruttiva dello stream** |
| `agent/model/AgentStatus.java` | L'enum chiuso |
| `docs/adr/ADR-012-agent-lifecycle-unification.md` | Le decisioni |

### Modificati

| File | Cambio |
|---|---|
| `agent/model/Agent.java` | `boolean active` → `AgentStatus status` con `@Enumerated(STRING)`; `requireActive(boolean)` → `requireStatus(AgentStatus)`; `isActive()` resta, **derivato** |
| `agent/repository/AgentRepository.java` | `findAllByActiveOrderByIdAsc(boolean)` → `findAllByStatusOrderByIdAsc(AgentStatus)` |
| `agent/service/AgentService.java` | Traduce il booleano del contratto in `AgentStatus`. **È l'unico punto in cui i due vocabolari si incontrano** |
| `agent/dto/AgentResponse.java` | `status` dalla colonna invece che calcolato. **Record e JSON invariati** |
| `db/dev/V1__dev_seed_agents.sql` | Scrive `status` invece di `active`. Inevitabile: §5 di ADR-012 |
| `persistence/DevSeedFlyway.java` | `repair()` prima di `migrate()` |
| 6 classi di test | `SELECT active` → `SELECT status`; fixture resi version-aware; liste di versioni a `"8"` |

## Perché `@Enumerated(STRING)`

Il `CHECK` confronta i **nomi**, quindi la colonna deve contenerli. Un ordinale renderebbe il dato
illeggibile fuori dall'applicazione, e riordinare l'enum riscriverebbe il significato di ogni riga
senza toccarne una. Stessa scelta di `Task.status` in `V7`.

## Perché la migrazione è in cinque passi e l'ordine non è intercambiabile

1. `ADD COLUMN status VARCHAR(32)` — **nullable**, per la durata della migrazione;
2. `UPDATE ... CASE WHEN active` — la biiezione, scritta una volta sola;
3. `SET NOT NULL` — **dopo** il backfill. Prima servirebbe un `DEFAULT`, e un `DEFAULT` qui
   **mentirebbe** su qualunque dei due stati non scegliesse;
4. `ADD CONSTRAINT agents_status_check` — la seconda guardia di ADR-004 §2;
5. `DROP COLUMN active` — il passo irreversibile, quello che l'autorizzazione umana copre.

Il contrasto con `V4` è dichiarato nel file: là un `DEFAULT` serviva proprio perché scrittori
esterni **omettono** la colonna; qui nessuno può omettere uno stato di ciclo di vita senza che
qualcuno decida cosa significhi.

## Test aggiunti

| Test | Cosa asserisce |
|---|---|
| `MigrationStreamTest.agentLifecycleIsUnifiedOnAPopulatedV7Database` | La biiezione, **entrambe le direzioni**, su righe seminate prima della migrazione; colonna vecchia assente; conteggi invariati; vincolo reale |
| `MigrationStreamTest.anAlreadySeededDatabaseSurvivesTheSeedBeingEdited` | Un checksum stantio non impedisce l'avvio, e il seed non viene ri-eseguito |
| `SchemaMigrationTest.agentLifecycleIsAStatusColumnAndTheBooleanIsGone` | **Presenza e assenza**: una migrazione che avesse aggiunto senza eliminare lascerebbe lo stesso stato registrato due volte |
| `SchemaMigrationTest.agentStatusIsConstrainedToTheClosedSet` | Il `CHECK` esiste e dichiara i due valori |

`AgentRegistryApiTest.statusIsDerivedAndNotStored` è stato **girato**, non cancellato: adesso si
chiama `oneLifecycleStateIsStoredAndTheOtherFieldIsDerived` e asserisce che **esattamente uno**
dei due esista nel database, mentre il JSON ne porta due. L'invariante che conta non è cambiata.
