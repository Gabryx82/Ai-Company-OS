# ADR-008 — Agent Registry: lo stesso standard del Project Registry, e una divergenza dichiarata

- **Stato**: **Accettata** — decisa autonomamente il 2026-09-14 in Autonomous Project Mode.
- **Task**: TASK-007
- **Rapporto con le precedenti**: non supera nessuna ADR. Applica ad `Agent` le decisioni che
  ADR-004 ha preso per `Project`, e il protocollo di lock di ADR-006 §4 per la clausola di
  chiusura L7. Usa il contratto di errore di ADR-007.

## Contesto

`Agent` è la tabella più vecchia del repository e la meno curata: cinque colonne, nessun
timestamp, nessun vincolo di unicità, nessuna validazione, e un solo `GET /api/agents` senza
scritture. È rimasta così mentre `Project` acquisiva ciclo di vita, unicità imposta dal
database, timestamp con fuso, e un protocollo di concorrenza.

La target architecture appoggia sugli agenti Skills, Rules, Subagents, Tools, MCP Registry,
Model Gateway e Planner. Tutto quello che viene dopo assume un registro che oggi non esiste.

## Decisioni

### 1. Le decisioni di ADR-004 si applicano identiche, e non si ridiscutono

| Elemento | Scelta | Da |
|---|---|---|
| Cancellazione | Nessun mapping `DELETE` → `405`. Si disattiva | ADR-004 §3 |
| Transizione illegale | `409`, regola **sull'entità** | ADR-004 §4 |
| Unicità del nome | Indice unico funzionale su `lower(name)`, con la stessa normalizzazione nel service | ADR-004 §5 |
| Modifica di un agente fuori registro | `409`: prima si riattiva | ADR-004 §8 |
| Timestamp | `TIMESTAMP WITH TIME ZONE`, valorizzati dall'entità | ADR-004 §7 |

*Perché non ridiscuterle.* Sono state prese con le loro motivazioni, sono state riviste, e
niente in `Agent` le rende diverse. Riaprirle qui produrrebbe due dialetti di dominio dentro lo
stesso sistema, che è precisamente il problema che TASK-005 ha appena chiuso sugli errori.

### 2. Il ciclo di vita resta sul `boolean active`. **Non** si introduce un enum, e la divergenza è dichiarata

`Project` esprime il ciclo di vita con `ProjectStatus` (enum chiuso + `CHECK`). `Agent`
continua a esprimerlo con `active BOOLEAN`, e ci costruisce sopra `deactivate()` / `activate()`
con le stesse regole.

*Perché non unificare adesso.* Unificare significa portare `active` a `status VARCHAR(32)` con
backfill e poi **eliminare la colonna `active`**. Il backfill sarebbe senza perdita — un
booleano verso due valori è una biiezione — ma la rimozione di una colonna è una migrazione
irreversibile, e `AUTONOMOUS_CHARTER.md` la mette dietro una decisione umana **quando esiste
un'alternativa ragionevole**. L'alternativa esiste ed è questa: il ciclo di vita funziona
identico su un booleano, e le regole di dominio non cambiano di una riga.

*Perché non tenerle entrambe.* Aggiungere `status` lasciando `active` significherebbe lo stesso
stato espresso due volte, che possono divergere — l'alternativa che ADR-004 ha scartato per
`deleted_at`. Peggio: `active` è `NOT NULL` senza default, quindi o le si dà un default che
mente sugli agenti disattivati, o serve un trigger per una colonna che nessuno legge.

*Come si toglie comunque la divergenza dal contratto pubblico.* `AgentResponse` espone
`status: "ACTIVE" | "INACTIVE"`, **derivato** dal booleano, accanto ad `active` che resta.
Un client vede un vocabolario solo, e il database non ne registra due. È la stessa forma di
ADR-006 §1: lo stato si deriva in lettura invece di essere materializzato.

*Registrato.* **TD-31** — «`Agent` esprime il ciclo di vita con un booleano, `Project` con un
enum chiuso. Unificarli richiede di eliminare una colonna, cioè una migrazione irreversibile che
il charter mette dietro una decisione umana. Da fare quando un terzo stato servirà davvero: a
quel punto la migrazione va fatta comunque, e il costo della decisione è già pagato.»

*Perché `INACTIVE` e non `ARCHIVED`.* Un progetto archiviato è messo via; un agente disattivato
è spento. Usare la stessa parola per due significati diversi sarebbe coerenza formale contro il
senso — lo stesso criterio con cui ADR-005 §5 ha rifiutato di rendere `PUT` non idempotente per
simmetria con `archive`.

### 3. `V4` è additiva, e non tocca nessun dato esistente

```
ALTER TABLE agents ADD COLUMN created_at TIMESTAMPTZ;
ALTER TABLE agents ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE agents SET created_at = now(), updated_at = now() WHERE created_at IS NULL;
ALTER TABLE agents ALTER COLUMN created_at SET NOT NULL;   -- idem updated_at
CREATE UNIQUE INDEX agents_name_unique_idx ON agents (lower(name));
```

