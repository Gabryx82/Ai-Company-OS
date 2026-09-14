# TASK-007 — Artifact

**Completata il 2026-09-14.** Suite **158 verdi** (erano 137). Schema a **`V4`**.

## Che cosa esiste adesso

| Endpoint | |
|---|---|
| `POST /api/agents` | `201` + `Location`, agente attivo |
| `GET /api/agents[?active=]` | listato, filtro sul ciclo di vita |
| `GET /api/agents/{id}` | `404` `agent-not-found` se non esiste |
| `PUT /api/agents/{id}` | `409` `inactive-agent-is-immutable` se disattivato |
| `POST /api/agents/{id}/activate` / `/deactivate` | `409` `illegal-agent-state-transition` se ripetuto |
| `DELETE /api/agents/{id}` | `405`: si disattiva, non si cancella |

`AgentResponse` guadagna `status` (**derivato**), `createdAt`, `updatedAt`. `active` resta:
togliere un campo rompe ogni client, aggiungerne uno no.

## `V4`, e la cosa che il piano non aveva previsto

Additiva: due timestamp e un indice unico su `lower(name)`. Nessuna colonna rimossa, nessuna
tabella creata.

**Scoperto dalla suite, non dal progetto**: rendere i timestamp `NOT NULL` ha rotto lo stream del
seed di sviluppo, che inserisce agenti senza fornirli. Il file del seed è una migrazione già
applicata sui volumi esistenti: modificarlo ne cambierebbe il checksum e romperebbe la
validazione Flyway ovunque abbia già girato. Rimedio: un **`DEFAULT now()`** sulle colonne.
Il percorso applicativo non cambia — l'entità continua a valorizzarli in `@PrePersist`, come
ADR-004 §7 ha deciso — e il default serve ai writer che non sono l'applicazione.

## TD-31, e perché non è un hard stop

Unificare il ciclo di vita con l'enum di `Project` richiede di eliminare `active` dopo il
backfill. Il backfill sarebbe senza perdita, ma la rimozione di una colonna è irreversibile e il
charter la mette dietro una decisione umana **quando esiste un'alternativa ragionevole**.
Esiste: ogni regola gira identica su un booleano.

Quindi non ci si è fermati a chiedere — ci si è fermati a **non farlo**. Registrato come TD-31,
e nascosto al client invece che al registro: `status` è derivato.

## Verifica per mutazione (AC-13)

| Mutazione | Test rosso |
|---|---|
| `findByIdForUpdate` → `findById` (L1) | due `deactivate` concorrenti |
| ogni violazione tradotta in conflitto di nome | l'integrità non correlata non è mascherata |
| via la guardia sull'agente inattivo | `PUT` su inattivo |
| transizioni ripetute consentite | `deactivate` ripetuto |
| via il backfill di `V4` | upgrade `V3 → V4` |
| `status` aggiunto come colonna | `status` è derivato |

Sei su sei.

## Review avversariale — un rilievo, dichiarato invece che sepolto

**MEDIUM.** Riscrivendo `AgentController` ho perso il `@CrossOrigin` che c'era. Era già TD-11 —
consentiva ogni origine — ma il controller adesso **non legge soltanto**: riportarlo avrebbe
aperto creazione, modifica e le due transizioni a qualunque origine, come effetto collaterale di
una task che non riguarda CORS. Lasciato rimosso, scritto nel Javadoc del controller e qui.
**Non chiude TD-11**: la policy CORS è una task a sé.

## Debito
Nessuna chiusura. **Apre TD-31.** **TD-11 non chiuso**, e la sua superficie è cambiata.

## Commit
`2866e89` `docs(task-007)` — scope e la migrazione rifiutata · `ed8d615` `feat(agent)` — il registro
