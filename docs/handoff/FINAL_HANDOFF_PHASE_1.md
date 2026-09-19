# FINAL HANDOFF — PHASE 1, Foundations

> ## ✅ ACCETTATA. Integrata in `master` il 2026-09-19.
>
> Scritto **per** la review umana; la review è avvenuta e il lavoro è stato accettato, insieme a
> PHASE 2. Il testo che segue è conservato **com'''era**, perché è ciò che è stato valutato.
>
> `master` `d5ff121` → **`0a35ac0`** in fast-forward, poi avanti fino a `6dc5989` con PHASE 2.
> Suite al checkpoint di questa fase: **158/158 verdi**. Nessun merge commit, nessuna storia
> riscritta.
>
> La decisione su **TD-31** (§5), che questo documento lasciava aperta, **resta aperta**: la
> review ha autorizzato i merge e nient'''altro.

**Per la review umana. Niente è stato integrato in `master`.**
*(Vero quando è stato scritto. Vedi il riquadro sopra.)*

- Integration branch: **`autonomous/phase-1-foundations`**
- `master`: fermo a **`d5ff121`**, intatto
- Suite: **158 test verdi** contro PostgreSQL reale, schema **`V4`**
- Nessun failure aperto, nessun push, nessun remote configurato

---

## 1. Che cosa è stato costruito

Quattro task in modalità autonoma, dopo che la modalità stessa è stata scritta.

Il Company OS aveva un dominio (`Project`), una relazione (`Task` → `Project`) e una persistenza
riproducibile. Adesso ha anche: **una regola di coerenza dell'archiviazione con un protocollo di
concorrenza che la rende vera**, **un contratto di errore unico con identificatori stabili**,
**una storia di test delle migrazioni che copre da sola quelle future**, e **un secondo registro
di dominio** allo stesso standard del primo.

La cosa più importante non è nell'elenco: tre difetti di concorrenza che il sistema **prometteva**
di non avere sono stati riprodotti con test deterministici prima di essere corretti, e uno di
essi non era stato previsto da nessun documento.

## 2. Task completate

| Task | Cosa chiude | Commit di chiusura |
|---|---|---|
| **TASK-004** — Archival Consistency & Project Lock Protocol | TD-19 (ciclo di vita), TD-24, TD-25 | `b1274d1` |
| **TASK-005** — Uniform Error Contract | TD-07, TD-20, TD-21, TD-27, TD-29 | `d3faeae` |
| **TASK-006** — Migration Test Coverage | TD-22, TD-23 | `d72cf19` |
| **TASK-007** — Agent Registry | nulla; **apre TD-31** | (questo commit) |

Prima, in `master`: TASK-001/001A (persistenza), TASK-002 (Project Registry), TASK-003
(relazione `Task` → `Project`).

## 3. Architettura risultante

```
API  ──  un solo contratto di errore (ApiExceptionHandler + ApiProblem)
         ogni errore: type urn:ai-company-os:problem:<slug>, title, status, detail

Dominio
  Project   ciclo di vita ACTIVE/ARCHIVED (enum + CHECK), si archivia, non si cancella
  Agent     ciclo di vita su boolean active, si disattiva, non si cancella   ← TD-31
  Task      status/priority stringhe libere, appartiene a 0 o 1 progetto

Relazioni
  Task → Project     unidirezionale, FK senza ON DELETE, project_id nullable
  Agent              nessuna relazione: le domande di dominio non sono state poste

Concorrenza — protocollo L0–L7, universale (ADR-006 §4)
  L0  riga del task esclusiva, prima di leggerne l'associazione
  L1  riga del progetto/agente esclusiva, per chi cambia lo stato
  L2  righe dei progetti condivise, per chi legge lo stato per agire
  L5  ordine globale: tasks per id crescente, poi projects per id crescente
  L6  le letture non bloccano
  L7  clausola di chiusura: nessuna eccezione «tanto questo caso è innocuo»

Persistenza — PostgreSQL 17, schema di proprietà di Flyway (V1..V4), Hibernate in validate,
              seed di sviluppo in uno stream separato
```

