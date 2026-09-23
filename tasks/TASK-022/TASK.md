# TASK-022 — La console dell'operatore

**Fase**: PHASE 7. **ADR**: ADR-017. **Milestone**: M-6.

## Invarianti
- **I-1** Ogni richiesta porta il token; il token non finisce mai in un cookie.
- **I-2** Ogni mutazione porta l'`If-Match` letto; un `412` non viene ritentato, il task viene riletto.
- **I-3** Una lettura singola senza `ETag` leggibile è un errore immediato.
- **I-4** Solo gli archi che partono dallo stato corrente sono offerti.
- **I-5** Una run si lancia col tag del task, e mai per un task senza agente attivo, `DONE` o con una
  run in corso.
- **I-6** I tipi sono generati dal contratto committato, e la CI rifiuta una deriva.
