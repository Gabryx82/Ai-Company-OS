# TASK-016 — Dettagli, vocabolario di `priority`, filtro per stato

**Fase**: PHASE 4. **Debito**: TD-36. **Migrazione**: `V9`.

## Invarianti
- **I-1** `PUT /api/tasks/{id}` sostituisce titolo, descrizione, priorità; **non** stato né associazioni.
- **I-2** Un task in un progetto archiviato non si modifica (ADR-006 §2); `If-Match` obbligatorio.
- **I-3** `priority ∈ {LOW, MEDIUM, HIGH}`, case-sensitive, in tre guardie che concordano.
- **I-4** `V9` non riscrive nulla sui dati del censimento, e si ferma lasciando intatta una riga
  fuori vocabolario.
- **I-5** `GET /api/tasks?status=` restituisce solo quello stato; uno stato ignoto è
  `400 invalid-parameter`.

## Contesto
`CONTEXT`: `.company-os/PHASE_4_PLAN.md`, `tasks/TASK-010/CENSUS.md` (metodo), ADR-011.
