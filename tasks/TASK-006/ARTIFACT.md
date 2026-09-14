# TASK-006 — Artifact

**Completata il 2026-09-14.** Suite **137 verdi** (erano 136). Nessun cambiamento di
comportamento dell'applicazione: un solo file di test toccato.

## Che cosa è stato fatto

`everyConsecutiveUpgradePreservesWhatWasAlreadyThere` in `MigrationStreamTest`.

Per **ogni coppia consecutiva** che Flyway risolve — non per un elenco scritto a mano — porta un
database alla versione `N`, ci scrive le righe che un'installazione viva avrebbe, migra a `N+1`
e verifica le tre cose che una migrazione può distruggere senza fallire:

| Asserzione | Cosa impedisce |
|---|---|
| conteggi per tabella non diminuiscono | una riga sparisce |
| le tabelle di `N` esistono ancora a `N+1` | una tabella sparisce |
| `migrationsExecuted == 1` | più del previsto viene applicato |

Le tabelle di storia di Flyway sono escluse dai conteggi: crescono legittimamente, e contarle
trasformerebbe «non si è perso niente» in «non è cambiato niente».

**Non** asserisce come sia fatto lo schema dopo: quello appartiene al test della migrazione che
l'ha reso tale, e metterlo qui costringerebbe ogni migrazione futura a modificare questo test.

## Perché più largo di quanto TD-22 chiedesse

TD-22 chiedeva il test `V1 → V2`. Scritto così avrebbe chiuso il buco di ieri e riaperto quello
di domani: `V3 → V4` sarebbe stato di nuovo scoperto, e qualcuno avrebbe dovuto ricordarsene.
Leggere le coppie dallo stream copre `V4` il giorno in cui esiste, senza che nessuno se ne
ricordi.

## TD-23

La decisione resta: l'elenco delle tabelle è dichiarato a mano, perché una migrazione che crea
una tabella è un fatto che qualcuno deve affermare. Quello che è sparito è la **frase** che
descriveva il test come capace di adattarsi da solo alle tabelle nuove — non lo è. Il difetto
che TD-23 registrava era la spiegazione, non l'elenco: adesso c'è `TABLES_AT_HEAD` con scritto
che cos'è.

## Verifica per mutazione (AC-7)

| Mutazione su `V3` | Esito |
|---|---|
| `DELETE FROM tasks;` | **RED** |
| `DROP TABLE agents CASCADE;` | **RED** |

## Debito

**TD-22 CLOSED**, più ampiamente di quanto chiedesse. **TD-23 CLOSED.**

## Commit
`af9eb23` `docs(task-006)` — scope · `5d0ad8d` `test(persistence)` — il test e la correzione