## 4. ADR introdotte

| ADR | Contenuto | Stato |
|---|---|---|
| **ADR-006** | Coerenza dell'archiviazione **derivata** (nessuna scrittura sui figli, `restore` inverso per costruzione), congelamento in scrittura con letture aperte, protocollo di lock L0–L7, nessuna migrazione | Accettata, implementata |
| **ADR-007** | Un contratto di errore per tutta l'API, `type` stabile ed enumerato. **Supera ADR-004 §6 e ADR-005 §8** | Accettata, implementata |
| **ADR-008** | Agent Registry allo standard di ADR-004, con una divergenza dichiarata sul ciclo di vita | Accettata, implementata |

## 5. Debito aperto

### Richiede una decisione umana

| ID | Contenuto |
|---|---|
| **TD-31** | `Agent` usa un booleano, `Project` un enum chiuso. Unificarli richiede di **eliminare una colonna**: migrazione irreversibile, che il charter mette dietro un gate umano. Nel frattempo il contratto pubblico è già uniforme, perché `status` è derivato |
| **TD-14** | Nessuna CI. 158 test, invarianti di concorrenza e guardie verificate per mutazione, e nulla che li esegua. Una CI reale richiede un remote, che è un hard stop |

### Alto valore, autonomo

| ID | Contenuto |
|---|---|
| **TD-28 / TD-30** | Manca la **rilevazione** dell'intento stantio, su `Project` e su `Task`. I lock serializzano ma non rilevano: il primo chiamante non sa di essere stato preceduto. Si chiudono insieme, con `ETag`/`If-Match`, non con un lock |
| **TD-04** | Nessuna autenticazione. Tre registri con scritture |
| **TD-11** | Policy CORS non decisa. **Superficie ridotta** da TASK-007: il `@CrossOrigin` senza origine è stato rimosso da `AgentController` — vedi §7 |
| **TD-26** | Il path lazy non è esercitato fuori transazione. Parzialmente coperto |

### Minore
TD-08, TD-12, TD-13, TD-15, TD-17, TD-18. Rilievi R3, R5, R6, R7 della review TASK-001.
Debito documentale: `docs/RUNNING.md` non documenta `/api/projects`, `/api/agents`, né gli
endpoint di TASK-003.

## 6. Rischi conosciuti

| Rischio | Stato |
|---|---|
| **Nessuna autenticazione su nessun endpoint** | Reale e cresciuto: tre registri con scritture. TD-04 |
| **Nessuna CI** | Tutto è stato eseguito a mano su questa macchina. Nessuno rieseguirà la suite se non lo si chiede |
| **Test di concorrenza e tempo** | Ogni ordinamento è a latch, ma un'attesa limitata esiste dove un lock tenuto è osservabile solo come «l'altro thread non è arrivato». Su una macchina molto carica può diventare instabile |
| **`V4` fallisce su nomi di agente duplicati** | Deliberato: l'alternativa sarebbe scegliere in silenzio quale riga sopravvive |
| **Timestamp degli agenti preesistenti** | Valgono il momento della migrazione. Approssimazione dichiarata, non invenzione |
| **Il protocollo di lock è senza deadlock *perché* `archive` non scrive i figli** | Se una task futura gli desse effetti sui figli, l'ordine globale va rivisto. Scritto in ADR-006 §4, non assunto |

## 7. Una cosa fuori scope, dichiarata

`AgentController` aveva un `@CrossOrigin` senza origine. Riscrivendolo l'ho perso, e ho deciso di
**lasciarlo rimosso**: era già TD-11, ma quel controller adesso non legge soltanto, e riportarlo
avrebbe aperto creazione, modifica e le due transizioni a qualunque origine come effetto
collaterale di una task che non riguarda CORS.

Non chiude TD-11. È la scelta di non allargare una porta aperta passandoci davanti. Se la
preferisci com'era, è una riga.

## 8. Test

