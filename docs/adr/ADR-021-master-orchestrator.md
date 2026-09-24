# ADR-021 — Il Master Orchestrator: pianifica, decide, consegna, non finge API

- **Stato**: Accettata e implementata (PHASE 10–11)
- **Data**: 2026-09-24
- **Decisa da**: agente, su direttiva umana del 2026-09-24 (§5–6, §8, §10–12)
- **Dipende da**: ADR-016 (run), ADR-018, ADR-019, ADR-020. **Sostituisce** il ruolo che TD-08 aveva
  tolto al vecchio `MasterOrchestrator` (rimosso in TASK-020 perché non orchestrava nulla).

## 1. Che cosa è

Un insieme di servizi del control plane, non un modello: **pianifica** (PHASE 10), **decide** per ogni
task agente, modello, software e contesto, **consegna** il lavoro all'AI Engine o a un agente
esterno, **registra** esiti e review (PHASE 11). Usa i modelli come strumenti; le decisioni
deterministiche restano codice, verificabile.

## 2. Pianificazione (PHASE 10)

```
MASTER_PROMPT.md ──► planner ──► .aicos/plan.json ──► PlanDocument (parse + validate) ──► PlanImporter
                      │                                                                    │
       AI Engine (JSON mode, job in background)                 fasi + TASK-NNN nel DB, e i documenti:
       oppure agente esterno (handoff su file)                  IMPLEMENTATION_PLAN.md, PHASE_N.md, TASK-NNN.md
```

- **Un formato, due produttori.** Il modello locale (default `qwen3.5:9b`, ruolo `PLANNER`) oppure
  Claude Code/Codex seguendo `GET /api/projects/{id}/plan/handoff`. Entrambi finiscono nello stesso
  validatore: il formato del piano non dipende da chi l'ha scritto.
- **Tollerante su ciò che non conosce, rigido su ciò che serve**: campi in più ignorati; titoli
  obbligatori; al massimo 12 fasi, 20 task per fase, 120 task; `422 plan-invalid` elenca *tutti* i
  problemi per percorso.
- **Codici deterministici**: `TASK-001…` nell'ordine del piano; i codici proposti dal modello sono
  ignorati.
- **Agente**: ruolo nominato esattamente, poi per inclusione, poi routing lessicale (TASK-020);
  nessuna corrispondenza → task non assegnata, la assegna l'operatore.
- **Sostituire un piano** è permesso solo a una bozza d'*intenzione*: piano non approvato, nessuna
  task uscita da `OPEN`, nessuna run. Oltre, `409 plan-locked`. Le righe rimosse sono quelle che il
  pianificatore stesso ha creato e che nessuno ha toccato.

## 3. Decisione di dispatch (PHASE 11)

`GET /api/tasks/{id}/orchestration` risponde, per una task, con **le ragioni** oltre che con le scelte:

| Scelta | Regola |
|---|---|
| Agente | quello assegnato; altrimenti il primo suggerimento del router |
| Modello | quello dell'agente (`agents.model`); altrimenti il modello di catalogo `ACTIVE` del ruolo che la task richiede (`CODER` se la task parla di codice, altrimenti `GENERAL`/`PLANNER`), servito dall'engine |
| Software | quelli che il piano indica per la task, poi quelli del tipo di progetto, poi quelli le cui capability compaiono nella task; ciascuno con disponibilità rilevata |
| Contesto | `AGENTS.md`, la task, la sua fase, il piano, il master prompt, i file che la task elenca, le reference se la task tocca UI/grafica; ciascuno con «esiste / non esiste» |
| Prompt | compatto: «Esegui TASK-NNN seguendo `tasks/TASK-NNN.md` e la governance in `AGENTS.md`.» |
| Target | AI Engine, oppure i software del catalogo con `execution_target` installati, oppure manuale |

## 4. Consegna senza API finte

Claude Code, Codex, Antigravity e OpenCode non espongono un'API di esecuzione che l'operatore voglia
pagare a consumo. La consegna è quindi un **handoff su file** più l'**apertura dello strumento**:

1. `.aicos/handoffs/TASK-NNN-<target>.md` contiene prompt compatto, file di contesto, livello di
   autonomia, e come riportare l'esito (sezione *Esito* della task);
2. il launcher (ADR-019) apre lo strumento **nella cartella del progetto**:
   - CLI (Claude Code, OpenCode): Windows Terminal con il comando e il prompt compatto come argomento
     — il prompt è composto dal control plane e passa i filtri di I3;
   - desktop (Codex, Antigravity IDE): l'app, o l'IDE sulla cartella; il prompt compatto torna nella
     risposta perché la console lo copi negli appunti;
3. la task passa a `IN_PROGRESS` con lo stesso arco `START` di sempre (If-Match del task, gate di
   fase, agente attivo);
4. quando l'operatore torna, registra l'esito con una **review**.

Nessun polling di processi esterni, nessuna lettura dello schermo: quello che è successo lo dice il
file della task e lo conferma l'operatore.

## 5. Review

`POST /api/tasks/{id}/reviews` registra un verdetto (`ACCEPTED` o `CHANGES_REQUESTED`) con nota,
opzionalmente legato alla run o all'handoff che l'ha prodotto. `ACCEPTED` completa la task
(`IN_PROGRESS → DONE`) nella stessa transazione; `CHANGES_REQUESTED` la lascia in corso. Le review
sono un registro in sola aggiunta (`task_reviews`): la storia delle decisioni umane non si riscrive.

## 6. Run dell'engine con contesto dai file

Per una task **di un piano**, il prompt di una run (ADR-016) include le istruzioni del livello di
autonomia (ADR-022) nel system prompt e, nel messaggio, il contenuto del documento della task e della
sua fase, troncati a una dimensione fissa. Per una task **fuori da un piano** il prompt è
esattamente quello di PHASE 6 — è la regressione che protegge il flusso già accettato.

## 7. Alternative scartate

- **Pilotare le app via automazione dell'interfaccia** (click simulati, lettura dello schermo): fragile
  e opaco, e l'operatore vuole capire che cosa succede.
- **Usare le API a consumo di Anthropic/OpenAI come unico target**: contraddice la direttiva
  («non voglio dipendere obbligatoriamente dalle API»); restano disponibili, spente senza chiave.
- **Un runtime a grafo (LangGraph) adesso**: ADR-001 lo rinvia a un esperimento con criteri
  misurabili; l'orchestrazione di oggi è una sequenza con gate umani, e il codice la esprime già.
