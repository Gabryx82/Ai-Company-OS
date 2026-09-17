# TASK-009 → prossimo agente

Conciso per chi arriva senza cronologia. Stato completo in `.company-os/PROJECT_STATE.md`,
decisioni in `docs/adr/ADR-010-task-agent-assignment.md`.

## Stato

- Branch `task-009-task-agent-assignment`, integrato in fast-forward in `autonomous/phase-2-assignment`.
- **201 test verdi**, schema **`V6`**, nessun failure aperto.
- `master` fermo a `d5ff121`. Intoccato, e resta il gate umano.

## Le quattro cose da sapere prima di scrivere codice

**1. Il lock graph ha tre classi, e l'ordine è `tasks` → `projects` → `agents`.**
Dentro ogni classe: id crescente, deduplicato. L'arco `projects → agents` è una **convenzione**,
non un vincolo dei dati: vale perché ADR-010 §3.1 la dichiara, quindi non indovinarla, leggila.

**2. L'aciclicità è una conseguenza, non una proprietà.** Regge finché `archive`/`restore` non
scrivono righe di `tasks` (ADR-006 §1) e finché il ciclo di vita di un agente non dipende dai suoi
task (ADR-010 D3). Un percorso futuro che violasse una delle due **non** va aggiunto aggirando
l'ordine: l'esempio concreto è in ADR-010 D3bis, e chiude un ciclo.

**3. La regola di congelamento sta in `Task.requireNotFrozen()`, ed è l'unico punto.**
ADR-006 §2 dice «qualunque scrittura futura su quel task → `409`, per costruzione». **Per
costruzione non è automatico**: vale perché il tuo percorso chiama quel metodo. Se scrivi una nuova
mutazione del task e non lo chiami, buchi ADR-006 §2 senza rendere rosso niente. TASK-009 lo ha
scoperto mettendo alla prova quella frase per la prima volta.

**4. Un task può puntare a un agente inattivo, ed è uno stato legale e necessario.** Non
«aggiustarlo». È D3: congelare quei task li intrappolerebbe con chi non può eseguirli, proprio
quando serve riassegnarli. Se un giorno sembra un bug, leggere D3 prima di chiuderlo.

## Quello che è pronto adesso e prima non lo era

`TD-08` — `MasterOrchestrator` è quattro `if` su `contains()` che restituiscono nomi di agenti
**che non esistono nel database**. L'audit di TASK-000 dice REPLACE, non evolvere, e finora la
sostituzione non era possibile: non c'era modo di esprimere «questo task è di quell'agente».
Adesso c'è. **Non è ancora la prossima task** — vedi sotto — ma ha smesso di essere bloccato.

## TASK-010 — da scegliere, con il candidato più forte già identificato

`AUTONOMOUS_LOOP.md` §4 va riletto sul posto. Al momento della chiusura di TASK-009: livelli 1 e 2
vuoti, livello 3 vuoto (TASK-008 ha chiuso TD-28 e TD-30). Il candidato più forte è **livello 5**:

> **TD-12 — `Task.status` e `Task.priority` sono stringhe libere.**
> Nessun enum, nessuna macchina a stati. Con TASK-003 e TASK-009 un task sa *dove* sta e *di chi*
> è; quello che ancora non sa è **in che stato** è, in un modo che qualcosa possa verificare. Il
> Task Engine del Product Vision chiede transizioni controllate e gate di approvazione, e su
> stringhe arbitrarie sono impossibili — il che rende TD-12 il vero prerequisito di TD-08 e del
> Planner, non il contrario.

Se lo scegli, tre cose che questa fase ha già stabilito e che si applicheranno:

- il ciclo di vita sta **sull'entità**, con transizioni esplicite e nessun setter (ADR-004 §4,
  ADR-008);
- ogni mutazione nuova **eredita** `If-Match` e `PreconditionCoverageTest` non ti lascia
  dimenticarlo (ADR-009 P4);
- ogni mutazione nuova deve chiamare `requireNotFrozen()`, o ADR-006 §2 smette di essere vera.

Una migrazione che stringe `status` a un vocabolario chiuso **non è additiva** se i dati esistenti
contengono valori fuori dal vocabolario: va deciso cosa farne, e se la risposta è «cancellarli» o
«riscriverli» è **hard stop #2 o #3** del charter. Guardare i dati prima di scrivere l'ADR.

Poi resta **TASK-011** (livello 6): la collisione di identificatori fra `docs/audit/TECHNICAL_DEBT.md`
e la numerazione viva, e `docs/RUNNING.md`.

## Debito che questa task lascia

| ID | Contenuto |
|---|---|
| **TD-13** | **RESOLVED** (numerazione dell'audit): `Agent` e `Task` adesso si conoscono |
| **TD-34** | *(nuovo, MINOR)* Un task può puntare a un agente inattivo — legale e necessario — e non c'è modo di chiedere «i task fermi su agenti inattivi» senza incrociare due liste lato client |
| **TD-35** | *(nuovo, MINOR)* Nessun `DELETE` dell'associazione: un task assegnato non torna non assegnato, si riassegna soltanto. Gemello di quello che ADR-005 §5 lasciò aperto sul progetto, con più pressione |
| L-1…L-4 | Rilievi LOW della review, in `ARTIFACT.md` §7. Nessuno blocca TASK-010 |
