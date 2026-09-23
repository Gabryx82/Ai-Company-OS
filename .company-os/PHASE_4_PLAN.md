# PHASE 4 — Task lifecycle

> Piano della fase. Stato in `PROJECT_STATE.md`, sequenza in `ROADMAP_PHASES_3_7.md`.

- **Integration branch**: `autonomous/phase-4-task-lifecycle`, da `autonomous/phase-3-security`.
- **Baseline**: 244 test verdi, stream `V8`, CI verde (run `35870550125`).

## Obiettivo
> **Un task si muove attraverso il suo ciclo di vita lungo archi dichiarati, i suoi dettagli si
> correggono, e la sua priorità significa qualcosa.**

È il prerequisito dichiarato di TD-08: un orchestratore che non muove il lavoro non ha niente da
orchestrare.

## Scope
| Task | Contenuto | Debiti |
|---|---|---|
| **TASK-015** | Transizioni `start`/`complete`/`stop`/`reopen` (ADR-014) | TD-37 (apre TD-38) |
| **TASK-016** | `PUT /api/tasks/{id}`; vocabolario chiuso di `priority` con censimento e `V9`; `?status=` | TD-36 |

**Fuori scope**: storia delle transizioni (TD-38), rimozione dell'agente da un task (TD-35),
filtro «task su agenti inattivi» (TD-34), paginazione.

## Criterio di chiusura
1. ogni coppia (stato, transizione) che non è un arco è `409`, verificato per tabella e per mutazione;
2. la concorrenza è verificata con interleaving **forzato**;
3. `V9` è verificata come upgrade reale e come rifiuto su dati fuori vocabolario;
4. suite verde in locale e in CI.
