# ADR-010 — Assegnazione `Task` → `Agent`, e il lock graph con tre classi di righe

- **Stato**: **Accettata.** Autonomous Project Mode, `AUTONOMOUS_CHARTER.md` §2.
- **Data**: 2026-09-17
- **Task**: TASK-009
- **Rapporto con le precedenti**: non supera nessuna ADR. **Estende ADR-006 §4** — il protocollo
  di lock acquista una terza classe di righe e l'aciclicità è **ridimostrata**, non ereditata.
  Applica **ADR-009 P4** per costruzione. Si appoggia a ADR-005 (la prima relazione), ADR-008
  (il registro degli agenti), ADR-004 §3 (si disattiva, non si cancella).

## 1. Le tre domande di dominio

Poste prima del codice, come ADR-005 fece per le sue. **Nessuna risposta è copiata dalla
relazione `Task` → `Project`**: dove coincide, coincide per una ragione che vale qui; dove il
dominio diverge, diverge — ed è il caso della terza, che è anche la più conseguente.

### D1 — Un task può essere assegnato a un agente **disattivato**?

**No. `409`, `urn:ai-company-os:problem:inactive-agent-cannot-receive-tasks`.**

Stesso esito che ADR-005 §3 diede per un progetto archiviato, e va detto perché **non è lo stesso
argomento**. Là il motivo è di contenimento: un contenitore tolto dal registro operativo non deve
crescere. Qui il motivo è di **responsabilità**, ed è più stringente: assegnare lavoro a un agente
spento crea un'obbligazione che nessuno può assolvere. Il task non è «in un posto sbagliato», è
**fermo**, e niente nel sistema lo segnala — sarebbe un task silenziosamente bloccato, cioè
esattamente la classe di difetto che un Company OS esiste per non avere.

*L'alternativa seria, e perché no.* Assegnare in anticipo a un agente temporaneamente spento,
aspettandosi che torni, è un caso d'uso legittimo — ma richiede semantica di coda e di
pianificazione che questo sistema non ha, e senza quella semantica «assegnato a chi è spento» è
indistinguibile da «dimenticato». Rifiutare è **reversibile dal chiamante** (`activate`, e la
stessa richiesta passa); permetterlo non lo è, perché i client comincerebbero a dipenderci. Resta
fra le domande di contratto aperte.

*Perché `409` e non `403`.* Identico ad ADR-004 §8, ADR-005 §3 e ADR-008: il rifiuto dipende dallo
stato della risorsa, non da chi chiede.

*Il caso idempotente è `200`.* Chiedere l'agente che il task **ha già** non muta niente, quindi non
c'è nessuna scrittura da rifiutare — nemmeno se quell'agente nel frattempo è stato disattivato.
ADR-005 §5 e ADR-006 §2 hanno già stabilito che un `PUT` che dichiara uno stato finale già vero ha
ragione. Qui la conseguenza è utile e non solo coerente: un client che riafferma lo stato che
legge non viene punito per una disattivazione che non lo riguarda.

### D2 — Un task in un progetto **archiviato** può cambiare agente?

**No. `409`, `archived-project-task-is-immutable`** — il `type` che esiste già.

ADR-006 §2 aveva scritto la regola in forma generale — «qualunque scrittura futura su quel task →
`409`, per costruzione» — e questa è la prima volta che quella frase viene messa alla prova. Regge,
e per il suo argomento originale: un contenitore fuori dal registro operativo che lascia comunque
**ri-assegnare** il proprio contenuto non è fuori da niente. Cambiare chi lavora su un task è una
mutazione del task quanto lo è spostarlo.

**«Per costruzione» però non è automatico, ed è il punto da non fraintendere.** Non c'è nessun
meccanismo che estenda la regola a un percorso nuovo: vale perché la guardia sta sull'entità
`Task` e perché il percorso nuovo la attraversa. Un `assignTo(Agent)` scritto senza quella guardia
avrebbe bucato ADR-006 §2 senza rendere rosso niente. Da qui due conseguenze operative:

1. la regola è scritta una volta sola, in un metodo privato dell'entità che entrambi gli
   `assignTo` chiamano;
2. il percorso di assegnazione dell'agente **deve leggere lo stato del progetto del task**, quindi
   deve bloccarne la riga (L2). È questo a rendere il lock graph di §3 non banale.

*Via d'uscita.* La stessa di ADR-006 §2, e non una nuova: `restore` del progetto → `PUT` →
`archive`.

