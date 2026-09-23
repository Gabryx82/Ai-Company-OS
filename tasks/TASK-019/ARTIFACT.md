# TASK-019 — Artefatto

Suite Java **277 → 307**. Stream **`V9` → `V10`** (additiva). Nessun debito chiuso da solo: è la
metà «run» di M-5; TD-08 si chiude con TASK-020.

## Verifica per mutazione (albero pulito prima e dopo ciascuna)
| # | Mutazione | Esito |
|---|---|---|
| R1 | listener sincrono invece di `AFTER_COMMIT` | rosso (2): l'executor non trova la riga, la run resta `QUEUED` |
| R2 | niente controllo della run attiva | rosso: l'indice parziale scatta, `500` invece di `409` — la rete regge, il contratto no |
| R3 | `DONE` eseguibile | rosso |
| R4 | `OPEN` non avviato | rosso (3) |
| R5 | un bug lascia la run `RUNNING` | rosso |
| R6 | prompt senza specializzazione | rosso |
| R7 | inoltro di qualunque `type` d'errore | rosso |
| R8 | recupero delle sole `QUEUED` | rosso |
| R9 | niente precondizione sul lancio | rosso |

**R1 è una race**, e va letta come tale: il listener sincrono consegna la run all'executor prima del
commit, e se l'executor fosse più lento del commit la troverebbe. Qui è stato rosso; non è garantito
che lo sia sempre. La garanzia vera è strutturale (`AFTER_COMMIT`), e il test la rende visibile nel
caso frequente.

## Rotture dichiarate
Nessuna: tutto additivo.

## LOW
- L-1 Nessun modo di cancellare una run in corso.
- L-2 L'output è testo libero: nessun artefatto strutturato.
- L-3 Nessun limite al numero di run per task nel tempo (solo a quelle simultanee).
