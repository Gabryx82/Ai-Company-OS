# TASK-009 — Implementazione

Le decisioni sono in `docs/adr/ADR-010-task-agent-assignment.md`. Qui c'è come si realizzano, e i
punti che sembrano dettagli e non lo sono.

## 1. Ordine di lavoro

1. Le tre domande di dominio, risolte **prima** — §1 di ADR-010.
2. Il lock graph, **ridimostrato** — ADR-010 §3.
3. Test rossi che dimostrano il contratto (§5).
4. `V6` + `Task.agent` + la guardia di congelamento **estratta**.
5. `TaskService.assignToAgent`, con L0, P1, L2 su progetto e agente in ordine L5′.
6. Rotte, DTO, `ApiProblem`.
7. Test di concorrenza, suite completa, review avversariale, verifica per mutazione.

## 2. Migrazione

```sql
ALTER TABLE tasks ADD COLUMN agent_id BIGINT;
ALTER TABLE tasks ADD CONSTRAINT tasks_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agents (id);
CREATE INDEX tasks_agent_id_idx ON tasks (agent_id);
```

Le tre decisioni sono quelle di `V3`, e reggono per le stesse ragioni: nullable perché ogni task
esistente non ha un agente; `ADD COLUMN` senza `DEFAULT` quindi metadata-only; nessun `ON DELETE`
quindi `NO ACTION`. Dettaglio in ADR-010 §5.

**Nessun `DEFAULT`, a differenza di `V5`.** Non è una dimenticanza: `V5` aggiungeva una colonna
`NOT NULL`, e il default serviva perché il seed di sviluppo è una migrazione già applicata che non
può imparare una colonna nuova. Qui la colonna è nullable, quindi non c'è niente da fornire.

## 3. La guardia che va scritta una volta sola

È il punto della task più facile da sbagliare in silenzio.

ADR-006 §2 aveva scritto la regola in forma generale — «qualunque scrittura futura su quel task →
`409`, **per costruzione**». Quella frase è vera, e **non è automatica**: non esiste nessun
meccanismo che la estenda a un percorso nuovo. Vale solo se il percorso nuovo attraversa la
guardia.

Quindi la guardia esce da `assignTo(Project)`, dove viveva inline e si leggeva come una regola
sull'associazione, e diventa un metodo privato che **entrambi** gli `assignTo` chiamano:

```java
private void requireNotFrozen() {
    if (project != null && project.isArchived()) {
        throw new ArchivedProjectTaskIsImmutableException(project.getId());
    }
}
```

Un `assignTo(Agent)` scritto senza quella chiamata avrebbe bucato ADR-006 §2 **senza rendere rosso
niente** — non c'era nessun test che potesse accorgersene, perché il percorso non esisteva ancora.
Adesso c'è: `aTaskInAnArchivedProjectDoesNotChangeAgent`.

## 4. Il codice

### 4.1 `Task.assignTo(Agent)`

Tre regole, nell'ordine, e l'ordine è la decisione:

| # | Regola | Perché in questa posizione |
|---|---|---|
| 1 | Stesso agente già assegnato → **ritorna** | Non muta niente, quindi non c'è niente da rifiutare — nemmeno se l'agente è stato disattivato nel frattempo. ADR-005 §5 |
| 2 | Task congelato → `409` | Prima della destinazione: il task è congelato **prima** che si guardi a chi lo si sta dando. ADR-006 §7 fece la stessa scelta |
| 3 | Agente inattivo → `409` | D1 |

Deliberatamente assente: **nessuna regola sull'agente di origine.** Nessuna dipende dal suo stato
(D3), ed è la ragione per cui il service non ne blocca la riga.

### 4.2 `TaskService.assignToAgent` — l'ordine è il protocollo

```java
// L0
Task task = repository.findByIdForUpdate(taskId).orElseThrow(...);

// P1, prima che qualunque regola legga la riga
precondition.requireSatisfiedBy(task.getVersion());

// L2 sul progetto del task, se ne ha uno -- D2
Long projectId = task.getProjectId();
Project lockedProject = projectId == null ? null : requireProjectForDecision(projectId);

// L2 sull'agente di destinazione -- D1. L5': dopo il progetto
Agent target = requireAgentForDecision(agentId);

task.assignTo(target, lockedProject);
return versioned(repository.saveAndFlush(task));
```

**La riga che sembra gratuita è `requireProjectForDecision`**, e il suo risultato viene passato
all'entità invece di essere scartato — non perché leggere il proxy sia sbagliato, ma perché una
chiamata il cui valore non va da nessuna parte è una riga che il prossimo lettore cancella. Niente in «dai questo task a
quell'agente» nomina un progetto. Sta lì per D2: se il congelamento dipende da `Project.status`,
allora quello stato va letto sotto lock e tenuto fino al commit, o la regola viene valutata su uno
stato che un `archive` concorrente sta già cambiando. È TD-25 che ricompare su un percorso
inventato dopo che era stato chiuso, e
`anArchiveCannotCommitBetweenAnAgentChangesDecisionAndItsCommit` è l'unico test che se ne accorge.

**Perché `getProjectId()` prima del lock non è un problema.** Non inizializza il proxy: su un proxy
Hibernate il getter dell'identificatore restituisce l'id senza caricare, e un test di TASK-004 lo
pinna. Il caricamento avviene dopo, dentro `requireProjectForDecision`, sotto `FOR SHARE` — e
siccome il persistence context tiene una sola istanza per id, la guardia sull'entità legge lo stato
bloccato e non uno snapshot.