### D3 — Disattivare un agente fa qualcosa ai suoi task?

**Scrive una riga sola, la propria — come `archive` — ma la regola derivata è l'opposta: un task
il cui agente è inattivo NON è congelato.**

Qui il dominio diverge, e copiare ADR-006 §1 per intero sarebbe stato l'errore.

La parte che si applica identica è il **non materializzare**: `deactivate` e `activate` non
toccano nessuna riga di `tasks`. Le tre ragioni di ADR-006 §1 valgono parola per parola, e la
decisiva è sempre il `restore`: una cascata materializzata dovrebbe ricordare che cosa ha
cambiato, o `activate` non sarebbe l'inverso esatto di `deactivate`. Con lo stato derivato non c'è
niente da ricordare.

La parte che **non** si applica è *quale* regola si deriva. Per i progetti la regola derivata è
«congelato in scrittura». Per gli agenti quella regola sarebbe **attivamente dannosa**:

> Il momento in cui si disattiva un agente è esattamente il momento in cui bisogna poter
> riassegnare il suo lavoro. Congelare i suoi task lo intrappolerebbe con un lavoratore che non
> può eseguirlo, e l'unica via d'uscita sarebbe riattivare l'agente — cioè annullare la ragione
> per cui lo si è spento.

Quindi: **un task assegnato a un agente inattivo resta pienamente scrivibile**, e in particolare
riassegnabile a un altro agente. Quella è la via di recupero, ed è la ragione per cui la relazione
esiste.

*Conseguenza dichiarata.* Esiste uno stato legale in cui un task punta a un agente inattivo. Deve
essere legale: l'alternativa sarebbe far fallire la disattivazione, e §1bis spiega perché non si
può.

*Contratto.* `TaskResponse` guadagna `agentId` e **nient'altro**: nessun `agentActive`, nessuno
stato derivato. Lo stesso rifiuto che ADR-006 §3 oppose a `projectStatus` — pubblicare un campo è
irreversibile, non pubblicarlo no — e la scopribilità si ottiene con `GET /api/agents/{id}`, che
quello stato ce l'ha già.

### D3bis — E se invece `deactivate` rifiutasse finché ha lavoro assegnato?

È l'alternativa seria a D3, e va scartata con la sua ragione, perché è l'unica che
**romperebbe il lock graph**.

Rifiutare la disattivazione richiede di sapere se esistono task assegnati, cioè di leggerli sotto
lock mentre si tiene già il lock esclusivo sulla riga dell'agente. Introdurrebbe l'arco
**`agents` → `tasks`**, e §3 mostra che tutti gli altri percorsi vanno `tasks` → … : il grafo
acquisterebbe un ciclo e due transazioni potrebbero aspettarsi a vicenda.

C'è anche l'argomento di prodotto, e da solo basterebbe: non poter togliere dal servizio un agente
guasto **finché** qualcuno non ne ha riassegnato tutto il lavoro è il contrario di ciò che serve
quando un agente è guasto.

Le due ragioni puntano nella stessa direzione, il che è il motivo per cui la risposta a D3 non è
una preferenza estetica: **l'aciclicità del protocollo è una conseguenza di D3**, esattamente come
in ADR-006 §4 era una conseguenza di §1.

## 2. Il contratto

| Rotta | Esito |
|---|---|
| `PUT /api/tasks/{id}/agent` | **nuova.** `If-Match` obbligatorio (ADR-009 P0). `200` + `ETag`, e i rifiuti sotto |
| `GET /api/agents/{id}/tasks` | **nuova.** I task di un agente, dal più vecchio. `404` se l'agente non esiste, non lista vuota |
| `POST /api/tasks` | accetta `agentId` **opzionale**, additivo |
| `TaskResponse` | guadagna `agentId`, nullable. Additivo |

Nessun `DELETE` dell'associazione: `NULL → non NULL` resta a senso unico, come ADR-005 §5, e per
la stessa ragione — una rotta che si può aggiungere domani senza rompere nessuno non si indovina
oggi. Con gli agenti la pressione è più forte, perché disattivare un agente fa venire voglia di
**disassegnare**; ma la via di recupero che il dominio ha davvero è **riassegnare**, e quella
esiste. Resta fra le domande aperte.

### Failure semantics

