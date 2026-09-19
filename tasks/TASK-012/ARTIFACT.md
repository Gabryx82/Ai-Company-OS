# TASK-012 — Chiusura

Due debiti, due esiti diversi. Suite **219 → 223**, schema **`V7` → `V8`**.

| Debito | Esito |
|---|---|
| **TD-14** | **Aperto, bloccato su un dato che non esiste nel repository.** `EVIDENCE_TD14.md` |
| **TD-31** | **CHIUSO.** `V8`, `AgentStatus`, contratto pubblico invariato. ADR-012 |

## 1. TD-14 — perché non è stato chiuso, e perché non è una rinuncia

Quattro fonti verificate, tutte negative: nessun remote configurato né mai esistito (nessun
`refs/remotes`, nessun `FETCH_HEAD`, `.git/config` senza sezione `[remote]`); nessun URL, owner o
nome di repository in **alcun** file tracciato o non tracciato; `gh` non installato; nessuna
configurazione CI presente.

La ricerca ha prodotto solo falsi positivi, e vale la pena dirlo perché sembrano risultati: ogni
occorrenza di `origin` è la parola italiana **«origine»** nella prosa su CORS e sui lock, più
`originalTag` in `Precondition.java`.

**Manca esattamente una cosa: l'URL del remote.** Dedurlo dal nome della cartella sarebbe
l'invenzione che la regola vietava, e un `origin` puntato al repository sbagliato è peggio di
nessun `origin`.

**Non è stato scritto nemmeno un workflow CI**, ed è deliberato: scegliere GitHub Actions
presuppone GitHub, e la piattaforma è parte della destinazione mancante. Lascerebbe in repository
un file che sembra una CI configurata senza esserlo.

## 2. TD-31 — che cosa è stato cambiato esattamente

`V8`, in cinque istruzioni: aggiunge `status VARCHAR(32)`, lo riempie con
`CASE WHEN active THEN 'ACTIVE' ELSE 'INACTIVE' END`, lo rende `NOT NULL`, aggiunge
`agents_status_check`, **elimina `active`**.

Nell'applicazione: `AgentStatus` enum, `@Enumerated(STRING)` sull'entità, `requireActive(boolean)`
→ `requireStatus(AgentStatus)`, `findAllByActiveOrderByIdAsc` → `findAllByStatusOrderByIdAsc`.

**Il contratto pubblico non cambia di un byte**, ed è la metà che rende il cambiamento sicuro.
`AgentResponse` aveva già **due** campi di ciclo di vita dalla TASK-007; `V8` inverte quale dei
due è reale:

| | Prima | Dopo |
|---|---|---|
| Database | `active BOOLEAN` | `status VARCHAR(32)` + `CHECK` |
| Risposta | `active` reale, `status` derivato | `status` reale, `active` derivato |
| JSON | `{"active":true,"status":"ACTIVE"}` | **identico** |

## 3. La conseguenza che il piano non aveva previsto

Il dev seed **nomina** la colonna che `V8` elimina. Su un database nuovo lo schema arriva a `V8`
prima che il seed parta, quindi il seed sarebbe fallito. `V4` aveva evitato una collisione della
stessa famiglia con un `DEFAULT` proprio per non toccare un file già applicato: **quella via qui
non esiste**, perché un `DEFAULT` non aiuta un `INSERT` che nomina una colonna scomparsa.

E modificare il file cambia il checksum, che Flyway valida a ogni `migrate` — quindi ogni database
già seminato, **compreso quello di questa macchina**, avrebbe rifiutato di avviarsi in dev.

Risolto con `repair()` in `DevSeedFlyway.apply`, prima di `migrate()`. Tocca solo metadati. La
motivazione per cui è accettabile **su questo stream e non su quello dello schema** è in ADR-012
§5, e la riga che conta è: sullo stream dello schema il fallimento rumoroso è il comportamento
voluto, quindi la chiamata non va copiata lì.

