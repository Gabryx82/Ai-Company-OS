# PHASE 6 — Execution

> Piano della fase. Stato in `PROJECT_STATE.md`, sequenza in `ROADMAP_PHASES_3_7.md`.

- **Integration branch**: `autonomous/phase-6-execution`, da `autonomous/phase-5-ai-engine`.
- **Baseline**: 277 test Java, 51 test engine, stream `V9`.

## Obiettivo
> **Un agente esegue un task attraverso l'AI Engine, e il control plane sa che cosa è stato
> chiesto, a chi, con quale modello, e che cosa è successo.**

M-5, metà orchestrazione, e TD-08: il placeholder `MasterOrchestrator` sostituito da routing sul
registro reale.

## Scope
| Task | Contenuto | Debiti |
|---|---|---|
| **TASK-019** | Run: `V10`, lancio col protocollo del task, esecuzione asincrona dopo il commit, fallimenti tipizzati, recupero all'avvio (ADR-016) | — |
| **TASK-020** | Modello per agente (`V11`); routing lessicale sul registro; rimozione di `MasterOrchestrator` | TD-08 |

**Fuori scope**: cancellazione delle run (TD-39), costi e quote (TD-40), esecuzione a più passi.

## Criterio di chiusura
1. un task va da `OPEN` a una run `SUCCEEDED` con un modello reale, end to end, su dati reali (clone);
2. ogni run finisce con un tipo, verificato per mutazione;
3. nessun test raggiunge un engine reale;
4. suite verdi in locale e in CI.
