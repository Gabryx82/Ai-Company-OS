# TASK-020 — Modello per agente, e il routing che sostituisce `MasterOrchestrator`

**Fase**: PHASE 6. **Debito**: TD-08. **ADR**: ADR-016 §6. **Migrazione**: `V11`.

## Invarianti
- **I-1** Un agente può dichiarare un modello dell'engine; senza, vale il default dell'engine.
- **I-2** Una run usa il modello chiesto al lancio, altrimenti quello dell'agente, e registra quello inviato.
- **I-3** Il routing suggerisce **solo agenti attivi del registro**, in ordine stabile, dicendo
  quali parole hanno fatto il punteggio.
- **I-4** Il routing non scrive niente.
- **I-5** `POST /api/orchestrator` non esiste più.