| Situazione | Stato | `type` |
|---|---|---|
| Task inesistente | `404` | `task-not-found` |
| Agente inesistente | `404` | `agent-not-found` |
| Agente **inattivo** | `409` | **`inactive-agent-cannot-receive-tasks`** (nuovo) |
| Task in un progetto `ARCHIVED` | `409` | `archived-project-task-is-immutable` (esistente) |
| Stesso agente già assegnato | `200` no-op | — |
| `If-Match` assente / stantio / illeggibile | `428` / `412` / `400` | ADR-009 §6 |

*Ordine dei controlli quando più d'uno si applica*: **prima il congelamento del task, poi lo stato
dell'agente di destinazione.** Stessa scelta di ADR-006 §7 — origine prima di destinazione — e per
la stessa ragione: il task è congelato prima ancora che si guardi a chi lo si sta dando.
Deterministico, e asserito da un test.

## 3. Il lock graph, con tre classi di righe

ADR-006 §4 chiude con un avvertimento esplicito: «se un percorso futuro dovesse bloccare un task
tenendo già un lock su un progetto, l'ordine globale va rivisto, non aggirato». Questa è quella
revisione, e non consiste nell'aggiungere `agents` in fondo a L5: consiste nel **rifare la prova**
su tutti i percorsi, quelli nuovi e quelli che esistevano già.

### 3.1 L5′ — l'ordine globale, esteso

> **L5′ — Ordine globale di acquisizione.**
> I lock si prendono sempre in quest'ordine di classe:
> **`tasks` → `projects` → `agents`.**
> Dentro ogni classe l'insieme delle righe si costruisce, si **deduplica**, si ordina per `id`
> crescente e si blocca in quell'ordine, indipendentemente dal ruolo che ciascuna riga ha nella
> richiesta.

*Che cosa di questo ordine è imposto e che cosa è convenzione* — la distinzione conta, perché una
convenzione va dichiarata per poter essere obbedita:

| Arco | Natura |
|---|---|
| `tasks` → `projects` | **Imposto dai dati.** Quali progetti servano lo dice la riga del task, e leggerla senza lock è il difetto che L0 chiude |
| `tasks` → `agents` | **Imposto dai dati** per l'agente *di origine* — se un giorno servisse — e **scelto** per quello di destinazione, che arriva dal corpo della richiesta. Bloccare l'agente prima del task sarebbe possibile, e creerebbe l'arco `agents` → `tasks` che §3.4 mostra essere l'unico capace di chiudere un ciclo |
| `projects` → `agents` | **Convenzione.** Nessun dato la impone: in `PUT /api/tasks/{id}/agent` i due insiemi sono indipendenti. È fissata qui per dichiarazione, e vale perché è dichiarata |

### 3.2 Ogni percorso di scrittura, dopo TASK-009

| Percorso | `tasks` | `projects` | `agents` |
|---|---|---|---|
| `POST /api/projects` | — | — | — |
| `PUT /api/projects/{id}` | — | `{id}` **WRITE** | — |
| `POST /api/projects/{id}/archive` | — | `{id}` **WRITE** | — |
| `POST /api/projects/{id}/restore` | — | `{id}` **WRITE** | — |
| `POST /api/agents` | — | — | — |
| `PUT /api/agents/{id}` | — | — | `{id}` **WRITE** |
| `POST /api/agents/{id}/activate` | — | — | `{id}` **WRITE** |
| `POST /api/agents/{id}/deactivate` | — | — | `{id}` **WRITE** |
| `POST /api/tasks`, nessuna associazione | — | — | — |
| `POST /api/tasks` con `projectId` | — | `{target}` SHARE | — |
| `POST /api/tasks` con `agentId` | — | — | `{target}` SHARE |
| `POST /api/tasks` con entrambi | — | `{target}` SHARE | `{target}` SHARE |
| `PUT /api/tasks/{id}/project` | `{id}` **WRITE** | `{source, target}` SHARE, id crescente | — |
| **`PUT /api/tasks/{id}/agent`** | `{id}` **WRITE** | **`{progetto del task}` SHARE**, se ne ha uno | `{target}` SHARE |
| Ogni `GET` | — | — | — |

Due righe di questa tabella sono le sole novità sostanziali, e ciascuna ha la sua ragione nel
dominio:

- **`PUT /api/tasks/{id}/agent` blocca un progetto.** Non è un dettaglio implementativo: discende
  da **D2**. La decisione dipende da `Project.status`, quindi L2 si applica, quindi la riga va
  presa condivisa e tenuta fino al commit. Senza, la regola di congelamento verrebbe valutata su
  uno stato che l'`archive` concorrente sta già cambiando — TD-25 di nuovo, su un percorso nuovo.
