# TASK-015 → prossimo agente
- Transizioni: `POST /api/tasks/{id}/start|complete|stop|reopen`, `If-Match`. Tabella in `TaskTransition`.
- **Chi chiude TD-35** (togliere l'agente) deve rifiutare i task `IN_PROGRESS` (ADR-014 §4).
- Un test di concorrenza con una barriera **non** forza l'interleaving: usare la transazione esterna + latch.
- PHASE 6 (run) muoverà lo stato: dovrà passare da `TaskService#transition`, non scrivere `status`.
