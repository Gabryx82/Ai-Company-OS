# TASK-008 — Optimistic concurrency nel contratto HTTP

> **Autonomous Project Mode.** Scope scelto dall'agente (`AUTONOMOUS_CHARTER.md` §2), **forma
> fissata da una precisazione umana del 2026-09-16**. Vedi ADR-009 §0 per chi ha deciso che cosa.

## Baseline
Branch `task-008-optimistic-concurrency` da `autonomous/phase-2-assignment` (`f2e9b9b`).
Suite di partenza: **158 verdi**. Schema a `V4`.

## Perché adesso
`AUTONOMOUS_LOOP.md` §4, **livello 3** — debito che il passo successivo tocca, non debito in
generale. TASK-009 aggiunge un secondo asse di assegnazione alla riga `tasks`. Costruire la
rilevazione dell'intento stantio dopo significherebbe farla su due endpoint invece che su uno, e
nel frattempo pubblicare un contratto che dovrà cambiare.

## Il problema in una frase
I lock **serializzano ma non rilevano**. Due scritture concorrenti producono entrambe uno stato
legale, e il primo chiamante non sa di essere stato sostituito.

## Decisioni
In `docs/adr/ADR-009-optimistic-concurrency-http-contract.md`.

| # | Decisione |
|---|---|
| 1 | Due meccanismi distinti: **L0/L1/L2 = consistenza interna**, **`ETag`/`If-Match` = intento stantio del client**. Nessuno sostituisce l'altro, e ciascuno rende sano l'altro |
| 2 | **`@Version` è un contatore persistente, non il rilevatore.** Dopo l'attesa su `PESSIMISTIC_WRITE` l'entità è caricata **già alla versione nuova**, quindi `OptimisticLockException` non arriva mai. Il rilevatore è il confronto esplicito fra l'`If-Match` del client e la versione letta sotto lock |
| 3 | Protocollo **P0–P4**. `If-Match` **obbligatorio** su ogni mutazione di risorsa esistente; valutato **dentro la transazione, dopo il lock, prima delle guardie**; e **prima di ogni no-op idempotente** |
| 4 | `428` se assente, `412` se stantio, `400` se illeggibile o `*`. Tre `ApiProblem` nuovi, dentro ADR-007 |
| 5 | Percorso canonico dell'ETag: **`GET /api/{risorsa}/{id}`**, che per i task **non esiste e viene introdotto**. ETag restituito anche da creazioni e mutazioni |
| 6 | `V5` **additiva**: `version BIGINT NOT NULL DEFAULT 0` su `tasks`, `projects`, `agents` |
| 7 | Adottato da **tutte e tre** le risorse, non solo dalle due rotte del debito. Una precondizione con buchi non è una precondizione (L7 è il precedente) |
| 8 | **P4**: ogni write path futuro eredita il protocollo. **TASK-009 incluso** — `PUT /api/tasks/{id}/agent` nasce con la precondizione |

## Invarianti