- **`PUT /api/tasks/{id}/agent` NON blocca l'agente di origine.** Discende da **D3**: nessuna
  regola dipende dallo stato dell'agente che il task lascia, perché un agente inattivo non congela
  niente. L2 dice «ogni riga da cui la decisione dipende», e quella non lo è. Bloccarla sarebbe un
  lock che non protegge nulla e un conflitto inventato fra due riassegnazioni che lasciano lo
  stesso agente.

### 3.3 Le regole L, rilette su tre classi

Nessuna cambia di contenuto. Si applicano così:

- **L0** — `PUT /api/tasks/{id}/agent` muta un task esistente, quindi prende `tasks{id}` in
  esclusiva **prima** di leggerne qualunque associazione. Il progetto da bloccare si deriva da
  quella riga, non da una letta prima.
- **L1** — invariata: chi cambia il ciclo di vita di un progetto o di un agente prende
  `PESSIMISTIC_WRITE` sulla propria riga.
- **L2** — si estende agli agenti senza riformularsi: «ogni riga da cui la decisione dipende,
  condivisa». Per l'assegnazione sono il progetto del task (D2) e l'agente di destinazione (D1).
- **L6** — invariata. `GET /api/agents/{id}/tasks` non prende lock.
- **L7** — invariata, ed è il motivo per cui questa sezione esiste invece di un «tanto gli agenti
  sono semplici».

### 3.4 Aciclicità: la prova

Un deadlock fra due transazioni che rispettano l'ordine **dentro** ogni classe richiede due
transazioni che acquisiscano due **classi** in ordine opposto. Il grafo degli archi
«classe X bloccata prima della classe Y nella stessa transazione», letto dalla tabella §3.2:

```
tasks ──────► projects          PUT /tasks/{id}/project
  │              │              PUT /tasks/{id}/agent
  │              │
  │              ▼
  └──────────► agents           PUT /tasks/{id}/agent
                                POST /tasks con entrambi   (projects ► agents)
```

Tre archi: `tasks → projects`, `tasks → agents`, `projects → agents`. Ordinamento topologico:
**`tasks`, `projects`, `agents`**. Nessun ciclo.

La verifica che conta non è che il grafo sia un DAG — lo si vede — ma che **gli archi mancanti
manchino davvero**. Uno per uno:

| Arco assente | Perché nessun percorso lo produce |
|---|---|
| `projects → tasks` | `archive`, `restore` e `PUT /projects/{id}` **non prendono nessun lock su un task**. È ADR-006 §1: la coerenza dell'archiviazione è derivata, non materializzata |
| `agents → tasks` | `activate`, `deactivate` e `PUT /agents/{id}` non prendono nessun lock su un task. È **D3**: nessuna regola sul ciclo di vita dell'agente dipende dai suoi task. Sarebbe l'arco che D3bis avrebbe introdotto |
| `agents → projects` | Nessun percorso che parte da un agente legge lo stato di un progetto. I due registri non si conoscono (ADR-008 §5) |

> **L'aciclicità non è una proprietà indipendente: è una conseguenza di ADR-006 §1 e di D3.**
> Vale finché `archive`/`restore` non scrivono righe di `tasks` e finché il ciclo di vita di un
> agente non dipende dai suoi task. Un percorso futuro che violasse una delle due **non** va
> aggiunto aggirando l'ordine: va rivisto l'ordine, o va rivista la decisione.

*Dettaglio che conferma, come in ADR-006 §4.* Al flush, l'`UPDATE` di `tasks.agent_id` fa prendere
a PostgreSQL un `FOR KEY SHARE` implicito sulla riga dell'agente padre, per la chiave esterna.
Quel lock lo possediamo già in forma più forte (`FOR SHARE`, da L2): nessuna attesa nuova, nessun
arco nuovo.

## 4. Una sola versione, quella del task

`tasks.agent_id` è una colonna di `tasks`. Assegnare un agente **scrive la riga del task**, quindi
incrementa `tasks.version` e consuma l'entity-tag del task come qualunque altra mutazione.

**Nessun versionamento separato dell'associazione**, e non per economia: due contatori sulla stessa
riga sarebbero due verità sullo stesso fatto, che possono divergere — l'argomento che ADR-004 usò
contro `deleted_at` accanto a `status` e ADR-006 §1 contro la cascata materializzata. E
produrrebbero un contratto in cui un client deve sapere *quale* ETag serve a *quale* rotta della
stessa risorsa.

