# TASK-002 — HANDOFF → Codex (review differenziale)

## Cosa è stato fatto

Primo dominio reale del Company OS: **`Project`**, tabella PostgreSQL creata da `V2`, con
CRUD, ciclo di vita esplicito e archiviazione al posto della cancellazione fisica.

Vincolo di scope rispettato: il progetto è **pronto a diventare** il contenitore, non lo è
ancora. `V2` crea una tabella e due indici, non tocca `agents` né `tasks`, non aggiunge
chiavi esterne.

| Elemento | Scelta |
|---|---|
| Entità | `Project(id, name, description, status, createdAt, updatedAt)`, `BIGINT` identity come le altre |
| Stato | Enum chiuso `ACTIVE` / `ARCHIVED`, imposto anche da `CHECK` nel database |
| Cancellazione | Nessun mapping `DELETE` → `405`. Si archivia |
| Transizioni | Sull'entità, non nel service. Transizione illegale → `409` |
| Unicità nome | Indice unico funzionale su `lower(name)`; il service traduce anche la violazione concorrente in `409` |
| Errori | `ProblemDetail`, advice **limitato a `ProjectController`** |

API: `POST /api/projects`, `GET /api/projects[?status=]`, `GET /{id}`, `PUT /{id}`,
`POST /{id}/archive`, `POST /{id}/restore`.

Motivazioni complete in `docs/adr/ADR-004-project-registry-and-archival-lifecycle.md`.

## Effetto collaterale obbligato sui test esistenti

La fixture `db/fixture/v2/V2__task_001a_probe.sql` di TASK-001A rappresentava «la prossima
migrazione di schema». Con una `V2` reale, Flyway avrebbe trovato due migrazioni con la
stessa versione.

Rinominata in `db/fixture/next/V900__probe_next_schema_migration.sql`. Il numero è vincolato
da entrambi i lati: **sopra** la testa di `db/migration`, **sotto** `1000`, altrimenti la
fixture legacy non riproduce più il fallimento R1. Il vincolo è scritto nella fixture.

`MigrationStreamTest` ora legge le versioni applicate da Flyway invece di elencarle a mano,
così la prossima migrazione non lo rompe. `SchemaMigrationTest` e `DevSeedMigrationTest`
mantengono l'elenco esplicito `("1", "2")` di proposito: lì il punto è che nessuna versione
di seed finisca nella storia di schema, quindi l'asserzione deve essere rigida e chi aggiunge
una `V3` deve dichiararla.

## Stato Git

| Voce | Valore |
|---|---|
| Branch | `task-002-project-registry-foundation`, creato da `master` |
| HEAD all'avvio | `32174a1` |
| Working tree | pulito |
| Remote | **nessuno configurato** — nessun push |
| `master` | **intatto**, nessun merge |
| Storia | non riscritta |

| Hash | Messaggio |
|---|---|
| `4e4fa64` | `feat(project): add the Project domain and its registry API` |
| `9fb3029` | `test(project): cover the registry contract and unblock the schema stream` |
| HEAD | `docs(task-002): record ADR-004 and the project registry artefacts` |

## Verifiche eseguite

```
./mvnw -B clean test  →  Tests run: 66, Failures: 0, Errors: 0, Skipped: 0  —  BUILD SUCCESS
```

26 test prima, 66 adesso. I 40 nuovi sono su `Project`: 22 di contratto HTTP, 9 di
persistenza su PostgreSQL reale, 7 di dominio puro.

`V2` applicata al database di sviluppo **reale** (volume `aicompany_postgres_data`, non
cancellato): storia `1, 2`, seed e task preesistenti intatti. Smoke test HTTP completo sui
percorsi `201` / `400` / `404` / `405` / `409` e sulle invarianti di TASK-001. Righe di
smoke test rimosse al termine. Dettaglio in `ARTIFACT.md`.

## Punti su cui è più utile un parere indipendente

1. **`archive` non idempotente.** Riarchiviare risponde `409`. Un no-op silenzioso sarebbe
   più comodo per i client, ma nasconde un doppio invio e rende la macchina a stati non
   verificabile. Concordi, o la comodità vale di più?
2. **`GET /api/projects` senza filtro restituisce anche gli archiviati.** Ho evitato il
   filtro implicito perché è il tipo di comportamento che si scopre mesi dopo, quando un
   conteggio non torna. Il rovescio è che il caso d'uso più frequente — «i progetti su cui
   sto lavorando» — richiede sempre il parametro.
3. **Advice limitato a un controller.** Per un periodo l'API ha due forme di errore. È il
   prezzo per non cambiare il contratto di `agents` e `tasks` fuori scope (TD-07), ma va
   confermato che la disomogeneità sia accettabile fino a quando TD-07 non viene affrontato.
4. **Timestamp dall'orologio dell'applicazione** (`@PrePersist`) invece che da `DEFAULT
   now()`. Con più istanze e orologi non sincronizzati l'ordinamento per `created_at` può
   essere leggermente incoerente. Accettabile ora, o meglio spostarli subito sul database?
5. **Nessuno slug pubblico.** Il nome è oggi la chiave naturale, quindi rinominare un
   progetto ne cambia l'identità leggibile. Introdurre uno slug immutabile dopo che
   esisteranno riferimenti costa di più. Vale la pena anticiparlo?
6. **`VARCHAR(120)` per il nome e `VARCHAR(2000)` per la descrizione.** Numeri scelti, non
   derivati da un requisito.
7. **Nessuna paginazione su `GET /api/projects`.** Aggiungerla dopo cambia il contratto. È
   un debito consapevole, ma è il momento giusto per contrarlo?

## Rilievi ancora aperti, non toccati da TASK-002

Della review TASK-001: **R3** (`.env` non letto dal processo Maven), **R5**
(`server.address` non vincolato a loopback), **R6** (tag immagine mobile, nomi Compose
fissi), **R7** (`.gitignore` non copre `.env.*`), più il debito devtools sull'exit code di
`spring-boot:run`. Aperto anche il LOW su `GET /api/tasks/{id}`, assente benché `POST`
emetta un `Location` che punta lì — `/api/projects` non ha lo stesso problema.

Debito di progetto: TD-04, TD-07, TD-08, TD-11, TD-12/TD-13, TD-14, TD-15, TD-17/TD-18.
Correzioni documentali ai file `docs/audit/*` di TASK-000: ancora da applicare.

## Vincolo

TASK-003 **non avviata**. Nessun merge, push, remote o riscrittura di storia.
