# TASK-006 — Migration Test Coverage

> **Autonomous Project Mode.** Scope deciso dall'agente, `AUTONOMOUS_CHARTER.md` §2.

## Baseline
Branch `task-006-migration-test-coverage` da `autonomous/phase-1-foundations` (`39225e3`).
Suite di partenza: **136 verdi**.

## Perché adesso

`AUTONOMOUS_LOOP.md` §4, livello 3 — debito che il **passo successivo** tocca. Il passo
successivo è l'Agent Registry, che porterà una `V4`, e la storia di test delle migrazioni ha due
buchi proprio lì.

## Il problema

**TD-22.** Ogni test di `MigrationStreamTest` costruisce lo schema in un colpo solo, tranne uno:
`taskProjectRelationIsAddedToAPopulatedV2Database`, che TASK-003 ha scritto per il passo
`V2 → V3`. Gli altri passi non hanno l'equivalente. Una migrazione che dipendesse in silenzio da
una tabella vuota passerebbe comunque.

**TD-23.** Il test legge le **versioni** da Flyway ma fissa a mano l'elenco delle **tabelle**.
La cosa è scritta come se fosse a prova di futuro, e non lo è: la prossima migrazione che crea
una tabella lo rompe. TD-23 la registra come «scelta accettabile, affermazione da correggere».

## Decisione

**Non** aggiungere il test `V1 → V2` che TD-22 chiede alla lettera. Aggiungere invece un test che
percorre **ogni coppia consecutiva** dello stream, presente e futura: scrive righe alla versione
`N`, migra a `N+1`, e verifica che nulla sia stato perso.

*Perché.* Il test specifico chiuderebbe il buco di ieri e lascerebbe aperto quello di domani —
`V3 → V4` sarebbe di nuovo scoperto, e qualcuno dovrebbe ricordarsi di aggiungerlo. Un test
sulle coppie copre `V4` il giorno in cui esiste, senza che nessuno se ne ricordi. Costa la
stessa scrittura e non ha un momento in cui smette di valere.

Su TD-23 la decisione resta quella registrata — l'elenco delle tabelle è **una dichiarazione
deliberata**, chi aggiunge una tabella deve dichiararla — ma smette di essere spacciata per
automatica: diventa una costante con un nome che lo dice, e l'affermazione sbagliata sparisce.

## Invarianti

| ID | Invariante | Come si verifica |
|---|---|---|
| **I-1** | Nessuna migrazione dello stream **perde righe**: i conteggi per tabella prima di `V(n+1)` sono ≤ quelli dopo | Test sulle coppie consecutive |
| **I-2** | Nessuna migrazione **elimina una tabella** che esisteva a `V(n)` | Stesso test |
| **I-3** | Ogni passo applica **esattamente una** migrazione | Stesso test |
| **I-4** | L'elenco delle tabelle attese è una dichiarazione esplicita, non una promessa di automatismo | Costante con javadoc, e nessuna affermazione contraria nel codice |

## In scope
1. `everyConsecutiveUpgradePreservesWhatWasAlreadyThere` in `MigrationStreamTest`.
2. `TABLES_AT_HEAD`: elenco estratto in costante, con il suo perché.
3. Correzione dei commenti che affermano un automatismo che non c'è.
4. Artefatti, `PROJECT_STATE.md`.

## Fuori scope
- Migrazioni nuove, schema, entità, service, API.
- Qualunque cambiamento di comportamento dell'applicazione.
- TD-26, TD-14, TD-28, TD-30.
- Agent Registry: è TASK-007.

## Acceptance criteria
- **AC-1** — Il test percorre **tutte** le coppie consecutive risolte da Flyway, non un elenco
  scritto a mano. Aggiungere `V4` lo estende da solo.
- **AC-2** — A ogni passo vengono scritte righe reali prima di migrare, e ritrovate dopo.
- **AC-3** — Ogni passo applica esattamente una migrazione.
- **AC-4** — Nessuna tabella presente a `V(n)` manca a `V(n+1)`.
- **AC-5** — L'elenco delle tabelle è una costante documentata; nessun commento afferma più che
  il test si adatti da solo alle tabelle nuove.
- **AC-6** — Suite completa verde. Nessun test preesistente modificato per farlo passare.
- **AC-7** — Verifica per mutazione: una migrazione che cancellasse righe rende rosso il test.

## Chiusure attese
**TD-22** CLOSED — e più di quanto chiedesse. **TD-23** CLOSED.
