# TASK-006 — Handoff

## Stato
Completata. Suite **137 verdi**. Nessun cambiamento all'applicazione.

## Da sapere

1. **Ogni migrazione nuova è già coperta.** `everyConsecutiveUpgradePreservesWhatWasAlreadyThere`
   legge le coppie da Flyway: quando arriva `V4`, il passo `V3 → V4` viene verificato senza che
   nessuno aggiunga un caso. Se una migrazione perde righe o elimina una tabella, quel test
   diventa rosso.

2. **Se la tua migrazione crea una tabella, aggiornare `TABLES_AT_HEAD`.** È una dichiarazione
   deliberata, non un automatismo, e il test `emptyDatabaseIsMigratedByTheSchemaStreamAlone`
   fallirà finché non lo fai. È il comportamento voluto.

3. **Il probe `V900`** in `db/fixture/next` sta sopra la testa dello stream reale e sotto `1000`.
   Resta valido finché le versioni reali restano sotto `900`.

## Prossimo
**TASK-007 — Agent Registry.** `Agent` è oggi una tabella piatta con un solo `GET`. Portarla allo
standard di `Project` è il livello 4 della priorità: Skills, Tools, MCP Registry, Model Gateway
e Planner si appoggiano tutti agli agenti.
