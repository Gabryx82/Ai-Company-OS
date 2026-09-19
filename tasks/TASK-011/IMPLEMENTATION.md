# TASK-011 — Implementazione

Chiusura in `tasks/TASK-011/ARTIFACT.md`. Qui **cosa è stato scritto e dove**.

## 1. File

| File | Ruolo |
|---|---|
| `docs/DEBT_REGISTRY.md` | **nuovo.** §1 quale spazio è autoritativo e come si cita; §2 le cinque collisioni; §3 quelli che coincidono; §4 solo-audit; §5 solo-vivi; §6 come si apre un debito nuovo; §7 cosa il documento non fa |
| `docs/audit/TECHNICAL_DEBT.md` | Nota in testa, **prima della scala di severità**, con la tabella delle cinque collisioni e il rinvio al registro |
| `docs/RUNNING.md` | Sezione API riscritta; due istruzioni false corrette con nota storica; tabella delle sette migrazioni |
| `backend/src/test/java/com/aicompany/backend/docs/DebtRegistryConsistencyTest.java` | **nuovo.** 3 test, nessun contesto Spring, nessun database |

**Nessun file di `src/main` toccato. Nessuna migrazione.**

## 2. Il test, e le sue tre precauzioni

`DebtRegistryConsistencyTest` è l'unico test della suite che legge file **fuori dal proprio
modulo**. È deliberato — i documenti sono l'oggetto sotto test — e porta tre precauzioni che
esistono per come un test del genere fallisce male:

| Precauzione | Contro cosa |
|---|---|
| `repositoryRoot()` risale fino a una directory con `.company-os` | Contro l'assunzione sulla working directory di Surefire. Normalmente sale di un livello, ma non dipende da quello |
| `read()` asserisce `Files.exists` **prima** di leggere | Contro il verde per file mancante. È anche ciò che ha reso leggibile il rosso di baseline: il messaggio nomina il path assoluto atteso |
| `assertThat(auditIds).isNotEmpty()` | Contro la vacuità. Se il formato `### TD-NN` cambiasse, il set letto sarebbe vuoto e `containsAll` passerebbe su qualunque registro |

`KNOWN_COLLISIONS` è una costante scritta a mano, come `TABLES_AT_HEAD` in `MigrationStreamTest` e
per la stessa ragione: **è una scoperta, non una derivazione.** Una sesta collisione deve
obbligare qualcuno a venire a dichiararla.

## 3. `RUNNING.md` — l'ordine della sezione API è una decisione

`If-Match` viene **prima degli endpoint**, non dopo.

Non è gusto editoriale: senza quella sezione, la prima scrittura di chiunque risponde `428` e il
documento non spiega perché. Un endpoint non documentato fa perdere tempo; un protocollo non
documentato fa concludere che il server sia rotto.

Poi le risorse in ordine di centralità — task, progetti, agent — e per ciascuna **anche ciò che
non esiste**, perché è la metà che un lettore non può dedurre: niente `DELETE` sulle task (è
`405`, «non per questa via»), niente `PUT` generale, niente `DELETE` sui progetti, nessun modo di
cambiare lo `status` di una task (TD-37).

`POST /api/orchestrator` è documentato **con un avviso**, non omesso. Esiste e risponde; tacerlo
lo farebbe scoprire a qualcuno che ci costruisce sopra.

## 4. Le due note storiche

Le due correzioni portano una nota che dice **che cosa la riga affermava prima**.

Non è cerimonia. Qualcuno può aver creato una `V2` seguendo quella riga, o aver concluso che il
proprio database fosse rotto perché `flyway_schema_history` conteneva sette righe invece di una.
Cancellare l'errore in silenzio gli toglie il modo di capire cosa gli è successo — ed è lo stesso
criterio per cui il registro del debito non rinumera.
