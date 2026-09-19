# TASK-010 — Status census

> Eseguito il 2026-09-19, prima di qualunque decisione sul vocabolario, perché
> `tasks/TASK-009/HANDOFF.md` avverte che stringere `status` **non è additivo** se i dati
> contengono valori fuori vocabolario, e cosa farne toccherebbe gli hard stop #2 e #3 del
> charter. La domanda non è opinabile e non va indovinata: va contata.

Ogni riga qui sotto è un fatto verificabile con un comando, e il comando è riportato.

## 1. Schema e migrazioni

`backend/src/main/resources/db/migration/V1__create_agents_and_tasks.sql`:

```sql
status      VARCHAR(255) NOT NULL,
```

- **Nessun `CHECK`**, nessun `DEFAULT`, nessun enum PostgreSQL.
- `V2`…`V6` **non toccano mai** `tasks.status`. Verificato:
  `grep -l "status" src/main/resources/db/migration/*.sql` → solo `V1` (tasks) e `V2` (projects).
- Il contrasto è dichiarato nel repository stesso: `V2` dà a `projects.status` un
  `projects_status_check CHECK (status IN ('ACTIVE','ARCHIVED'))` con il commento «*The allowed
  set is closed and owned by the database, not only by the enum*». `tasks.status` non ha mai
  ricevuto lo stesso trattamento.

**Conclusione**: il database non vincola nulla. Oggi accetta qualunque stringa ≤ 255 caratteri.

## 2. Dev seed

`backend/src/main/resources/db/dev/V1__dev_seed_agents.sql` inserisce **tre agenti e nient'altro**.

- **Zero righe di `tasks`.** Il seed non ha mai creato un task.
- Conseguenza: il seed **non contribuisce alcun valore** al censimento, e — a differenza di quanto
  successe con `V4` — non può essere rotto da un vincolo su `tasks`, perché non scrive su quella
  tabella.

## 3. Fixture e test

Tre famiglie di scrittori, censite separatamente perché falliscono in modi diversi.

| Famiglia | Come si conta | Valori trovati |
|---|---|---|
| Costruttore d'entità | `grep -rn "new Task(" src/` → 26 occorrenze (1 main, 25 test) | `"OPEN"` in **tutte** le 25 di test |
| Payload JSON di API | `grep -rhon '"status":"[^"]*"' src/test` | `OPEN` ×13, `ARCHIVED` ×1 — e l'unico `ARCHIVED` è `ProjectApiTest:140`, cioè un **progetto**, non un task |
| `INSERT` SQL diretti | `grep -rn "INSERT INTO .*tasks" src/test` → 6 occorrenze | `'OPEN'` in tutte, tranne una deliberata `NULL` (`TaskPersistenceTest:62`, che asserisce il `NOT NULL`) |

**Un test merita una riga a sé.** `MigrationStreamTest.everyConsecutiveUpgradePreservesWhatWasAlreadyThere`
scrive una riga in `tasks` **a ogni versione `N`** dello stream e poi migra a `N+1`:

```java
jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) "
        + "VALUES (?, ?, ?)", "written at V" + from, "OPEN", "HIGH");
```

Il valore è `OPEN`. Questo è il vincolo operativo più stretto che il censimento produce: **una
`V7` che escludesse `OPEN` dal vocabolario renderebbe rosso quel test**, a ogni coppia, senza che
nessuno debba ricordarsene. È una guardia che esiste già e che va rispettata, non aggirata.

## 4. Database locale corrente

Volume `aicompany_postgres_data`, container `aicompany-postgres` (`postgres:17-alpine`), avviato
per il censimento e non modificato.

```
 version |        description        | success
---------+---------------------------+---------
 1       | create agents and tasks   | t
 2       | create projects           | t
 3       | add task project relation | t
```

**Il database di sviluppo reale è a `V3`, non a `V6`.** È il fatto più importante del censimento
e nessun documento lo diceva: `PROJECT_STATE.md` dichiara lo schema «a `V6`», il che è vero dello
*stream Flyway* e falso di **questa installazione**. `V4`, `V5` e `V6` non le ha mai viste.

Contenuto di `tasks`:

```
 id | title | status | priority | project_id
----+-------+--------+----------+------------
  1 | ok    | OPEN   | LOW      |
```

```
 status | count
--------+-------
 OPEN   |     1
```

**Una riga, scritta a mano (`title = 'ok'`), `status = 'OPEN'`.** Non proviene dal seed — il seed
non scrive task — quindi è stata creata da qualcuno via API o via `psql`. È esattamente la
categoria di riga che il censimento esisteva per trovare, ed è dentro vocabolario.

## 5. Write path e read path reali

**Write path: ce n'è esattamente uno, e non è quello che ci si aspetta.**

`grep -rn "getStatus()\|\.status()" src/main/java` restituisce, per i task, due sole righe:
`TaskController:68` (legge il campo della request) e `TaskResponse:39` (lo rimette nella risposta).

