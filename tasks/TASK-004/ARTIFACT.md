# TASK-004 — Artifact

**Completata il 2026-09-14.** Branch `task-004-archival-consistency`.
Suite: **126 test, verdi** contro PostgreSQL reale. Schema fermo a **`V3`**, nessuna migrazione.

## Che cosa è cambiato, osservabile

| Endpoint | Prima | Ora |
|---|---|---|
| `PUT /api/tasks/{id}/project`, task **dentro** un progetto archiviato, spostamento | `200` | **`409`** `ProblemDetail`, titolo «Task in an archived project cannot be modified» |
| `PUT /api/tasks/{id}/project`, stesso progetto archiviato | `200` | `200`, no-op, nessuna scrittura |
| `POST /api/projects/{id}/archive` concorrente a un altro | `200` + `200` | `200` + `409` |
| Tutto il resto | — | invariato. `TaskResponse` identico a TASK-003 |

Unico cambio di contratto: la prima riga. È una restrizione, approvata esplicitamente.

## Il protocollo, in codice

| Regola | Dove vive |
|---|---|
| **L0** — riga del task, esclusiva, prima di leggerne l'associazione | `TaskRepository.findByIdForUpdate` + primo passo di `TaskService.assignToProject` |
| **L1** — riga del progetto, esclusiva, per chi cambia lo stato | `ProjectRepository.findByIdForUpdate` + `ProjectService.lockForWrite` |
| **L2/L3/L4** — righe dei progetti, condivise, per chi legge lo stato per agire | `ProjectRepository.findByIdForShare` + `TaskService.requireProjectForDecision` |
| **L5** — `tasks` poi `projects`, id crescente, insieme deduplicato | `TreeSet` in `TaskService.assignToProject` |
| **L6** — le letture non bloccano | `TaskService.requireProject`, `ProjectService.findById` |
| Regola di dominio | `Task.assignTo`, tre rami nell'ordine: no-op, origine congelata, destinazione archiviata |

Sette file di produzione toccati, nessun DTO, nessuna migrazione, nessuna colonna.

## Acceptance criteria

Tutti soddisfatti. AC-18 **passa**, quindi TD-24 si chiude.

| Gruppo | AC | Dove |
|---|---|---|
| Comportamento | AC-1…AC-8 | `ProjectArchivalConsistencyTest`, `TaskProjectAssociationApiTest` |
| Protocollo e concorrenza | AC-9…AC-13, AC-19, AC-20 | `ProjectConcurrencyTest`, `ProjectLockProtocolTest` |
| Regressione | AC-14…AC-16 | suite invariata, `SchemaMigrationTest` non toccato |
| Qualità | AC-17, AC-18 | verifica per mutazione, sotto |

## Verifica per mutazione (AC-17)

Otto mutazioni, ciascuna toglie **una** cosa. Tutte producono rosso.

| Mutazione | Test che diventa rosso |
|---|---|
| `findByIdForShare` → `findById` (L2) | AC-9 |
| `findByIdForUpdate` → `findById` sui progetti (L1) | AC-10 |
| `findByIdForUpdate` → `findById` sul task (L0) | AC-19 |
| `TreeSet` → `LinkedHashSet` (L5) | AC-13/AC-20 |
| via la guardia sull'origine | AC-4/AC-5 |
| via il ramo no-op | AC-6 |
| `lockForWrite` reso pubblico e `@Transactional(readOnly)` | AC-18 |
| `join fetch` aggiunto alla query L0 | il test sul path lazy |

## Debito

| ID | Stato |
|---|---|
| **TD-25** | **CLOSED** |
| **TD-19** | **RESOLVED**, componente (a) ciclo di vita. La componente (b) è uscita verso TD-28 |
| **TD-24** | **CLOSED** — AC-18 passa e fallisce per mutazione |
| **TD-28** | **OPEN** — `PUT /api/projects/{id}` esposto alla sovrascrittura con dati stantii. Serve `ETag`/`If-Match` |
| **TD-29** | **OPEN, MINOR, subordinato a TD-07** — nessun contratto API normalizzato per gli errori infrastrutturali di locking. Non implica timeout né `503` |
| **TD-30** | **OPEN, MINOR, ristretto** — last-write-wins **su stato fresco** sulle riassegnazioni dello stesso task. Manca la rilevazione dell'intento stantio. Gemello di TD-28 |
| TD-26 | **OPEN**, ora parzialmente coperto: un test pinna che leggere `projectId` non inizializzi il proxy |

## Rilievi della review avversariale, corretti nella task

| Gravità | Rilievo | Correzione |
|---|---|---|
| **HIGH** | La correttezza della guardia dipendeva, non dichiarata, dal fatto che leggere `projectId` non inizializzi il proxy: se lo facesse, la regola deciderebbe su stato letto senza lock, e Hibernate non lo aggiornerebbe dopo | Aggiunto un test che pinna la proprietà e fallisce se una `join fetch` la togliesse |
| **MEDIUM** | Il no-op confrontava due id potenzialmente `null` come uguali: un target non persistito avrebbe saltato in silenzio entrambi i rifiuti | Guardia `target.getId() != null` |
| **MEDIUM** | `ProjectArchivalConsistencyTest` misurava zero scritture senza aver mai visto lo strumento muoversi | Aggiunto un controllo positivo nel test stesso |
| **MEDIUM** | Il Javadoc di `ProjectConcurrencyTest` affermava ancora «questi test falliscono sulla baseline» | Riscritto: erano rossi prima, sono verdi ora |
| LOW | `IMPLEMENTATION.md` prescriveva una `join fetch` nella query L0 — sarebbe fallita comunque: PostgreSQL rifiuta `FOR UPDATE` sul lato nullable di un outer join | Corretto nel documento e nel Javadoc del repository |

## Commit

| Hash | Contenuto |
|---|---|
| `f3730af` | `docs(task-004)` — scope approvato |
| `add5cd9` | `test(project)` — le due race, deliberatamente rosse |
| `430b001` | `test(project)` — il bypass per staleness, riprodotto |
| `934a954` | `docs(governance)` — Autonomous Project Mode |
| `6d0f23d` | `docs(task-004)` — L0, ordine globale, TD-30 ristretto |
| `736a22d` | `feat(project,task)` — implementazione |
| `77c2071` | `test(project,task)` — protocollo, regola, ragionamento |
