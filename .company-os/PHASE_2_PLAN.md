# PHASE 2 — Assignment

> Piano persistente della fase. La fonte primaria dello stato resta `PROJECT_STATE.md`:
> questo documento dice **dove va la fase e perché**, non dove è arrivata.

- **Integration branch**: `autonomous/phase-2-assignment`, creato da `autonomous/phase-1-foundations` (`0a35ac0`).
- **`master`**: fermo a `d5ff121`. Non si tocca, non si mergia. Gate umano finale.
- **Baseline verificata il 2026-09-15**: `./mvnw -B clean test` → **158 test, 0 failure, BUILD SUCCESS**,
  schema `V4`, nessun remote configurato. Corrisponde a `PROJECT_STATE.md`.

## 1. Obiettivo

> **Il Company OS sa dire chi lavora su che cosa, e due client non possono sovrascriversi in
> silenzio mentre lo dicono.**

PHASE 1 ha costruito i contenitori: due registri con ciclo di vita, una relazione, un contratto
di errore, un protocollo di concorrenza che rende vere le regole. Quello che manca non è un
altro contenitore — è il **collegamento** fra il lavoro e chi lo esegue, che è il presupposto di
tutto il livello di orchestrazione della target architecture (Planner, Subagents, Model Gateway:
nessuno dei tre significa qualcosa finché un task non sa a quale agente appartiene).

La seconda metà dell'obiettivo non è un'aggiunta cosmetica: è la condizione per cui la prima non
peggiora il sistema. Vedi §2, livello 3.

## 2. Perché questo scope e non un altro

La scelta segue `AUTONOMOUS_LOOP.md` §4, livello per livello. Non si scende finché quello sopra
non è vuoto.

| Livello | Contenuto | Esito |
|---|---|---|
| **1 — Correttezza e invarianti già promessi** | Qualcosa che il repository afferma e che non è vero | **Vuoto.** 158/158 verdi, nessun failure aperto. Due affermazioni stantie trovate in `PROJECT_STATE.md` (§5) sono correzioni di una riga, non una task |
| **2 — Blocker architetturali** | — | **Vuoto** |
| **3 — Debito pericoloso per il passo successivo** | **TD-28 / TD-30** | **TASK-008**, vedi sotto |
| **4 — Dipendenze della target architecture** | Relazione **`Task` → `Agent`** | **TASK-009** |
| **5 — Valore strutturale** | Coerenza del ciclo di vita dell'agente verso il lavoro assegnato; vocabolario chiuso di `Task.status` | **TASK-010**, da definire quando ci si arriva |
| **6 — Cleanup e documentazione** | Collisione di identificatori nel registro del debito; `docs/RUNNING.md` | **TASK-011** |

**Perché TD-28/TD-30 viene prima della relazione, e non dopo.** Non perché il debito vada chiuso
in generale — il charter lo vieta esplicitamente — ma perché è **quello che il passo successivo
tocca**. Oggi `PUT /api/tasks/{id}/project` è l'unico modo di mutare un task esistente, e TD-30 è
un limite su un campo solo. TASK-009 aggiunge un secondo asse di assegnazione alla stessa riga:
un secondo percorso di scrittura, e la stessa assenza di rilevazione dell'intento stantio
moltiplicata. Aggiungere il campo prima significa costruire il meccanismo su due endpoint invece
che su uno, e nel frattempo pubblicare un contratto che dovrà cambiare. ADR-006 §8 dice già con
quale meccanismo si chiude: concorrenza ottimistica **nel contratto HTTP**, non un lock.

**Cosa resta fuori, deliberatamente.**

| Candidato | Perché non in PHASE 2 |
|---|---|
| **TD-14 — CI** | Una CI reale richiede un remote, e un push è **hard stop #4** del charter |
| **TD-31 — unificare il ciclo di vita di `Agent`** | Richiede di eliminare una colonna: **hard stop #3**. La decisione è umana e sta in `FINAL_HANDOFF.md` §5 |
| **TD-04 — autenticazione** | Reale e in crescita, ma non è ciò che il passo successivo tocca, e introdurla cambierebbe ogni test di API della fase mentre la fase è in corso. Primo candidato di PHASE 3 |
| **TD-08 — `MasterOrchestrator`** | Da sostituire, non da evolvere, e la sostituzione ha senso solo **dopo** che un task sa a quale agente appartiene. Diventa lavoro reale quando TASK-009 è chiusa |

## 3. Task pianificate

### TASK-008 — Optimistic concurrency nel contratto HTTP

**Livello 3. Chiude TD-28 e TD-30.**

Il problema, nelle parole che il repository usa già: i lock **serializzano ma non rilevano**. Due
scritture concorrenti sulla stessa risorsa producono entrambe uno stato legale, e il primo
chiamante non viene informato di essere stato preceduto.

**Deciso in `docs/adr/ADR-009-optimistic-concurrency-http-contract.md`**, la cui forma è stata
fissata da una precisazione umana del 2026-09-16 (ADR-009 §0 traccia chi ha deciso che cosa).
In sintesi:

