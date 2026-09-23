# ADR-014 — Il ciclo di vita di un task: quattro archi, regole sull'entità

- **Stato**: Accettata e implementata (TASK-015)
- **Data**: 2026-09-23
- **Decisa da**: agente, entro il blocco PHASE 3–7 autorizzato il 2026-09-23
- **Chiude**: TD-37. **Completa** ADR-011 §3 (che chiudeva il vocabolario e lasciava aperte le
  transizioni), senza superarla.

## 1. Problema

`Task.status` aveva un vocabolario chiuso e **nessun percorso di mutazione**: creato `OPEN`,
restava `OPEN`. `PROJECT_STATE.md`: «un orchestratore che non muove il lavoro attraverso gli
stati non ha niente da orchestrare». TD-37 era il prerequisito dichiarato di TD-08.

## 2. Il grafo

```
  OPEN ──start──▶ IN_PROGRESS ──complete──▶ DONE
   ▲                  │                       │
   └──────stop────────┘                       │
   └────────────────reopen────────────────────┘
```

Quattro archi, in una tabella sola: `TaskTransition` (`from`, `to`). **`TaskStatus` resta il
vocabolario, `TaskTransition` è la macchina** — la distinzione di ADR-011 §3 («un vocabolario non è
una macchina a stati») diventa due tipi.

**Esclusi, e perché.** `OPEN → DONE`: registrare lavoro già finito è ciò che la creazione con
`DONE` copre già (ADR-011 §3), e su un task esistente renderebbe «chi l'ha fatto» senza risposta.
`DONE → IN_PROGRESS`: il lavoro riaperto torna in coda, dove può essere riassegnato prima che
qualcuno lo inizi. Aggiungere un arco costa una riga e un test; toglierlo dopo che i client lo usano
rompe i client — l'asimmetria di ADR-004 §2 vale per gli archi come per i valori.

## 3. Le regole, nell'ordine

Sull'entità (`Task.apply`), non nel service — come per progetti e agenti:

1. **Un task congelato non si muove** (ADR-006 §2, «qualunque scrittura futura», applicata per la
   terza volta). Prima di tutto, perché è il rifiuto su cui il chiamante può agire qualunque altra
   cosa sia sbagliata.
2. **La transizione deve essere un arco dallo stato corrente** → `409`
   `illegal-task-state-transition`. **Non idempotente**: un secondo `start` è `409`, come un
   secondo `archive` (ADR-004 §4).
3. **Solo `start` guarda l'agente**: deve esserci (`409` `unassigned-task-cannot-start`) ed essere
   attivo (`409` `inactive-agent-cannot-receive-tasks`, riusato: stesso rimedio). `IN_PROGRESS`
   significa «qualcuno ci sta lavorando adesso» (ADR-011); entrarci richiede un qualcuno che possa.

**Uscire da `IN_PROGRESS` non dipende mai dall'agente.** È ADR-010 D3 che sopravvive al ciclo di
vita: un agente spento a metà lavoro non intrappola il task, e `stop`/`complete` sono la via
d'uscita. Un task `IN_PROGRESS` con agente inattivo è quindi uno stato **legale**, raggiungibile.

## 4. Invariante nuovo, e chi lo protegge

> **`IN_PROGRESS` ⇒ il task ha un agente.**

Vero per costruzione oggi: `start` lo richiede, e **non esiste** un modo di togliere l'agente a un
task (TD-35). **Il giorno in cui TD-35 viene chiuso** (un `DELETE /api/tasks/{id}/agent`), quel
percorso deve rifiutare un task `IN_PROGRESS`, o l'invariante cade in silenzio. Scritto qui perché
è la frase che la task futura deve trovare.

Riassegnare un task `IN_PROGRESS` resta permesso: è un passaggio di consegne, e l'invariante regge.

## 5. Concorrenza

`L0` (task, esclusivo) → `P1` (precondizione) → `L2` sul progetto (condiviso, per la regola del
congelamento) → `L2` sull'agente del task (condiviso, **solo per `start`**). L5′ invariato
(`tasks` → `projects` → `agents`), nessun arco nuovo nel lock graph: nessun percorso che blocca un
agente blocca poi un task.

Il lock sull'agente per `start` è **uniformità**, non necessità stretta: anche senza, lo stato
finale di `start ‖ deactivate` è legale in entrambi gli ordini (§3). È tenuto perché L7 dice che il
protocollo è universale — una regola che legge una riga la legge sotto lock — e perché rende ogni
esito equivalente a un ordine seriale leggibile.

## 6. Contratto

`POST /api/tasks/{id}/start|complete|stop|reopen`, `If-Match` obbligatorio (P4), `200` + `ETag`
nuovo. **Nessun `PUT` su `status`**, e non va aggiunto: permetterebbe di nominare la destinazione
invece dell'arco, e `OPEN → DONE` sarebbe a una richiesta di distanza.

Ordine dei rifiuti: `401` → `428`/`400` → `404` → `412` → `409` congelato → `409` arco → `409`
agente.

## 7. Fuori scope

Storia delle transizioni (chi ha mosso cosa e quando) → **TD-38**. Transizioni automatiche (una
run che completa il task) → PHASE 6, dove saranno una decisione esplicita. Filtri per stato →
TASK-016.
