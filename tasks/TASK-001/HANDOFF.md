# TASK-001 — HANDOFF → Codex (review differenziale)

## Cosa è stato fatto
Sostituita H2 in-memory + `ddl-auto=update` con PostgreSQL, schema di proprietà di Flyway, DTO con Bean Validation e test su database reale. Scope rispettato: nessun `Project`, relazione, enum, provider, frontend o CRUD aggiuntivo.

## Stato Git

| Voce | Valore |
|---|---|
| Branch corrente | `task-001-persistence-foundation` |
| Creato da | `task-000-audit` (HEAD `503663c`) |
| Working tree | pulito |
| Remote | **nessuno configurato** — nessun push eseguito |
| `master` | intatto a `930f70f`, nessun merge |
| Storia | non riscritta; nessun force push, reset, rebase o cancellazione di branch |

Commit creati su questo branch:

| Hash | Messaggio |
|---|---|
| `2f01194` | `docs(task-000): add Codex differential review` |
| `5e164f5` | `feat(persistence): add PostgreSQL, Flyway and profile configuration` |
| `c295189` | `test(persistence): verify migrations, persistence and validation on PostgreSQL` |
| HEAD | `docs(task-001): document persistence foundation and record ADRs` (questo commit) |

Operazioni Git ancora da fare prima di un eventuale merge:
1. Decidere una destinazione remota (**richiede approvazione esplicita**: nessun remote è stato configurato).
2. Attivare una CI che esegua `./mvnw test` con Docker disponibile.
3. Integrare su `master` — non autorizzato in questa task.

## Verifiche eseguite

```
./mvnw -B clean test  →  Tests run: 17, Failures: 0, Errors: 0  —  BUILD SUCCESS
```

| Verifica | Esito |
|---|---|
| `GET /api/agents` | `200`, 3 agent dal seed Flyway |
| `POST /api/tasks` valido | `201` + `Location: /api/tasks/1` |
| `POST /api/tasks {}` | `400`, nessuna riga scritta |
| Riavvio applicazione | dati conservati, seed non duplicato, `Schema "public" is up to date` |
| `docker compose down` + `up` (stesso volume) | nuovo container `f4bfee3994c1`, dati intatti |
| Colonna rimossa a mano + avvio | `SchemaManagementException: missing column [priority]`, avvio bloccato |

## Punti su cui è più utile un parere indipendente

1. **`status` e `priority` resi obbligatori** invece di opzionali con default. Scelta per non inventare semantica di dominio (R5). È il compromesso giusto?
2. **`POST` da `200` a `201`**: cambio di contratto deliberato su endpoint senza client. Accettabile?
3. **H2 rimosso del tutto** invece di conservato per test rapidi (la review lo riteneva ammissibile). Motivazione in ADR-002: evitare fallback silenziosi.
4. **Seed dev come migrazione `V1000` in `db/dev`**: un database seminato in dev non è promuovibile a produzione (Flyway lo segnalerebbe come applicato ma mancante). Documentato in `docs/RUNNING.md` — è sufficiente o serve un meccanismo?
5. **Verifica del volume manuale, non automatizzata**: Testcontainers usa container effimeri. Accettabile o serve un test dedicato?

## Rilievo emerso, fuori scope

Con `spring-boot-devtools` sul classpath, un fallimento di avvio avviene sul thread `restartedMain` e **il processo Maven esce comunque con codice 0**. L'applicazione non parte ma uno script che controlli solo l'exit code non se ne accorge. Rilevante quando si configurerà la CI.

## Correzioni documentali della review ancora aperte

Recepite in `PROJECT_STATE.md` e negli artefatti TASK-001. **Non** applicate ai file `docs/audit/*` di TASK-000, perché fuori dallo scope approvato: TD-06 (causalità Jackson non dimostrata), TD-07 (stacktrace non osservati), TD-14 (irriproducibilità online non dimostrata), percentuali di copertura da dichiarare come stime. Da pianificare come task documentale a sé.

## Vincolo
TASK-002 **non avviato**.