**Conseguenza derivata, adesso nominata**: il seed è valido solo alla testa dello stream. Nel
prodotto non cambia nulla; i fixture che seminavano a versioni intermedie ora scrivono
direttamente, chiedendo a `information_schema` quale colonna esista a quella versione.

## 4. Verifica sui dati reali, non solo sui fixture

Il database di sviluppo di questa macchina è a **`V3`** ed è stato **clonato** —
`CREATE DATABASE ... TEMPLATE` — per non toccarlo. Sul clone è stata avviata l'applicazione reale
in profilo `dev`:

| Verifica | Esito |
|---|---|
| Migrazioni applicate | `1,2,3,4,5,6,7,8` |
| Colonne di `agents` | `status` presente, **`active` assente** |
| Dati | 3 agenti `active = t` → 3 agenti `'ACTIVE'`. **Nessuna riga persa** |
| Checksum del seed | `109116245` → `263723270`, **riparato**, seed **non** ri-eseguito (sempre 3 agenti) |
| Vincolo | `agents_status_check` presente e corretto |
| Riga di `tasks` preesistente | sopravvissuta (`ok` / `OPEN`) |
| Avvio dell'applicazione | **riuscito** — quindi Hibernate `validate` passa |
| `GET /api/agents` | `{"active":true,"status":"ACTIVE", ...}` — **contratto identico** |
| `?active=true` / `?active=false` | 3 agenti / 0 agenti — **filtro corretto** |

Clone eliminato dopo la verifica. **Il database reale è ancora a `V3` con `active`**, verificato
dopo la pulizia.

## 5. Verifica per mutazione

Albero verificato pulito **prima di ogni mutazione e dopo ogni revert**.

| # | Mutazione | Esito |
|---|---|---|
| **M1** | Il backfill scrive `'ACTIVE'` incondizionatamente | **Rosso**: `expected: "INACTIVE"` |
| **M2** | `repair()` rimosso da `DevSeedFlyway.apply` | **Rosso**: `Validate failed: Migrations have failed validation` |

**M1 è la mutazione che conta.** Un test con soli agenti attivi sarebbe passato contro quella
migrazione, e la biiezione — l'intero argomento per cui `V8` non perde dati — sarebbe stata
asserita senza essere verificata. Il test semina **entrambi** i valori proprio per questo.

**M2 riproduce esattamente il fallimento che il database reale avrebbe avuto**: è la prova che
`repair()` non è una precauzione teorica.

## 6. Adversarial review

Nessun rilievo HIGH o MEDIUM.

**LOW registrati:**

- **L-1** — `MigrationStreamTest.insertAgentAt` interroga `information_schema` a ogni inserimento.
  Inefficiente e irrilevante in un test; l'alternativa (sapere che `V8` è la soglia) metterebbe il
  numero di versione in un secondo posto.
- **L-2** — Il fixture legacy `V1000__dev_seed_agents.sql` non è più una copia byte-per-byte del
  vecchio seed. Non può esserlo: numerato 1000, gira dopo `V8`. Il commento lo dichiara.
- **L-3** — `repair()` accetta anche una modifica accidentale al seed. Dichiarato in ADR-012 §5
  con le tre condizioni che lo rendono accettabile qui.
- **L-4** — Nessun indice su `agents.status`, mentre `projects_status_idx` esiste. Deliberato: non
  c'era indice su `active`, e TD-31 non parla di prestazioni.

## 7. Debito

| ID | Stato |
|---|---|
| **TD-31** | **CHIUSO** da `V8` + ADR-012 |
| **TD-14** | **APERTO**, bloccato sull'URL del remote. Evidenza e dato mancante in `EVIDENCE_TD14.md` |

**Nessun debito nuovo aperto.** Prossimo identificatore libero: **`TD-38`**.

## 8. Suite

**223/223 verdi.** `./mvnw -B clean test` → `BUILD SUCCESS`. Schema `V8`.
