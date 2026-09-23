# TASK-019 — Le run di un task

**Fase**: PHASE 6 — Execution. **ADR**: ADR-016. **Migrazione**: `V10`.

## Invarianti
- **I-1** Il lancio segue il protocollo del task: L0, P1 sul tag del task, L2, regole sull'entità.
- **I-2** Nessuna run su un task congelato, `DONE`, senza agente o con agente inattivo.
- **I-3** Al più una run non finita per task (servizio sotto L0, indice parziale come rete).
- **I-4** Un task `OPEN` lanciato diventa `IN_PROGRESS` attraverso l'arco `START`; una run
  riuscita **non** completa il task.
- **I-5** Nessun executor vede una run prima del commit che la crea.
- **I-6** Ogni run finisce, con un tipo; nessuna resta `RUNNING` in un processo vivo; le run
  interrotte da uno stop sono fallite all'avvio.
- **I-7** Le colonne di esito concordano con lo stato, nel database.
- **I-8** La run ricorda l'agente che l'ha eseguita e i prompt inviati, verbatim.
- **I-9** Nessun test raggiunge un engine reale.