| ID | Invariante | Come si verifica |
|---|---|---|
| **I-1** | Una mutazione di risorsa esistente **senza** `If-Match` non muta niente → `428` | Test di API su tutte e sette le rotte, con rilettura che prova che lo stato non è cambiato |
| **I-2** | Una mutazione con `If-Match` **stantio** non muta niente → `412` | Idem, con rilettura |
| **I-3** | **La precondizione è valutata prima del no-op idempotente**: `PUT /api/tasks/{id}/project` verso lo stesso progetto, con ETag stantio, è `412` e non `200` | Test di API dedicato |
| **I-4** | **La precondizione è valutata sotto lock**: due riassegnazioni concorrenti con lo **stesso** ETag valido producono esattamente un `200` e un `412`, mai due `200` | Test a due thread, latch, nessuno `sleep` |
| **I-5** | La versione di una riga cambia **solo** quando quella riga viene scritta: assegnare un task a un progetto **non** incrementa la versione del progetto | Test su ETag del progetto prima e dopo |
| **I-6** | Una mutazione che risulta un no-op **non** incrementa la versione | Test: due `PUT` identici con l'ETag giusto riescono entrambi |
| **I-7** | L'ETag restituito da una mutazione è **identico** a quello che una `GET` successiva restituisce | Test su ogni rotta di mutazione |
| **I-8** | `V5` è additiva: nessuna riga persa, nessuna tabella eliminata, le righe preesistenti arrivano a `version = 0` | Il test sulle coppie consecutive di TASK-006, **senza aggiungere un caso**, più un test di upgrade su database popolato |
| **I-9** | `If-Match: *` è rifiutato con `400`, non accettato come jolly | Test di API |
| **I-10** | Ogni `type` nuovo è enumerato | `ApiProblemCoverageTest`, che fallisce da solo se manca una mappatura |
| **I-11** | Il protocollo di lock di ADR-006 non è indebolito: L0, L1, L2, L5 restano come sono | I test di concorrenza esistenti restano verdi senza modifiche |

## In scope
1. `V5__add_row_version.sql` — `version` su `tasks`, `projects`, `agents`.
2. `@Version` sulle tre entità.
3. `com.aicompany.backend.api.Precondition` — interpretazione dell'`If-Match`, verifica sotto
   lock, e l'entity-tag.
4. Tre `ApiProblem` nuovi e i loro handler.
5. `GET /api/tasks/{id}` — nuovo.
6. `ETag` sulle risposte di `GET /{id}`, delle creazioni e delle mutazioni.
7. `If-Match` obbligatorio sulle sette rotte di mutazione elencate in ADR-009 §4.
8. Firme dei service: la precondizione entra come parametro.
9. Test: API, concorrenza, upgrade `V4 → V5` su database popolato, copertura dei `type`.
10. Artefatti, ADR-009, `PROJECT_STATE.md`, `PHASE_2_PLAN.md`.

## Fuori scope
- **Relazione `Task` → `Agent`** → TASK-009.
- Esporre `version` nei corpi JSON → ADR-009 §7.
- ETag sui listati → **TD-33**.
- Versione dello schema di rappresentazione nel token → **TD-32**.
- `If-Match` condizionale sulle `GET` (`If-None-Match`, `304`): è cache, non concorrenza.
- TD-14 (CI), TD-04 (auth), TD-11 (CORS), TD-31 (hard stop umano).
- Paginazione, frontend, servizio Python/AI.

## Criteri di accettazione
- **AC-1** Le sette rotte di mutazione rispondono `428` senza `If-Match`, e non mutano niente.
- **AC-2** Rispondono `412` con `If-Match` stantio, e non mutano niente.
- **AC-3** `PUT /api/tasks/{id}/project` verso lo **stesso** progetto con ETag stantio → `412`.
- **AC-4** Due riassegnazioni concorrenti con lo stesso ETag → un `200` e un `412`.
- **AC-5** `GET /api/tasks/{id}` esiste, porta l'ETag, e risponde `404` con `task-not-found`.
- **AC-6** L'ETag di una risposta di mutazione coincide con quello della `GET` successiva.
- **AC-7** Assegnare un task non cambia l'ETag del progetto.
- **AC-8** `If-Match: *` → `400`; una lista di entity-tag che contiene quello corretto → `200`.
- **AC-9** Senza `If-Match` su una risorsa **inesistente** → `428` (P0 precede il database);
  con `If-Match` valido su una risorsa inesistente → `404`.
- **AC-10** `V5` applicata a un database popolato non perde righe e porta le esistenti a `0`.
- **AC-11** Suite completa verde; i test di concorrenza di TASK-004 e TASK-007 invariati.
- **AC-12** Verifica per mutazione: togliere il confronto della precondizione rende rosso almeno
  un test; spostarlo **prima** del lock rende rosso il test di concorrenza I-4; spostarlo **dopo**
  la scorciatoia idempotente rende rosso I-3.