```
158 test, tutti verdi. ./mvnw -B clean test → BUILD SUCCESS
```

Contro PostgreSQL reale via Testcontainers, tranne i test unitari di dominio e quelli
strutturali. In particolare:

- **9 test di concorrenza** a due thread con transazioni controllate a mano, sincronizzati con
  latch e mai con sleep;
- **21 mutazioni verificate** nelle quattro task: per ciascuna guardia portante si è dimostrato
  che toglierla rende rosso un test. Un test di concorrenza che non è mai stato visto fallire
  non dimostra niente, ed è il criterio su cui la review di TASK-003 aveva chiuso M-1 e M-2.

## 9. Commit e branch

```
master                             d5ff121   intatto, gate umano
└── autonomous/phase-1-foundations           integration branch
```

Branch di lavoro conservati, tutti integrati in **fast-forward**, storia lineare, mai riscritta:
`task-004-archival-consistency`, `task-005-uniform-error-contract`,
`task-006-migration-test-coverage`, `task-007-agent-registry`.

## 10. Differenza rispetto a `master`

| | `master` | `autonomous/phase-1-foundations` |
|---|---|---|
| Test | 109 | **158** |
| Schema | `V3` | **`V4`** |
| Registri di dominio | 1 (`Project`) | **2** (`Project`, `Agent`) |
| Contratto di errore | tre dialetti | **uno**, con `type` stabili |
| Concorrenza | nessun controllo | **protocollo L0–L7** |
| ADR | 5 | **8** |

**Cambi di contratto osservabili** — nessuno stato HTTP cambia, cambiano i corpi:

1. `PUT /api/tasks/{id}/project` → `409` se **sposta** un task fuori da un progetto archiviato;
2. i corpi di errore di `/api/agents` e delle risposte preesistenti di `/api/tasks` diventano
   `ProblemDetail`;
3. ogni errore porta `type`;
4. `AgentResponse` guadagna `status`, `createdAt`, `updatedAt` (additivo).

## 11. Come eseguire e verificare

```bash
docker compose up -d
```

```bash
cd backend && ./mvnw -B clean test
```

Verifica manuale, con l'applicazione avviata (`./mvnw spring-boot:run`):

```bash
curl -s -X POST localhost:8080/api/agents -H 'Content-Type: application/json' -d '{}' | jq
```

Deve rispondere `400` con `type: urn:ai-company-os:problem:validation-failed` e l'elenco dei
campi — il contratto unico.

```bash
curl -s localhost:8080/api/projects/abc/tasks | jq .type
```

Deve rispondere `urn:ai-company-os:problem:invalid-parameter`: stessa rotta, stesso dialetto di
un identificatore che non esiste. Era TD-27.

## 12. Dove consiglio una review umana approfondita

1. **ADR-006 §4, il protocollo di lock.** È la decisione più conseguente della fase e vincola
   ogni scrittura futura. In particolare la clausola L7 e il fatto che l'assenza di deadlock sia
   una *conseguenza* della consistenza derivata, non una proprietà indipendente.
2. **ADR-006 §8 e la riformulazione di TD-30.** Ho scritto un limite, un test ha dimostrato che
   la mia descrizione era falsa, e l'ho corretta. Vale la pena controllare che la versione nuova
   sia vera.
3. **ADR-007 §2, i `type` come contratto.** È una promessa pubblica: gli slug non si cambiano più
   senza rompere qualcuno.
4. **TD-31 e ADR-008 §2.** È il punto dove mi sono fermato davanti a un hard stop del charter.
   Se ritieni che eliminare `active` sia accettabile, la migrazione è piccola e la decisione è
   tua.
5. **`ApiExceptionHandler`, il catch-all.** Restituisce un `detail` fisso e logga tutto. Vale la
   pena controllare che non nasconda nulla che avresti voluto vedere in risposta.
6. **La rimozione di `@CrossOrigin`** (§7).

---

**Il lavoro non è accettato finché non lo accetti tu.** Il merge in `master` è quel gesto, e
nessun agente lo esegue.
