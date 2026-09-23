# ADR-016 — Le run: un agente esegue un task attraverso l'AI Engine, e il control plane tiene il registro

- **Stato**: Accettata e implementata (TASK-019, TASK-020)
- **Data**: 2026-09-23
- **Decisa da**: agente, entro il blocco PHASE 3–7 autorizzato il 2026-09-23
- **Attua**: ADR-001 («stato canonico delle esecuzioni» in Spring), `MIGRATION_MAP.md` M-5 (metà
  orchestrazione). Usa ADR-014 (l'arco `START`) e ADR-015 (contratto v1).

## 1. Problema

Un task sa dove sta, di chi è e in che stato è, e da ADR-014 può muoversi. Nessuno però lo
**esegue**: l'agente è un nome in un registro. PHASE 5 ha dato un engine che sa chiamare un modello;
manca chi decide cosa chiamare, per quale task, e chi ricorda che cosa è successo.

## 2. Il lancio è una scrittura sul task

`POST /api/tasks/{id}/runs` può **avviare** il task (`OPEN → IN_PROGRESS`), quindi segue il
protocollo del task, non uno proprio:

1. **L0** sul task, poi **P1** con l'`If-Match` **del task** (P4: una scrittura su una riga esistente);
2. **L2** su progetto e agente, condivisi, in ordine L5′;
3. regole sull'entità (`Task.requireRunnable`): non congelato → non `DONE` (`409
   finished-task-cannot-run`: il lavoro finito si riapre con il suo arco, non si riesegue per
   sbaglio) → agente presente e attivo (gli stessi rifiuti di `start`);
4. **una sola run non finita per task** (`409 task-run-in-progress`), controllata sotto L0; l'indice
   unico parziale `task_runs_one_active_per_task_idx` è la rete, non il meccanismo;
5. se `OPEN`, l'arco `START` di ADR-014 — **mai** una scrittura diretta di `status`;
6. inserimento `QUEUED` e pubblicazione di `RunQueued`.

`IN_PROGRESS` è eseguibile: un secondo tentativo su lavoro già avviato è il caso ordinario.

## 3. Esecuzione

- **Dopo il commit** (`@TransactionalEventListener(AFTER_COMMIT)`): un executor che prendesse la run
  prima vedrebbe una riga non ancora visibile, o la eseguirebbe mentre la transazione che l'ha creata
  viene annullata. Verificato per mutazione (R1).
- **Fuori da ogni transazione** durante la chiamata: tre transazioni brevi (`QUEUED → RUNNING`,
  chiamata, `SUCCEEDED | FAILED`), ciascuna con il lock della sola riga della run. Una transazione
  aperta per i secondi di un modello locale terrebbe una connessione e un lock per run.
- **Executor limitato**: 2 thread, coda 50 (`aicos.runs.concurrency`, `aicos.runs.queue-capacity`);
  oltre la coda la run fallisce `rejected` invece di crescere senza limite.
- **Ogni run finisce.** Nessun percorso lascia una run `RUNNING` in un processo vivo; all'avvio, le
  run `QUEUED`/`RUNNING` del processo precedente sono fallite `interrupted`.
- **La run non chiude il task.** `SUCCEEDED` dice che l'engine ha risposto, non che la risposta sia
  buona: l'operatore la legge e usa `complete` (human in the loop, `PRODUCT_VISION.md`). Una run
  fallita non muove il task.
- Un **rifiuto** del modello (`finish_reason: refusal`) è una risposta: `SUCCEEDED`, non `FAILED`.

Ciclo di vita della run: `QUEUED → RUNNING → SUCCEEDED | FAILED`, guidato solo dall'executor, mai da
un client. Nessun `ETag` sulla run: nessuno può scriverla.

## 4. Fallimenti tipizzati

`failure_type` è sempre un URN:

| Origine | Tipo |
|---|---|
| L'engine ha risposto con un problem detail | **il suo** `urn:ai-company-os:engine:problem:*`, inoltrato, non tradotto |
| Il control plane | `urn:ai-company-os:run-failure:` `engine-unreachable`, `engine-timeout`, `engine-protocol`, `interrupted`, `rejected`, `internal` |

Tre spazi distinti (API, engine, run): chi legge una run sa chi ha deciso che è fallita. Un errore
con `type` estraneo all'engine è `engine-protocol`, non un inoltro. `internal` non porta mai il
messaggio dell'eccezione (verificato).

## 5. Persistenza e lock graph

`V10`, additiva: `task_runs`, con `task_runs_status_check`, `task_runs_finish_reason_check` e
**`task_runs_outcome_check`**: le colonne di esito devono concordare con lo stato (un `SUCCEEDED`
senza output è rifiutato dal database). `agent_id` è l'agente **al momento della run**, non letto
da `tasks.agent_id`: il task può cambiare mano, la run continua a dire chi l'ha eseguita. I prompt
inviati sono salvati **verbatim**.

Lock graph: il lancio prende `tasks → projects → agents` (L5′) e inserisce una riga di run; l'executor
blocca **solo** righe di run e mai un task. La classe nuova non ha archi verso le altre: aciclicità
invariata.

## 6. Contratto

| Rotta | |
|---|---|
| `POST /api/tasks/{id}/runs` | `If-Match` del task; corpo facoltativo `{"model": "<provider>:<modello>"}`; `202` + `Location` |
| `GET /api/tasks/{id}/runs` | la più recente per prima; task ignoto `404` |
| `GET /api/runs/{id}` | `404 run-not-found` |

Il modello: quello chiesto, altrimenti quello dell'agente (TASK-020), altrimenti il default
dell'engine.

## 7. Fuori scope

Cancellazione di una run, streaming dell'output, retry automatici, esecuzione a più passi o a grafo,
quote e costi, artefatti strutturati oltre al testo.