*Perché un `UPDATE`, qui, quando ADR-005 §2 lo rifiutava.* Là il valore da scrivere non
esisteva: nessun progetto era quello giusto per un task creato prima dei progetti, e inventarne
uno avrebbe asserito il falso. Qui il valore esiste ed è vero: quelle righe sono state create
prima di adesso, e `now()` è la migliore approssimazione disponibile di un fatto reale. È
un'approssimazione e va detto — un agente del seed di sviluppo risulterà creato al momento della
migrazione — ma non è un'invenzione.

*Il rischio dichiarato dell'indice unico.* Se un database esistente contiene due agenti con lo
stesso nome, `V4` **fallisce**. È il comportamento giusto: l'alternativa sarebbe scegliere in
silenzio quale delle due righe sopravvive. Chi si trova in quel caso deve decidere, e la
migrazione che si ferma è la conversazione che lo costringe a farlo.

### 4. Nessuna relazione con `Project`, e nessuna con `Task`

`V4` non aggiunge chiavi esterne. `Agent` resta un'entità autonoma.

*Perché.* È esattamente il vincolo che ADR-004 §1 si era dato per `Project`, con la stessa
motivazione: collegare due entità richiede decisioni di dominio che non sono state prese — un
task ha un agente assegnato? un agente appartiene a un progetto? può essere condiviso? — e una
chiave esterna su una tabella popolata non si annulla come una `CREATE TABLE`. Anticiparle qui
sarebbe prenderle per inerzia.

### 5. Il protocollo di lock si applica, perché L7 dice che si applica

`deactivate`, `activate` e `PUT /api/agents/{id}` prendono `PESSIMISTIC_WRITE` sulla riga
dell'agente prima di leggerne lo stato (**L1**). Le letture non prendono niente (**L6**).

*Perché non è una scelta.* ADR-006 §4 L7 è una clausola di chiusura: *ogni* percorso di
scrittura la cui correttezza dipende da uno stato di ciclo di vita applica il protocollo, senza
eccezioni «tanto questo caso è innocuo». Due `deactivate` concorrenti senza lock rispondono
entrambi `200` invece di `200` + `409` — è TD-19 di nuovo, sulla terza entità.

*Che cosa **non** serve qui.* Nessun L0: L0 protegge l'associazione di un task, e `Agent` non ne
ha. Nessun L2: nessuna decisione su un agente dipende dallo stato di **un'altra** riga. Il
protocollo si applica per intero e si riduce a L1 e L6, che è come deve essere — la regola è
universale, la sua istanza dipende da quello che l'entità ha.

### 6. Gli errori usano il contratto di ADR-007

Quattro `ApiProblem` nuovi: `agent-not-found`, `agent-name-conflict`,
`inactive-agent-is-immutable`, `illegal-agent-state-transition`. Nessun advice nuovo: ce n'è uno
solo, ed è globale.

*Conseguenza verificata da un test che già esiste.* `ApiProblemCoverageTest` fallisce se
un'eccezione di dominio nuova non ha una mappatura. Le quattro eccezioni di `Agent` non possono
essere aggiunte senza decidere che aspetto abbiano dall'esterno.

## Alternative scartate

| Alternativa | Perché no |
|---|---|
| `status` enum su `Agent`, eliminando `active` | Migrazione irreversibile dietro una decisione umana, con un'alternativa ragionevole disponibile (§2). TD-31 |
| `status` accanto ad `active` | Stesso stato due volte, possono divergere; e `active NOT NULL` richiederebbe un default che mente o un trigger (§2) |
| Chiamare `ARCHIVED` lo stato di un agente spento | Coerenza formale contro il senso: un progetto messo via e un agente spento non sono la stessa cosa (§2) |
| Nessun timestamp su `agents` | Lascia `Agent` fuori dallo standard che ogni altra entità rispetta, per risparmiare una migrazione additiva |
| Nessun indice unico sul nome | Un registro con «Code Architect» e «code architect» è rotto, come lo sarebbe quello dei progetti (ADR-004 §5) |
| `DELETE /api/agents/{id}` | Stessa ragione di ADR-004 §3: la cancellazione di una radice pone a ogni sottodominio futuro la domanda «cosa succede ai figli» |
| Relazione `Task` → `Agent` in questa task | Decisioni di dominio non prese, e una FK su tabella popolata non si annulla facilmente (§4) |
| Saltare il lock perché «gli agenti non hanno concorrenza reale» | È letteralmente il ragionamento che L7 vieta, ed è quello che ha prodotto TD-25 |

## Conseguenze operative

- Lo schema passa a **`V4`**. Il test sulle coppie consecutive introdotto da TASK-006 copre
  `V3 → V4` **senza che nessuno aggiunga un caso**: era il motivo per cui quella task è venuta
  prima di questa.
- `TABLES_AT_HEAD` non cambia: `V4` non crea tabelle.
- `AgentResponse` guadagna `status`, derivato. Additivo: `active` resta.
- Un database esistente con nomi di agente duplicati **blocca** `V4`, deliberatamente.
- I timestamp delle righe preesistenti valgono il momento della migrazione. È
  un'approssimazione dichiarata, non un'invenzione (§3).
- **TD-31** aperto: la divergenza booleano/enum fra `Agent` e `Project`.