Conseguenza osservabile, ed è la forma utile della decisione:

> Chi ha letto un task e poi ne cambia il **progetto** invalida il tag di chi stava per cambiarne
> l'**agente**. I due ricevono `200` e `412`, non due `200`.

È corretto: sono due scritture sulla stessa riga, e il secondo chiamante ha deciso su uno stato che
non esiste più. Un test lo asserisce.

**P3 resta valido e va nell'altra direzione**: assegnare un task **non** incrementa
`agents.version`, esattamente come non incrementa `projects.version`. Due assegnazioni allo stesso
agente attivo non sono in conflitto, e farle diventare un `412` inventerebbe un conflitto che il
dominio non ha. `OPTIMISTIC_FORCE_INCREMENT` resta vietato.

**P4 è applicato per costruzione**: `PUT /api/tasks/{id}/agent` nasce con `If-Match` obbligatorio,
e `PreconditionCoverageTest` non permette di aggiungere un percorso di scrittura senza dichiararlo.

## 5. `V6`, additiva

```sql
ALTER TABLE tasks ADD COLUMN agent_id BIGINT;
ALTER TABLE tasks ADD CONSTRAINT tasks_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agents (id);
CREATE INDEX tasks_agent_id_idx ON tasks (agent_id);
```

Le tre decisioni sono le stesse di `V3`, e reggono per le stesse ragioni:

- **nullable**, perché ogni task esistente non ha un agente e non esiste un agente corretto a cui
  puntare. `NULL` qui dice una cosa vera: non ancora assegnato;
- **`ADD COLUMN` senza `DEFAULT`**, quindi metadata-only su PostgreSQL: istantanea, e non può
  finire a metà;
- **nessun `ON DELETE`**: `NO ACTION`. Un agente si disattiva, non si cancella (ADR-004 §3,
  ADR-008); se qualcuno raggiunge comunque un `DELETE` fuori dall'applicazione, il database lo
  rifiuta finché dei task puntano lì, e quel rifiuto è la conversazione che vogliamo.

## 6. Debito

| ID | Stato | Contenuto |
|---|---|---|
| **TD-13** | **RESOLVED** | «`Agent` e `Task` non si conoscono» — l'ultima metà del debito di dominio di TASK-000. Da oggi si conoscono. *(Sul conflitto di numerazione fra `docs/audit/TECHNICAL_DEBT.md` e la lista viva, vedi TASK-011: qui si intende il TD-13 dell'audit)* |
| **TD-34** | **OPEN — nuovo, MINOR** | Un task può puntare a un agente inattivo, ed è uno stato legale e necessario (D3). Nessun modo di chiedere «dammi i task fermi su agenti inattivi» senza incrociare due liste lato client. Si chiude con un filtro su `GET /api/tasks`, il giorno in cui un client lo pone |
| **TD-35** | **OPEN — nuovo, MINOR** | Nessun `DELETE` dell'associazione: un task assegnato non torna «non assegnato», si riassegna soltanto. Gemello di quello che ADR-005 §5 lasciò aperto sul progetto, e con più pressione |

## Alternative scartate

| Alternativa | Perché no |
|---|---|
| Permettere l'assegnazione a un agente inattivo | Crea un'obbligazione che nessuno può assolvere, senza semantica di coda che la renda leggibile. D1 |
| **Congelare** i task di un agente disattivato | Li intrappolerebbe con chi non può eseguirli, proprio quando serve riassegnarli. D3 |
| Far **fallire** `deactivate` finché ha lavoro assegnato | Introduce l'arco `agents → tasks` e chiude un ciclo nel lock graph (§3.4); e impedisce di togliere dal servizio un agente guasto. D3bis |
| Bloccare anche l'agente **di origine** | Nessuna regola dipende dal suo stato: un lock che non protegge nulla e un conflitto inventato. §3.2 |
| Un `@Version` o un ETag separato per l'associazione | Due verità sullo stesso fatto, e un client che deve sapere quale tag serve a quale rotta. §4 |
| `agentActive` o `agentStatus` in `TaskResponse` | Nessun invariante lo richiede; pubblicare un campo è irreversibile. ADR-006 §3 |
| Relazione bidirezionale, `Agent` con una collezione di task | Esattamente ciò che ADR-005 §7 evitò: rende la cascata un flag invece di una decisione |
| `DELETE /api/tasks/{id}/agent` | Si può aggiungere domani senza rompere nessuno; toglierlo dopo no. TD-35 |