- **due meccanismi distinti e complementari** — L0/L1/L2 è consistenza interna, `ETag`/`If-Match`
  è intento stantio del client; il lock rende atomico il confronto, il confronto rende visibile
  la staleness;
- **`@Version` è un contatore persistente, non il rilevatore.** Dopo l'attesa su
  `PESSIMISTIC_WRITE` l'entità è caricata **già alla versione nuova** — sotto `READ COMMITTED` un
  `FOR UPDATE` sbloccato rilegge l'ultima versione committata — quindi `OptimisticLockException`
  non arriva mai. Affidarsi a essa sarebbe un `412` che non scatta;
- protocollo **P0–P4**: `If-Match` obbligatorio su ogni mutazione di risorsa esistente (`428` se
  assente, `412` se stantio, `400` se illeggibile o `*`), valutato **dentro la transazione, dopo
  il lock, prima delle guardie e prima di ogni no-op idempotente**;
- percorso canonico dell'ETag: **`GET /api/{risorsa}/{id}`**, con `GET /api/tasks/{id}` introdotto
  perché non esisteva; ETag anche su creazioni e mutazioni;
- `V5` additiva, e il protocollo adottato da **tutte e tre** le risorse, non solo dalle due rotte
  del debito: una precondizione con buchi non è una precondizione.

ADR-006 §4 aveva **scartato `@Version` su `Project`** — ma come sostituto del protocollo di lock
per TD-25, non come rilevatore di intento stantio, che è quello che ADR-006 §8 indica esso stesso.
ADR-009 §2 lo dice per esteso, perché letto di sfuggita sembra una contraddizione.

### TASK-009 — Task → Agent assignment

**Livello 4.**

Come TASK-003 fece per `Project`, e con le stesse tre domande di dominio da porre **prima** del
codice: un task può essere assegnato a un agente disattivato? Un task in un progetto archiviato
può cambiare agente — ADR-006 §2 dice già di sì alla forma della risposta, «qualunque scrittura
futura su quel task → `409`, per costruzione»? Disattivare un agente fa qualcosa ai suoi task, o
la coerenza è **derivata** come in ADR-006 §1?

Nasce con la precondizione di TASK-008 già addosso: **P4** di ADR-009 la rende ereditaria, e
`PUT /api/tasks/{id}/agent` non la acquista dopo.

Conseguenza nota sul protocollo di lock: l'ordine globale L5 (`tasks` → `projects`) acquista una
terza classe di righe, e l'aciclicità va **ridimostrata**, non assunta. ADR-006 §4 lo dice a
chiare lettere: «se un percorso futuro dovesse bloccare un task tenendo già un lock su un
progetto, l'ordine globale va rivisto, non aggirato».

### TASK-010 — da definire

**Livello 5.** Si sceglie quando ci si arriva, con i criteri di `AUTONOMOUS_LOOP.md` §4, e non
prima: TASK-009 può derivare interamente la coerenza del ciclo di vita dell'agente, e in quel
caso questa task ha un contenuto diverso da quello che oggi sembrerebbe ovvio.

### TASK-011 — Registro del debito e documentazione

**Livello 6.**

`docs/audit/TECHNICAL_DEBT.md` (TASK-000) e la numerazione viva in `PROJECT_STATE.md` usano lo
**stesso spazio di identificatori per debiti diversi**: `TD-14` è «build non riproducibile
offline» nell'uno e «nessuna CI» nell'altro; `TD-19`, `TD-20`, `TD-21` e `TD-22` divergono allo
stesso modo. È un rischio di tracciabilità reale — una task futura può chiudere il debito
sbagliato credendo di chiudere quello giusto — ed è il tipo di cosa che il charter chiede di
**scrivere** invece di sistemare passandoci davanti.

Insieme: `docs/RUNNING.md` non documenta `/api/projects`, `/api/agents` né gli endpoint di
TASK-003.

## 4. Criterio di chiusura della fase

PHASE 2 è completa quando tutte e tre sono vere:

1. un task può essere assegnato a un agente, con le regole di dominio dichiarate in un'ADR e rese
   vere da test, non da convenzione;
2. ogni percorso di scrittura che l'ADR di TASK-008 dichiara coperto **rileva** l'intento
   stantio, e la rilevazione è verificata per mutazione — togliere il confronto rende rosso un
   test;
3. `PROJECT_STATE.md` e un `FINAL_HANDOFF` aggiornato bastano a una sessione fredda, e `master` è
   ancora a `d5ff121`.

## 5. Correzioni immediate, non task

Trovate verificando la baseline, applicate insieme a questo piano:

- `PROJECT_STATE.md` § «Failure aperti» diceva «126/126 verdi» con la suite a 158;
- `PROJECT_STATE.md` § TD-14 diceva «con 136 test».

Numeri stantii in due punti mentre l'intestazione ne dichiarava un terzo. Nessun effetto sul
codice, ma è la fonte primaria dello stato: un numero sbagliato lì è un'affermazione falsa del
repository su se stesso.
