# TASK-015 — Transizioni di stato di un task

**Fase**: PHASE 4. **Debito**: TD-37. **ADR**: ADR-014.

## Invarianti
- **I-1** Solo i quattro archi di `TaskTransition` esistono; ogni altra coppia (stato, transizione) è `409`.
- **I-2** `start` richiede un agente presente e attivo; nessun'altra transizione guarda l'agente.
- **I-3** Nessuna transizione su un task di un progetto archiviato; il congelamento è il primo rifiuto.
- **I-4** `If-Match` obbligatorio; un chiamante stantio riceve `412`, non il `409` del nuovo stato.
- **I-5** Due transizioni concorrenti con lo stesso tag: una riesce, l'altra `412` (non `500`).
- **I-6** Una transizione muove la versione del task e di nessun altro (P3).
- **I-7** `IN_PROGRESS` ⇒ agente presente (ADR-014 §4).
