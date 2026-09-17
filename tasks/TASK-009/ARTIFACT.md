# TASK-009 — Artefatto

**Assegnazione `Task` → `Agent`.** Risolve **TD-13** (numerazione dell'audit), apre **TD-34** e
**TD-35**. Suite **179 → 201**, schema **`V5` → `V6`**.

Da oggi il Company OS sa dire **chi lavora su che cosa**.

## 1. Le tre domande di dominio, e dove il dominio ha divergito

Poste prima del codice. **Nessuna risposta è stata copiata da `Task` → `Project`**: due coincidono
per ragioni che valgono qui, una no.

| # | Domanda | Risposta | Rapporto con il progetto |
|---|---|---|---|
| **D1** | Assegnare a un agente disattivato? | **`409`** | Stesso esito, **argomento diverso**. Là è contenimento — un contenitore messo via non deve crescere. Qui è responsabilità: un'obbligazione che nessuno può assolvere, e senza semantica di coda «assegnato a chi è spento» è indistinguibile da «dimenticato» |
| **D2** | Cambiare agente a un task in un progetto archiviato? | **`409`**, con il `type` esistente | Coincide. È la **prima verifica** della frase generale di ADR-006 §2, e regge — ma vedi §3: «per costruzione» non è automatico |
| **D3** | Disattivare un agente fa qualcosa ai suoi task? | **Nessuna scrittura sui figli, e la regola derivata è l'OPPOSTA** | **Qui il dominio diverge** |

**D3 per esteso**, perché è la decisione della task. La metà che si applica identica è il non
materializzare: `deactivate` e `activate` scrivono una riga sola, la propria, e il `restore` è
l'inverso esatto per costruzione. La metà che **non** si applica è *quale* regola si deriva:

> Il momento in cui si disattiva un agente è esattamente il momento in cui bisogna poter
> riassegnare il suo lavoro. Congelare i suoi task lo intrappolerebbe con un lavoratore che non può
> eseguirlo, e l'unica via d'uscita sarebbe riattivare l'agente — cioè annullare la ragione per cui
> lo si è spento.

Quindi un task il cui agente è inattivo **resta pienamente scrivibile**. Esiste, e deve esistere,
uno stato legale in cui un task punta a un agente spento.

**D3bis** — far *fallire* `deactivate` finché ha lavoro assegnato — è l'alternativa seria, scartata
da due ragioni che puntano nella stessa direzione: introdurrebbe l'arco `agents → tasks` e
**chiuderebbe un ciclo nel lock graph**, e impedirebbe di togliere dal servizio un agente guasto
proprio quando è guasto.

## 2. Il lock graph, ridimostrato

Non «aggiungere `agents` in fondo a L5». ADR-010 §3 rifà la prova su **tutti** i percorsi di
scrittura, nuovi e preesistenti.

**L5′**: `tasks` → `projects` → `agents`, id crescente e deduplicato dentro ogni classe.

Natura degli archi, dichiarata perché una convenzione non scritta non è una convenzione:

| Arco | Natura |
|---|---|
| `tasks → projects` | **Imposto dai dati**: quali progetti servano lo dice la riga del task |
| `tasks → agents` | Imposto per l'origine, **scelto** per la destinazione. L'inverso creerebbe `agents → tasks` |
| `projects → agents` | **Convenzione.** Nessun dato la impone; vale perché l'ADR la dichiara |

**Aciclicità.** Tre archi, ordinamento topologico `tasks, projects, agents`. La verifica che conta
non è che il grafo sia un DAG — si vede — ma che **gli archi mancanti manchino davvero**:

| Arco assente | Perché |
|---|---|
| `projects → tasks` | `archive`/`restore` non toccano righe di `tasks` — ADR-006 §1 |
| `agents → tasks` | Il ciclo di vita dell'agente non dipende dai suoi task — **D3**. È l'arco che D3bis avrebbe introdotto |
| `agents → projects` | I due registri non si conoscono — ADR-008 §5 |

> **L'aciclicità è una conseguenza di ADR-006 §1 e di D3, non una proprietà indipendente.**

Due novità nella tabella dei percorsi, ciascuna con la sua radice nel dominio:

- `PUT /api/tasks/{id}/agent` **blocca un progetto** (SHARE) — da **D2**. È la riga che sembra
  gratuita, e un solo test se ne accorge;
- e **non blocca l'agente di origine** — da **D3**: nessuna regola dipende dal suo stato.

## 3. La guardia che va scritta una volta sola

ADR-006 §2 diceva «qualunque scrittura futura su quel task → `409`, **per costruzione**». Vero, e
**non automatico**: niente estende quella regola a un percorso nuovo.

La guardia è uscita da `assignTo(Project)`, dove viveva inline e si leggeva come una regola
sull'associazione, ed è diventata un `requireNotFrozen()` privato che **entrambi** gli `assignTo`
chiamano. Un `assignTo(Agent)` scritto senza quella chiamata avrebbe bucato ADR-006 §2 **senza
rendere rosso niente**, perché nessun test poteva accorgersene: il percorso non esisteva.

La mutazione §5 riga 1 è ciò che rende questa frase verificabile invece che rassicurante.

## 4. Superficie cambiata

| Rotta | Cambia |
|---|---|
| `PUT /api/tasks/{id}/agent` | **nuova.** `If-Match` obbligatorio dal primo giorno (P4). `200` + `ETag` |
| `GET /api/agents/{id}/tasks` | **nuova.** Dal più vecchio; agente inesistente → `404`, inattivo → risponde normalmente |
| `POST /api/tasks` | accetta `agentId` opzionale. Un agente rifiutato significa **nessun task** |
| `TaskResponse` | guadagna `agentId`, nullable. **E nient'altro** |
| `tasks_agent_id_fkey` | `NO ACTION`, come la chiave del progetto |

**Nessuna rottura.** Tutto additivo: `agentId` nel corpo, una rotta nuova, un campo opzionale in
ingresso. Due `doesNotExist` nel test della forma — `agentStatus`, `agentActive` — dicono che
l'assenza dei campi derivati è decisa e non dimenticata.

**Una sola versione.** `agent_id` è una colonna di `tasks`: assegnare scrive quella riga,
incrementa `tasks.version` e consuma l'ETag del task. Nessun versionamento separato
dell'associazione. La forma osservabile:

> Chi cambia il **progetto** invalida il tag di chi sta per cambiare l'**agente**. `200` e `412`,
> non due `200`.

E nell'altra direzione, **P3**: assegnare **non** incrementa `agents.version`.

## 5. Verifica per mutazione

Quattro, una per proprietà portante. Eseguite il 2026-09-17.

| Mutazione | Esito |
|---|---|
| `requireNotFrozen()` rimossa da `assignTo(Agent)` | **Rosso**, 2 test — e il secondo dice più del primo: senza la guardia emerge l'**altro** rifiuto (`inactive-agent-cannot-receive-tasks` al posto di `archived-project-task-is-immutable`), che è la prova che l'asserzione sull'ordine dei controlli è reale e non decorativa |
| `findByIdForShare` → `findById` sull'agente | **Rosso**, e **un test solo**: `aDeactivationCannotCommitBetweenAnAssignmentsDecisionAndItsCommit` |
| Il progetto letto dal proxy invece che dal lookup bloccato | **Rosso**, e **un test solo**: `anArchiveCannotCommitBetweenAnAgentChangesDecisionAndItsCommit`. È la prova che quel lock è portante — rieseguita dopo aver riparato l'harness, perché la prima esecuzione aveva lasciato il mutante nell'albero (§7) |
| Un write path nuovo **senza** `Precondition` | **Rosso**, entrambe le asserzioni di `PreconditionCoverageTest` |

**La quarta ha dovuto essere riprogettata, e la prima versione non provava niente.** Toglieva il
parametro `Precondition` da `assignToAgent`: il test di concorrenza chiama quel metodo con tre
argomenti, quindi la build **non compilava** e `PreconditionCoverageTest` non veniva mai eseguito.
Una mutazione che ferma la build non è una mutazione, è un errore di battitura — e la sua rossezza
non dimostra nulla sulla guardia.

La versione corretta **aggiunge** un percorso di scrittura senza precondizione, che è esattamente
il modo in cui P4 si rompe nella realtà: qualcuno scrive un metodo e non pensa alla staleness.
Compila, niente altro lo referenzia, e `PreconditionCoverageTest` lo trova.

## 6. Invarianti e come sono verificati

| ID | Verifica |
|---|---|
| I-1 agente inattivo → `409`, nulla scritto | `anInactiveAgentReceivesNoWork`, con rilettura via SQL |
| I-2 task congelato → `409`, e vince sul rifiuto dell'agente | `aTaskInAnArchivedProjectDoesNotChangeAgent`, `whenBothRefusalsApplyTheFrozenTaskIsWhatIsReported` |
| I-3 `deactivate` non scrive righe di `tasks` | `deactivatingAnAgentWritesNoTaskRow` — sulle **scritture**, contate da Hibernate, con controllo positivo |
| **I-4 un task con agente inattivo è riassegnabile** | `aTaskWhoseAgentWasDeactivatedCanStillBeReassigned` — **è D3 per intero** |
| I-5 `428` / `412` sul percorso nuovo | `theNewPathRequiresAPreconditionLikeEveryOtherMutation` |
| I-6 una riga, una versione | `changingTheProjectConsumesTheTagForChangingTheAgent`, e il gemello inverso |
| I-7 assegnare non muove l'ETag dell'agente | `assigningATaskDoesNotChangeTheAgentsEntityTag` |
| I-8 progetto e agente concorrenti | `changingTheProjectAndChangingTheAgentAreOneRowAndOneVersion` |
| I-9 `deactivate` contro assegnazione | `aDeactivationCannotCommitBetweenAnAssignmentsDecisionAndItsCommit` |
| I-10 `archive` contro cambio di agente | `anArchiveCannotCommitBetweenAnAgentChangesDecisionAndItsCommit` |
| I-11 `V6` additiva | Il test sulle coppie consecutive di TASK-006, **senza aggiungere un caso** |
| I-12 ogni `type` enumerato | `ApiProblemCoverageTest` |
| **I-13 P4** | `PreconditionCoverageTest`: il percorso nuovo è nell'insieme pinnato |

## 7. Rilievi della review del proprio diff

**HIGH, e il rilievo è contro il mio strumento, non contro il codice.**

`anArchiveCannotCommitBetweenAnAgentChangesDecisionAndItsCommit` ha cominciato a fallire su quello
che sembrava codice pulito, dopo essere passato. Ho diagnosticato due volte, e **le prime due
diagnosi erano sbagliate**:

1. *«è un flake delle attese»* — plausibile e falso. L'ho corretto lo stesso, perché il difetto
   c'era davvero (sotto), ma non era la causa;
2. *«il lock sul progetto non viene preso perché Hibernate restituisce il proxy»* — costruito su un
   log SQL vero, e **falso**, perché quel log veniva da un albero corrotto.

**La causa vera.** La mutazione `project-unlocked` sostituiva un blocco con la stringa vuota. Il
suo revert cerca la stringa mutata: `"".count()` su un file vale `len(file)+1`, l'assert è saltato,
lo script è uscito con codice diverso da zero — e il driver in shell **non controllava l'exit
status**. Il blocco del lock è rimasto fuori dal codice, e ogni esecuzione successiva confermava
diligentemente una mutazione che credevo di aver revertito.

Il lock c'era sempre stato. **Il test aveva ragione dal primo istante**: stava segnalando
esattamente il difetto che la mutazione introduce, e ha continuato a segnalarlo finché non ho
guardato lo strumento invece del codice.

Correzioni, tutte tenute:

| Che cosa | Perché resta |
|---|---|
| `awaitOrFail` separato da `awaitAtMost` nei due test di concorrenza | Il difetto era reale: un'attesa limitata dove serve un requisito produce sia rossi falsi sia **verdi falsi**. Corretto anche in `PreconditionConcurrencyTest`, che l'aveva ereditato da TASK-008 |
| Asserzione di fixture prima della corsa | Un setup che non assegna il progetto rendeva il fallimento incomprensibile. Adesso fallisce dicendo la sua premessa |
| `requireNotFrozen(Project)` prende il progetto bloccato | Non perché leggere il proxy fosse sbagliato — non lo era — ma perché la regola deve leggere ciò che il chiamante **ha deciso** di bloccare, e la forma precedente lasciava una chiamata il cui valore non andava da nessuna parte. Chiude **L-1** |
| L'harness di mutazione rifiuta di "revertire" una cancellazione | Una mutazione che non sa tornare indietro è peggio di nessuna mutazione: sporca l'albero e poi ogni test conferma il mutante |

**La lezione, scritta perché tornerà.** Quando un test fallisce su codice che si crede pulito,
**la prima ipotesi da verificare è che l'albero sia pulito davvero** — non che il test sia instabile
e non che il framework si comporti in modo esotico. Entrambe quelle ipotesi sono più interessanti,
ed è esattamente per questo che sono arrivate prima.

**LOW, registrati e non fatti:**

| # | Rilievo |
|---|---|
| L-2 | `assignTo(Agent)` esce presto sul caso idempotente **prima** di `requireNotFrozen()`, quindi riassegnare lo stesso agente a un task congelato è `200`. Coerente con `assignTo(Project)` e con ADR-006 §2, e non separatamente testato su un progetto archiviato |
| L-3 | `create` con **entrambe** le associazioni valuta `requireNotFrozen()` due volte, la seconda su un progetto appena rifiutato se archiviato. Innocuo, e un controllo in più non è un difetto |
| ~~L-4~~ | **Corretto nella task**, non registrato: `POST /api/tasks` con un `agentId` inesistente era asserito solo sul percorso `PUT`. La simmetria fra creare-con-agente e assegnare-dopo è ora coperta su entrambe le rotte |

**Chiuso in questa task** dalla review di TASK-008: **L-1** di allora — `findById` senza join fetch
sulla lettura singola — che con la seconda associazione sarebbe costato due query invece di una.

## 8. File

```
backend/src/main/resources/db/migration/V6__add_task_agent_relation.sql        nuovo
task/model/Task.java                          agent, assignTo(Agent), requireNotFrozen estratto
task/dto/TaskResponse.java                    + agentId
task/dto/TaskCreateRequest.java               + agentId opzionale
task/dto/TaskAgentAssignmentRequest.java      nuovo
task/exception/InactiveAgentCannotReceiveTasksException.java   nuovo
task/repository/TaskRepository.java           findAllByAgentId, findByIdWithAssociations,
                                              findAllWithProject -> findAllWithAssociations
task/service/TaskService.java                 assignToAgent, findAllByAgent, create(agentId)
task/controller/TaskController.java           PUT /{id}/agent
task/controller/AgentTaskController.java      nuovo
agent/repository/AgentRepository.java         findByIdForShare (L2)
api/ApiProblem.java, api/ApiExceptionHandler.java              + inactive-agent-cannot-receive-tasks
backend/src/test/.../task/TaskAgentAssignmentApiTest.java      19 test, scritti rossi
backend/src/test/.../task/TaskAgentConcurrencyTest.java        I-8, I-9, I-10
docs/adr/ADR-010-task-agent-assignment.md                      nuovo
tasks/TASK-009/{TASK,CONTEXT,IMPLEMENTATION,ARTIFACT,HANDOFF}
```