- **`POST /api/tasks`** → `TaskService.create(...)` → `new Task(title, description, status, priority)`.
  È l'**unico** punto in cui `status` viene scritto.
- **Non esiste alcun percorso di mutazione di `status`.** Non c'è `PUT /api/tasks/{id}`, non c'è
  `PUT /api/tasks/{id}/status`, non c'è un setter sull'entità. Un task creato `OPEN` resta `OPEN`
  per sempre.
- `MasterOrchestrator` e `OrchestratorController` **non toccano i task**: `analyze` è una funzione
  da stringa a stringa, senza repository. Verificato leggendo entrambi i file per intero.

**Read path: nessuno legge `status` per decidere qualcosa.**

- `TaskRepository` non ha alcun metodo su `status` (`grep -n "status" TaskRepository.java` → vuoto).
- Nessun filtro `?status=` su `GET /api/tasks`, a differenza di `GET /api/projects?status=`.
- `TaskResponse` lo espone come passthrough. **Nessun ramo condizionale nel codice dipende dal suo
  valore**, in `main` o in test.

## 6. Che cosa il contratto promette implicitamente

Questa è la seconda metà della domanda, e la risposta è più netta di quanto sembri.

`TaskCreateRequest`:

```java
@NotBlank(message = "status is required")
@Size(max = 255, message = "status must be at most 255 characters")
String status,
```

Il javadoc dello stesso record dice, testualmente:

> `status` and `priority` are required free-form strings. They are not enums yet: defining the
> allowed values and their transitions is a domain decision left to a later task.

E `docs/audit/CURRENT_FEATURES.md:51`, che è il documento che afferma cosa il sistema fa oggi:

> Stringhe libere: `"OPEN"`, `"open"`, `"banana"` sono tutti accettati. Nessun enum, nessuna
> macchina a stati, nessun vincolo DB.

**Il contratto non promette alcun vocabolario. Promette esplicitamente la sua assenza**, e la
promessa è documentata due volte, in un javadoc e in un documento di audit, e in entrambi i casi
come *provvisorio*, con il rinvio a «a later task» scritto dentro.

L'unico valore che compare in documentazione d'uso è `OPEN`, in `docs/RUNNING.md:63`:

```
-d "{\"title\":\"Primo task\",...,\"status\":\"OPEN\",\"priority\":\"HIGH\"}"
```

— un esempio, non un contratto.

## 7. Storia

Nessun altro valore è mai esistito in nessun branch:

```
git log --all --oneline -S"IN_PROGRESS" -- .    → vuoto
git log --all --oneline -S"COMPLETED" -- backend/ → vuoto
```

Non è mai esistito un seed di task, e l'unico initializer mai cancellato (`AgentInitializer`,
rimosso in `dba676b`) riguardava gli agenti.

## 8. Esito

| Domanda | Risposta |
|---|---|
| Valori di `Task.status` esistenti, ovunque | **`{ OPEN }`**, uno solo |
| Valori fuori da un vocabolario che contenga `OPEN` | **Nessuno**, in nessuna delle cinque fonti |
| Vocabolario implicitamente promesso dal contratto | **Nessuno.** Il contratto promette l'assenza di vocabolario, e la dichiara provvisoria |
| Righe da trasformare | **Zero** |
| Hard stop #2 o #3 attivato | **No** |

**Si applica la regola 3 del briefing**: tutti i valori esistenti rientrano in qualunque
vocabolario proposto che contenga `OPEN`. Enum, vincolo DB e migrazione incrementale sono
percorribili, con un upgrade test, **senza alcuna trasformazione di dati** e senza toccare una
sola riga esistente.

Non serve una strategia staged, non serve un mapping, non c'è debito residuo da
irreversibilità. Il rischio dichiarato che resta è diverso e va dichiarato per quello che è: su un
database **diverso da quelli censiti** che contenesse un valore fuori vocabolario, la migrazione
**fallisce**. È il comportamento corretto, ed è il precedente esatto di `V4` con
`agents_name_unique_idx` — «*a migration that stops is the conversation that makes them*».

## 9. Comandi, per rifare il censimento

```bash
# schema
grep -n "status" backend/src/main/resources/db/migration/*.sql

# seed
grep -c "INSERT INTO tasks" backend/src/main/resources/db/dev/V1__dev_seed_agents.sql   # 0

# fixture
grep -rhon '"status":"[^"]*"' backend/src/test --include=*.java | sed 's/.*"status":"//; s/"$//' | sort | uniq -c
grep -rn "new Task(" backend/src --include=*.java

# database locale
docker start aicompany-postgres
docker exec aicompany-postgres psql -U aicompany -d aicompany \
  -c "SELECT version, description FROM flyway_schema_history ORDER BY installed_rank;" \
  -c "SELECT status, count(*) FROM tasks GROUP BY status;"

# write/read path
grep -rn "getStatus()\|\.status()" backend/src/main/java --include=*.java
grep -n "status" backend/src/main/java/com/aicompany/backend/task/repository/TaskRepository.java
```
