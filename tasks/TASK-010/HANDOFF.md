# TASK-010 → prossimo agente

Conciso per chi arriva senza cronologia. Stato completo in `.company-os/PROJECT_STATE.md`,
decisioni in `docs/adr/ADR-011-task-status-closed-vocabulary.md`, evidenza in
`tasks/TASK-010/CENSUS.md`.

## Stato

- Branch `task-010-task-status-vocabulary`, integrato in fast-forward in `autonomous/phase-2-assignment`.
- **216 test verdi**, schema **`V7`**, nessun failure aperto.
- `master` fermo a `d5ff121`. Intoccato, e resta il gate umano.

## Le cinque cose da sapere prima di scrivere codice

**1. `Task.status` è `TaskStatus`, e il vocabolario è `OPEN`, `IN_PROGRESS`, `DONE`.**
Chiuso in tre punti: vincolo Jakarta sulla request, enum sull'entità, `tasks_status_check` nel
database. **Aggiungere un valore non è una modifica solo applicativa**: serve una `V8` che
riscriva il constraint, e `TaskStatusPinTest` ti ferma se lo dimentichi.

**2. È un vocabolario, NON una macchina a stati.** Nessuna regola di transizione esiste. Un task
può essere creato direttamente `DONE`, e c'è un test che lo pinna — `anyValueOfTheVocabularyMayBeTheFirstOne`.
Se un giorno sembra un bug, non lo è: ADR-011 §3. Aggiungere «il lavoro comincia aperto» significa
cancellare quel test e scrivere un'ADR che dica perché.

**3. Il campo della request è `String` e deve restarlo — a meno di cambiare ADR-007.**
Tipizzarlo come `TaskStatus` sembra più pulito e rompe il contratto d'errore: Jackson fallisce, la
risposta diventa `malformed-request` con «*the request body could not be read*», che è **falso**, e
il campo colpevole sparisce perché quel ramo non popola `errors`. La motivazione è nel javadoc di
`InTaskStatusVocabulary`, dove la leggerà chi sta per «semplificare».

**4. Il validator lascia passare `null` e blank di proposito.** Sono affare di `@NotBlank`. Due
vincoli che segnalano la stessa assenza mettono due frasi sotto lo stesso campo e
`ApiExceptionHandler` tiene la prima che arriva. C'è un test sul **messaggio**, perché entrambi i
percorsi rispondono `400 validation-failed` e il codice non distingue.

**5. Il database di sviluppo locale è a `V3`, non a `V7`.** Lo stream Flyway è a `V7`;
quell'installazione non ha mai visto `V4`…`V6`. Non è un problema — le migrazioni si applicheranno
in ordine, e `V7` passerà perché la sua unica riga è `OPEN` — ma se leggi «schema a `V7`» e poi
apri quel database, sai già perché non coincidono.

## Il censimento, e perché rifarlo prima di stringere qualcos'altro

`CENSUS.md` è riproducibile: ha i comandi in fondo. È **la cosa che ha reso questa task eseguibile
in autonomia**, perché l'avvertimento di TASK-009 — stringere un campo non è additivo se i dati
non ci stanno — è una domanda sui fatti, e i fatti erano `{ OPEN }`, zero righe da trasformare,
nessun hard stop.

**Se la prossima task stringe `priority` (TD-36), lo stesso censimento va rifatto su quel campo.**
Non ereditarne il risultato: `status` e `priority` sono colonne diverse con scrittori diversi.
Dal censimento di questa task si sa già che i fixture usano `HIGH` e `LOW` e che il database
locale ha una riga `LOW` — ma non è un censimento, è un indizio.

## TASK-011 — da scegliere, con due candidati e un criterio

`AUTONOMOUS_LOOP.md` §4 va riletto sul posto. Alla chiusura di TASK-010: livelli 1, 2 e 3 vuoti,
216/216 verdi, nessun failure aperto.

**Candidato A — TD-37, le transizioni di `status`.** Livello 5. È il seguito diretto: il
vocabolario è chiuso ma il ciclo di vita **non è percorribile**, perché non esiste alcun percorso
che muti lo `status` di un task esistente. È anche precisamente ciò che ADR-011 §3 ha evitato di
rispondere per inerzia, quindi arriva con le domande già formulate e non ancora decise:

- quali transizioni sono legali, o nessuna e si può andare ovunque;
- `DONE` è terminale? Un task riaperto è lo stesso task?
- serve un gate di approvazione, che il Product Vision nomina ma nessun requisito specifica?

Tre cose che questa fase ha già stabilito e che si applicheranno:

- ogni mutazione nuova **eredita** `If-Match`, e `PreconditionCoverageTest` non ti lascia
  dimenticarlo (ADR-009 P4);
- ogni mutazione nuova deve chiamare `Task.requireNotFrozen()`, o ADR-006 §2 smette di essere
  vera — e **non è automatico**: vale perché il tuo percorso la chiama (lezione di TASK-009);
- il ciclo di vita sta **sull'entità**, con transizioni esplicite e nessun setter (ADR-004 §4).

**Candidato B — TASK-011 come già pianificata in `PHASE_2_PLAN.md`.** Livello 6: la collisione di
identificatori fra `docs/audit/TECHNICAL_DEBT.md` e la numerazione viva, più `docs/RUNNING.md` che
non documenta `/api/projects`, `/api/agents` né gli endpoint di TASK-003.

**Il criterio, e non è ovvio.** Il livello 6 non si tocca finché il 5 è pieno, e il 5 **non lo è**:
TD-37 ci sta. Ma il piano di fase assegna il livello 6 a TASK-011 e il criterio di chiusura di
PHASE 2 (§4 del piano) **è già soddisfatto in tutte e tre le condizioni**. Quindi la domanda vera è
se TD-37 appartenga a questa fase o alla prossima — e l'argomento più forte è che PHASE 2 si chiama
*Assignment*, non *Lifecycle*: TD-37 è il primo pezzo di una fase diversa. Chi sceglie, lo scriva.

## Debito che questa task lascia

| ID | Contenuto |
|---|---|
| **TD-12** | **PARZIALMENTE CHIUSO.** La metà `status` è chiusa; la metà `priority` è TD-36 |
| **TD-36** | *(nuovo, MINOR)* `Task.priority` resta stringa libera senza vincolo DB. Stesso difetto, stessa forma di soluzione, scope deliberatamente non combinato |
| **TD-37** | *(nuovo)* Nessun percorso muta lo `status` di un task esistente. Vocabolario chiuso, ciclo di vita non percorribile |
| L-1…L-3 | Rilievi LOW della review, in `ARTIFACT.md` §6. Nessuno blocca TASK-011 |