**Perché il progetto prima dell'agente.** L5′, e la natura dell'ordine è dichiarata in ADR-010
§3.1: fra le due classi **nessun dato impone** l'ordine, quindi è una convenzione — e funziona
perché l'ADR la scrive, non perché ogni percorso la indovini uguale.

### 4.3 Repository

| Query | Nota |
|---|---|
| `AgentRepository.findByIdForShare` | L2. **Solo la destinazione**: l'agente di origine non si blocca, D3 |
| `TaskRepository.findAllByAgentId` | `LEFT JOIN FETCH` su entrambe le associazioni: la risposta porta due identificatori, e senza il fetch sono due query per riga |
| `findAllWithProject` → `findAllWithAssociations` | **Rinominata**, non allargata in silenzio: un nome che dice «with project» mentre carica due associazioni è un commento che ha smesso di essere vero |
| `findByIdWithAssociations` | Nuova. Chiude il rilievo **L-1** della review di TASK-008: la lettura singola costava una query in più per il proxy lazy, e con la seconda associazione ne sarebbe costate due |

### 4.4 Contratto

`TaskResponse` guadagna `agentId` e **nient'altro**. Due `doesNotExist` nel test della forma —
`agentStatus`, `agentActive` — dicono che l'assenza è decisa e non dimenticata.

`TaskCreateRequest` guadagna `agentId` opzionale. Un agente rifiutato significa **nessun task**:
l'assegnazione avviene prima dell'insert, nella stessa transazione.

## 5. I test rossi, e che cosa provano

Scritti prima, e rossi. Sulla baseline la rotta non esiste, quindi la maggior parte fallisce con
`404` — il che è onesto su cosa provano a quel punto: **la forma, non ancora le regole.** Le regole
le provano le mutazioni di §6.

| Test | Che cosa dimostra |
|---|---|
| `anInactiveAgentReceivesNoWork` | D1, con rilettura: il rifiuto non ha scritto |
| `aTaskInAnArchivedProjectDoesNotChangeAgent` | D2, e la via d'uscita `restore` → `PUT` → `archive` |
| `whenBothRefusalsApplyTheFrozenTaskIsWhatIsReported` | L'ordine dei controlli, deterministico |
| **`aTaskWhoseAgentWasDeactivatedCanStillBeReassigned`** | **D3.** Se fallisce, la regola derivata è diventata quella dei progetti e la via di recupero si è chiusa |
| `deactivatingAnAgentWritesNoTaskRow` | D3, sulle **scritture** e non sui valori, con controllo positivo |
| `changingTheProjectConsumesTheTagForChangingTheAgent` | ADR-010 §4: una riga, una versione |
| `assigningATaskDoesNotChangeTheAgentsEntityTag` | P3 |
| `theNewPathRequiresAPreconditionLikeEveryOtherMutation` | P4, sul contratto |
| `PreconditionCoverageTest` | P4, sul codice: il percorso nuovo è nell'insieme pinnato |

Un test è stato **riscritto prima di essere committato** perché passava sulla baseline per la
ragione sbagliata: asserire che un task senza agente non riporta `agentId` è vero anche quando la
colonna non esiste, perché «assente» e «null» sono la stessa cosa per una JSON path. Adesso
asserisce il caso assegnato e quello non assegnato **affiancati**.

## 6. Verifica per mutazione

Eseguite il 2026-09-17. Esiti in `ARTIFACT.md` §5; qui che cosa ciascuna prova.

| Mutazione | Rende rosso | Che cosa prova |
|---|---|---|
| `requireNotFrozen()` rimossa da `assignTo(Agent)` | 2 test | Che D2 **non arriva da sé**: §3. Il secondo fallimento mostra emergere l'altro rifiuto, quindi anche l'ordine dei controlli è reale |
| `findByIdForShare` → `findById` sull'agente | 1 test | Che L2 sull'agente serve, e che il test di concorrenza osserva l'interleaving che dichiara |
| Il progetto letto dal proxy invece che dal lookup bloccato | 1 test | Che quel lock è portante — §4.2. Rieseguita dopo aver riparato l'harness: vedi `ARTIFACT.md` §7 |
| Un write path nuovo **senza** `Precondition` | `PreconditionCoverageTest`, 2 asserzioni | Che **P4 è un test, non un'intenzione** |

**La quarta è stata riprogettata**, e vale la pena dire perché. La prima versione toglieva il
parametro da `assignToAgent`: la build non compilava, quindi il test non veniva eseguito e la sua
rossezza non diceva niente sulla guardia. Una mutazione che ferma la build non è una mutazione. La
versione corretta **aggiunge** un percorso di scrittura senza precondizione — il modo in cui P4 si
rompe davvero: qualcuno scrive un metodo e non pensa alla staleness.

## 7. Rischi

| Rischio | Mitigazione |
|---|---|
| La regola di congelamento non arriva sul percorso nuovo | Guardia estratta in un punto solo, §3, e una mutazione lo verifica |
| Il lock sul progetto sembra gratuito e qualcuno lo toglie | Il commento lo dice, e un test di concorrenza è l'unico che se ne accorge |
| Un `agents → tasks` futuro chiude un ciclo | ADR-010 §3.4 lo scrive per esteso, con l'esempio (D3bis) che lo produrrebbe |
| L'ETag dell'agente si muove assegnando | `OPTIMISTIC_FORCE_INCREMENT` non è usato, e I-7 lo asserisce |
| I test di concorrenza sono falsi verdi | Verifica per mutazione, §6, righe 2 e 3 |
