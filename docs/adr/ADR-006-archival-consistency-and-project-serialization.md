# ADR-006 — Coerenza di `archive`/`restore` verso i task, e il protocollo di lock sul progetto

- **Stato**: **Accettata — approvata dall'umano il 2026-09-12. Non ancora implementata.**
- **Data**: 2026-09-12 (revisione 3, di approvazione)
- **Task**: TASK-004 (scope **approvato**, implementazione non avviata)
- **Rapporto con le precedenti**: non supera nessuna ADR. Scioglie il punto che **ADR-005 §4**
  aveva esplicitamente rimandato. È la decisione che **chiuderà** TD-25 e la componente di
  ciclo di vita di TD-19 — che restano `OPEN` finché il codice non esiste. Si appoggia a
  ADR-004 §3 §4 §8 e a ADR-005 §3 §4 §6.

## Stato delle decisioni

Tutte approvate il 2026-09-12. Nessun punto resta in sospeso.

| § | Decisione | Stato |
|---|---|---|
| §1 | Archival consistency **derivata**, nessuna cascata materializzata | **Approvata** |
| §2 | Task in un progetto `ARCHIVED` congelato in scrittura, aperto in lettura | **Approvata** |
| §2 | `PUT` idempotente verso lo stesso progetto archiviato → `200` no-op | **Approvata** |
| §3 | Il contratto di `TaskResponse` **non cambia**; la scopribilità dello stato congelato è **fuori scope** | **Approvata** |
| §4 | Locking **pessimistico** sulla riga `projects`, formalizzato come protocollo L1–L7 | **Approvata** |
| §5 | Contabilità del debito: TD-25 chiuso, TD-19 risolto nella sola componente di ciclo di vita, TD-28 aperto, TD-29 **MINOR subordinato a TD-07**, TD-30 aperto | **Approvata** |
| §6 | Nessuna migrazione | **Approvata** |
| §7 | Failure semantics: nessun `503`, nessun timeout, nessun retry | **Approvata** |
| §8 | Limiti dichiarati del modello, incluso il last-write-wins fra riassegnazioni concorrenti dello stesso task | **Approvata** |

## Contesto

ADR-005 §4 ha lasciato una frase sospesa: «archiviare un progetto non tocca i suoi task. Non
ancora, e non per omissione». La conseguenza dichiarata era uno stato intermedio: un progetto
archiviato può avere task attaccati, e quei task «restano leggibili e — oggi — modificabili per
tutto ciò che non è la loro appartenenza».

Oggi quello stato intermedio produce una incoerenza osservabile e concreta:

- `POST /api/tasks` con un `projectId` archiviato → `409` (ADR-005 §3);
- `PUT /api/tasks/{id}/project` **verso** un progetto archiviato → `409` (ADR-005 §3);
- `PUT /api/tasks/{id}/project` di un task **che sta dentro** un progetto archiviato → `200`.

Cioè: un contenitore fuori dal registro operativo rifiuta lavoro nuovo, ma non protegge il
lavoro che contiene. Lo stesso archivio è una barriera in entrata e una porta aperta in uscita.

ADR-005 §4 indicava anche il prerequisito tecnico: **TD-19** (nessun controllo di concorrenza
su `Project`) e **TD-25** (finestra fra il controllo «il progetto è `ACTIVE`» e il commit
dell'assegnazione) «vanno sciolti insieme, con lo stesso meccanismo», prima di dare ad
`archive` effetti che dipendono dallo stato del progetto.

Questa ADR propone le tre decisioni insieme, perché sono la stessa decisione vista da tre lati.

## Decisioni proposte

### 1. La cascata non scrive niente. Lo stato operativo di un task è **derivato** dal progetto

`POST /api/projects/{id}/archive` e `POST /api/projects/{id}/restore` continuano a scrivere
**una sola riga**: la propria. Nessuna riga di `tasks` viene aggiornata o marcata.

Quello che cambia non è il dato, è la regola: **un task il cui progetto è `ARCHIVED` non è
scrivibile**. La condizione si calcola in lettura, dalla riga del progetto, nel momento in cui
serve.

*Perché non materializzare.* Scrivere un flag su ogni task sarebbe la scelta ovvia e sarebbe
sbagliata qui, per tre ragioni che questo repository ha già messo per iscritto altrove:

| Ragione | Dove è già scritta |
|---|---|
| Non si registra nel database un'informazione che nessuno ha fornito, né si inventa un valore per soddisfare una struttura | ADR-005 §1 §2, sul progetto sintetico «Unassigned» e sulle righe non migrate |
| Con l'archiviazione «la decisione su cosa mostrare viene presa in lettura, dove è reversibile» | ADR-004 §3, testualmente |
| Uno stato espresso due volte può divergere | ADR-004, alternative scartate: `deleted_at` accanto a `status` |

*La ragione decisiva è il `restore`.* Una cascata materializzata deve ricordare che cosa ha
cambiato, altrimenti il `restore` non è l'inverso dell'`archive`: un task già archiviato per
conto suo prima dell'`archive` del progetto tornerebbe attivo dopo il `restore`. La
contabilità di quella memoria (`archived_by_project`, o una tabella di journal) è un
sottosistema nuovo, con la propria classe di bug, introdotto per ricostruire un'informazione
che non è mai andata persa. Con lo stato derivato l'inverso è esatto **per costruzione**: non
c'è niente da ricordare perché non si è dimenticato niente.

*Cosa questo costa.* Ogni scrittura su un task deve conoscere lo stato del suo progetto, cioè
risolvere la relazione. Le scritture caricano comunque il task: il costo è una colonna in più
nello stesso `join fetch` che ADR-005 §9 ha già stabilito, non una query in più.

### 2. Un task dentro un progetto archiviato: congelato in scrittura, aperto in lettura

| Operazione su un task il cui progetto è `ARCHIVED` | Esito proposto |
|---|---|
| `GET /api/tasks`, `GET /api/projects/{id}/tasks` | `200`, invariato |
| `PUT /api/tasks/{id}/project` verso un altro progetto | **`409` (nuovo)** |
| `PUT /api/tasks/{id}/project` verso lo stesso progetto archiviato | **`200`, no-op** |
| Qualunque scrittura futura su quel task | `409`, per costruzione — la regola sta sull'entità |

*Perché le letture restano aperte.* ADR-004 §8 vincola le **scritture**; ADR-005 §6 ha già
stabilito che un progetto archiviato risponde normalmente ai `GET`, perché «un progetto di cui
non si possono più leggere i task sarebbe archiviazione travestita da cancellazione».

*Perché `409` e non `403`.* Identica ad ADR-004 §8 e ADR-005 §3: il rifiuto dipende dallo
stato della risorsa, non dai permessi, ed è il chiamante stesso a poterlo togliere —
`restore` del progetto, e la stessa richiesta passa.

*Perché il caso idempotente è `200` e non `409`.* Il congelamento riguarda le **mutazioni**.
Un `PUT` che chiede lo stato in cui il task già si trova non muta niente: non c'è nessuna
scrittura da rifiutare, e ADR-005 §5 ha già stabilito che un `PUT` sull'associazione «dichiara
lo stato finale desiderato, e se è già quello la richiesta ha ragione». L'asimmetria che ne
risulta — `200` per la conferma, `409` per lo spostamento — è veritiera: niente è cambiato,
quindi niente è stato rifiutato.

*Perché non c'è una via d'uscita diretta.* Spostare un task fuori da un progetto archiviato
senza passare da `restore` sarebbe esattamente la porta aperta descritta nel contesto. Il
flusso corretto è `restore` → `PUT` → `archive`, gli stessi tre passi che ADR-004 §8 ha già
accettato per modificare un progetto archiviato. Non è un flusso nuovo: è lo stesso flusso
applicato a una risorsa in più.

*Perché la regola sta sull'entità `Task`.* Identica ad ADR-004 §4, ADR-004 §8 e ADR-005 §3: un
punto d'ingresso futuro — importer, Planner, agente — non può dimenticare una regola che non
ha modo di aggirare. `project` non ha un setter, e `assignTo` è l'unico varco.

### 3. Il contratto pubblico non cambia

`TaskResponse` resta **identico** a quello di TASK-003: `id`, `title`, `description`, `status`,
`priority`, `projectId`. Nessun campo nuovo.

*Perché.* La revisione 1 di questa ADR proponeva un campo `projectStatus`. Non è necessario a
nessun invariante e a nessun acceptance criterion di questa task: il congelamento è una regola
di scrittura, e il suo effetto è già interamente osservabile dal `409`. Aggiungere un campo al
contratto perché «sarebbe comodo» è precisamente il tipo di allargamento che ADR-005 §5 ha
rifiutato per `DELETE /api/tasks/{id}/project`: pubblicarlo è irreversibile, non pubblicarlo no.

*Conseguenza da conoscere, dichiarata — e la scopribilità è **fuori scope**, deciso.* Un client
non può sapere da `GET /api/tasks` se un task è congelato: lo scopre dal `409`, oppure leggendo
`GET /api/projects/{id}`, che quello stato ce l'ha già. È una domanda di **scopribilità**, non
di correttezza. **Non entra in TASK-004 in nessuna forma**: né `projectStatus`, né `archived`,
né un campo derivato di altro nome. Si affianca alle domande di contratto già aperte in
`PROJECT_STATE.md` — nessun filtro «task senza progetto», nessun `DELETE` dell'associazione — e
va decisa insieme a quelle, quando esisterà un client reale che la pone.

### 4. Il protocollo di lock: **la riga del progetto è il punto di serializzazione**

Una regola sola, enunciata per esteso perché ogni percorso di scrittura futuro dovrà
applicarla senza interpretarla.

> **L1 — Chi cambia lo stato prende il lock esclusivo.**
> Ogni percorso che scrive una riga di `projects` acquisisce `PESSIMISTIC_WRITE`
> (`SELECT … FOR UPDATE`) su quella riga **prima** di leggerne lo stato, e la tiene fino al
> commit.
>
> **L2 — Chi dipende dallo stato prende il lock condiviso.**
> Ogni percorso di **scrittura** la cui correttezza dipende da `Project.status` acquisisce
> `PESSIMISTIC_READ` (`SELECT … FOR SHARE`) su **ogni** riga di progetto da cui dipende,
> **prima** di deciderlo, e la tiene fino al commit.
>
> **L3 — Creazione e assegnazione da `NULL`: un solo progetto.**
> `POST /api/tasks` con `projectId` e `PUT /api/tasks/{id}/project` su un task senza progetto
> dipendono da un progetto solo, la **destinazione**: un `PESSIMISTIC_READ` sulla destinazione.
> `POST /api/tasks` senza `projectId` non dipende da nessun progetto e non prende nessun lock.
>
> **L4 — Riassegnazione A → B: due progetti.**
> `PUT /api/tasks/{id}/project` su un task già in un progetto dipende da **entrambi**: da A
> perché un task in un progetto archiviato è congelato (§2), da B perché un progetto
> archiviato non riceve lavoro nuovo (ADR-005 §3). `PESSIMISTIC_READ` su **tutti e due**.
> Se A == B l'insieme si riduce a una riga sola, e il lock è uno solo.
>
> **L5 — Acquisizione multi-riga in ordine deterministico.**
> Quando l'insieme delle righe da bloccare ha più di un elemento, lo si costruisce, lo si
> **deduplica**, lo si **ordina per `id` crescente** e lo si blocca in quell'ordine — sempre,
> indipendentemente dal ruolo che ciascuna riga ha nella richiesta. Due richieste che bloccano
> lo stesso insieme non possono aspettarsi a vicenda.
>
> **L6 — Le letture non bloccano.**
> Nessun `GET` acquisisce lock. Una lettura non ritarda mai una transizione, e viceversa.
>
> **L7 — Clausola di chiusura.**
> Il protocollo è **universale**: ogni percorso di scrittura, presente o futuro, la cui
> correttezza dipende da `Project.status` lo applica. Non esistono eccezioni «tanto questo
> caso è innocuo»: è precisamente il ragionamento che ha prodotto TD-25.

Applicato ai percorsi di scrittura che esistono oggi, il protocollo è interamente determinato —
non resta niente da decidere caso per caso:

| Percorso di scrittura | Dipende da `Project.status`? | Regole | Righe bloccate |
|---|---|---|---|
| `POST /api/projects` | no (la riga non esiste ancora) | — | nessuna |
| `PUT /api/projects/{id}` | sì — un archiviato è immutabile (ADR-004 §8) | L1 | `{id}` `FOR UPDATE` |
| `POST /api/projects/{id}/archive` | sì — transizione legale (ADR-004 §4) | L1 | `{id}` `FOR UPDATE` |
| `POST /api/projects/{id}/restore` | sì | L1 | `{id}` `FOR UPDATE` |
| `POST /api/tasks` senza `projectId` | no | L6 | nessuna |
| `POST /api/tasks` con `projectId` | sì — destinazione non archiviata | L2, L3 | `{target}` `FOR SHARE` |
| `PUT /api/tasks/{id}/project`, task senza progetto | sì | L2, L3 | `{target}` `FOR SHARE` |
| `PUT /api/tasks/{id}/project`, task in A → B | sì — A congelato, B non archiviato | L2, L4, L5 | `{A, B}` `FOR SHARE`, id crescente |
| `PUT /api/tasks/{id}/project`, A → A | sì | L2, L4 | `{A}` `FOR SHARE` |
| Ogni `GET` | — | L6 | nessuna |

*Perché questo chiude TD-25.* `FOR SHARE` e `FOR UPDATE` sono incompatibili: l'assegnazione e
l'archiviazione dello stesso progetto non possono più sovrapporsi. I due ordini possibili
producono entrambi uno stato legale:

- assegnazione prima: l'`archive` aspetta il commit, poi archivia un progetto che ha un task
  in più. È lo stato che ADR-005 §4 ammette esplicitamente;
- `archive` prima: l'assegnazione, sbloccata, rilegge la riga — sotto `READ COMMITTED` un
  `SELECT … FOR SHARE` vede l'ultima versione committata — trova `ARCHIVED` e risponde `409`.

L'invariante che ne risulta è verificabile e netta: **al commit di un'assegnazione, ogni
progetto da cui quell'assegnazione dipendeva era nello stato su cui la decisione è stata
presa**. Non «è stato controllato», *era*.

*Perché chiude la componente di ciclo di vita di TD-19.* Due `archive` concorrenti si
serializzano sul `FOR UPDATE`: il secondo rilegge `ARCHIVED` e `Project.archive()` produce il
`409` che ADR-004 §4 prescrive. Oggi rispondono `200` entrambi.

*Perché condiviso e non esclusivo in L2.* Due task assegnati allo stesso progetto attivo non
sono in conflitto fra loro. Un `FOR UPDATE` li serializzerebbe uno a uno, creando un collo di
bottiglia per progetto e un conflitto falso; `FOR SHARE` li lascia passare insieme e blocca
solo chi vuole cambiare lo stato. La distinzione fra L1 e L2 **è** la distinzione fra i due
ruoli, non un dettaglio di ottimizzazione — ed è per questo che si chiama un protocollo solo e
non due meccanismi.

*Perché L5 anche se oggi non serve.* Con due soli lock condivisi, che fra loro non
conflittano, un deadlock oggi non è raggiungibile: l'ordinamento non ha un fallimento da
prevenire in questa task. È scritto lo stesso perché il primo percorso futuro che prenderà due
lock **esclusivi** — o un esclusivo e un condiviso su righe diverse — lo troverà già stabilito
invece di doverlo scoprire. Una regola di ordinamento aggiunta dopo il primo deadlock è una
regola aggiunta dopo un incidente.

*Perché pessimistico e non `@Version`.* L'ottimistico è il riflesso, e qui è la scelta
peggiore su tre assi:

| Asse | `@Version` su `Project` | Protocollo di lock |
|---|---|---|
| TD-25 | Non lo chiude: l'assegnazione **non scrive** il progetto, quindi non c'è versione da confrontare. Servirebbe `OPTIMISTIC_FORCE_INCREMENT`, che fa scrivere la riga del progetto a ogni assegnazione e trasforma due assegnazioni concorrenti — che non sono in conflitto — in un `409` | La chiude, deterministicamente |
| Schema | Richiede una migrazione (`ADD COLUMN version`) e un valore per ogni riga esistente | **Nessuna migrazione** |
| Contratto | Introduce fallimenti a commit da tradurre, e un `409` che il chiamante deve saper ritentare | Il chiamante aspetta e poi ha una risposta definitiva |

*Il costo dichiarato.* Un `archive` aspetta le assegnazioni in corso su quel progetto. Le
transazioni in gioco sono brevi e toccano una riga di `projects` e una di `tasks`. Si blocca un
solo tipo di riga, sempre `projects`, sempre in ordine di id crescente (L5).

### 5. Contabilità del debito: che cosa si chiude, che cosa si apre, e con quale nome

La revisione 1 diceva «TD-19 si chiude a metà». È una formulazione che non si può tracciare:
un debito o è risolto o è aperto. La contabilità corretta separa le due componenti che il
testo originale di TD-19 teneva insieme.

TD-19 (rilievo F-5 della review di TASK-002) affermava due cose distinte:

| Componente di TD-19 | Contenuto originale | Esito con TASK-004 |
|---|---|---|
| **(a) Ciclo di vita** | «due `archive` concorrenti rispondono entrambi `200` invece che `200` + `409`»; e, in prospettiva, il lost update sui figli quando `archive` acquisterà effetti | **RISOLTA.** L1 serializza le transizioni; §1 fa sì che `archive` non scriva figli, quindi il lost update che TD-19 anticipava non può proprio esistere |
| **(b) Sovrascrittura su `PUT`** | «due `PUT` concorrenti si sovrascrivono in silenzio» | **NON risolta.** Estratta e ritracciata come **TD-28** |

Quindi, alla chiusura di TASK-004:

| ID | Stato proposto | Nota |
|---|---|---|
| **TD-25** | **CLOSED** | Chiuso interamente da L2+L3+L4. Nessun residuo |
| **TD-19** | **RESOLVED — componente (a), ciclo di vita** | Il testo del debito va aggiornato dichiarando che (b) è uscita verso TD-28. Senza questa riga la tracciabilità si perde |
| **TD-28** | **OPEN — nuovo** | «`PUT /api/projects/{id}` resta esposto alla sovrascrittura con dati stantii. Il lock di riga lo serializza ma non lo rileva: il secondo scrittore sovrascrive con un corpo composto senza conoscere il primo. Richiede concorrenza ottimistica **nel contratto HTTP** — `ETag`/`If-Match`, o un numero di versione esposto — cioè una decisione sull'API, non sul database. Da affrontare quando esisterà più di un client concorrente reale.» |
| **TD-24** | **RESOLVED oppure OPEN, secondo l'esito di un acceptance criterion verificabile** | Vedi le conseguenze operative e AC-18 |
| **TD-29** | **OPEN — MINOR, subordinato a TD-07** | «**Assenza di un contratto API normalizzato per gli errori infrastrutturali di concorrenza e locking.** Un deadlock o una cancellazione amministrativa si presenta al client con la forma di errore di default, come ogni altro errore infrastrutturale oggi. Non è una regressione — prima non c'erano lock — e **non implica timeout, retry o `503`**: è una questione di *forma della risposta*, non di policy. Si chiude con TD-07, dentro la normalizzazione del contratto di errore, e non prima né separatamente.» |
| **TD-30** | **OPEN — nuovo, MINOR** | Limite dichiarato del modello: due riassegnazioni concorrenti **dello stesso task** restano last-write-wins. Vedi §8 |

Il punto che rende questa contabilità corretta e non cosmetica: **(b) non è un pezzo di TD-19
lasciato indietro da TASK-004, è un problema di un'altra natura** — riguarda il contratto HTTP
e non la coerenza fra entità — e tenerlo sotto lo stesso identificatore avrebbe fatto sembrare
risolto qualcosa che non lo è, o aperto qualcosa che lo è.

### 6. Nessuna migrazione di database

Non serve, e la sua assenza è un risultato, non un'omissione:

- nessuna colonna nuova (§1: niente si materializza; §3: il contratto non cambia);
- nessun vincolo nuovo: «nessun task in un progetto archiviato» sarebbe comunque **falso** come
  invariante — un progetto si archivia liberamente con i suoi task dentro (ADR-005 §4);
- il lock è una modalità di `SELECT`, non un oggetto di schema.

Lo schema resta a **`V3`**. `SchemaMigrationTest`, `MigrationStreamTest` e
`DevSeedMigrationTest` non cambiano, e nessun database esistente va migrato.

### 7. Failure semantics

| Situazione | Stato | Forma | Nuovo? |
|---|---|---|---|
| Task inesistente | `404` | `ProblemDetail` | no |
| Progetto (origine o destinazione) inesistente | `404` | `ProblemDetail` | no |
| Assegnazione **verso** un progetto `ARCHIVED` | `409` | `ProblemDetail` — «Archived project cannot receive tasks» | no |
| Spostamento di un task **dentro** un progetto `ARCHIVED` | `409` | `ProblemDetail`, titolo distinto | **sì** |
| `PUT` verso lo stesso progetto archiviato (no-op) | `200` | corpo invariato | **sì** |
| Secondo `archive`/`restore` concorrente | `409` | `ProblemDetail` | garanzia nuova: oggi è `200` |
| Fallimento di lock (deadlock, cancellazione amministrativa) | invariato rispetto a oggi | forma di errore di default | **non gestito, dichiarato** |

*Perché due `409` distinti e non uno.* «Il progetto di destinazione è archiviato» e «il task
che stai spostando è in un progetto archiviato» richiedono due azioni diverse dal chiamante:
il `restore` di **due progetti diversi**. Stesso codice, `title` e `detail` diversi.

*Ordine dei controlli, quando entrambi si applicano.* Prima l'origine, poi la destinazione: il
task è congelato prima ancora che si guardi dove sta andando. Deterministico, e asserito da un
test.

*Perché nessun `503`, nessun timeout, nessun retry.* TASK-004 usa **lock bloccanti ordinari**:
nessun `lock_timeout` applicativo, nessuna policy di ritentativo, nessun codice di stato nuovo.
Un chiamante che attende è un chiamante che poi riceve una risposta definitiva, ed è il
comportamento corretto per un'attesa che dura quanto una transazione breve.

Quello che resta scoperto è più stretto di una policy, ed è bene non confonderlo con una:
**la forma della risposta** quando un errore infrastrutturale di locking affiora comunque
(deadlock, cancellazione amministrativa della query). Oggi affiora come ogni altro errore
infrastrutturale — con la forma di default — perché l'API non ha ancora un contratto di errore
normalizzato. È esattamente il perimetro di **TD-07**, e per questo **TD-29 è MINOR e
subordinato a TD-07**: si chiude lì dentro, insieme a TD-20 e TD-27, non da solo e non con un
timeout.

### 8. Limite dichiarato: il protocollo serializza il **progetto**, non il **task**

Il protocollo L1–L7 blocca righe di `projects`. Non blocca righe di `tasks`, e questo ha una
conseguenza che va detta per intero perché è facile crederla coperta:

> **Due riassegnazioni concorrenti dello stesso task — `A → B` e `A → C` — restano
> last-write-wins.** Entrambe superano il protocollo correttamente, perché entrambe prendono
> `FOR SHARE` su `A` e sulla propria destinazione, e nessuno dei due lock è in conflitto con
> l'altro. Il task finisce in `B` o in `C` a seconda di chi committa per ultimo, e nessuno dei
> due chiamanti riceve un errore.

**Non è TD-25, e la differenza non è una sfumatura.** TD-25 era una violazione di invariante:
un task poteva finire attaccato a un progetto che era `ARCHIVED` al momento del commit, cioè
uno stato che il dominio dichiara impossibile. Qui non c'è nessun invariante violato: `B` e `C`
sono entrambi progetti attivi, entrambe le destinazioni sono legali, e lo stato finale è uno
stato valido. È una **corsa fra due chiamanti che vogliono cose diverse**, non un buco nelle
regole.

*Che cosa TASK-004 garantisce, esattamente.* La coerenza fra `Project.status` e le scritture
sui task: al commit di ogni scrittura, ogni progetto da cui quella scrittura dipendeva era nello
stato su cui la decisione è stata presa. **Non** garantisce concorrenza ottimistica o
pessimistica **sul task stesso**.

*Perché non si chiude qui.* Chiuderla richiede un controllo di concorrenza sull'entità `Task` —
`@Version` sul task, o un `If-Match` sull'associazione — cioè la stessa classe di decisione di
TD-28, presa sull'altra entità, con una migrazione o un cambio di contratto al seguito.
Entrambe sono fuori dallo scope approvato. Allargare qui significherebbe introdurre su `Task`
il meccanismo che §4 ha deliberatamente rifiutato su `Project`.

*Tracciato come debito, perché questo repository traccia i limiti noti.* **TD-30 — MINOR**:
«due riassegnazioni concorrenti dello stesso task restano last-write-wins. TASK-004 garantisce
la coerenza fra `Project.status` e le scritture sui task, non la concorrenza sul task stesso.
Distinto da TD-25, che era una violazione di invariante e non una corsa fra chiamanti. Parente
di TD-28: si chiude con un controllo di concorrenza sull'entità o sul contratto, quando
esisterà più di un client concorrente reale.»

## Alternative scartate

| Alternativa | Perché no |
|---|---|
| Cascata materializzata (`archived_at` o flag sui task) | Impone un sottosistema di memoria perché `restore` sia l'inverso di `archive`, e registra un'informazione che nessuno ha fornito (§1) |
| `@OneToMany(cascade = …)` su `Project` | Esattamente ciò che ADR-005 §4 ha evitato: rende la decisione un flag. E con lo stato derivato non serve nessuna collezione |
| Nessuna regola: i task di un progetto archiviato restano scrivibili | È lo stato di oggi, ed è l'incoerenza che questa task esiste per chiudere |
| Spostare un task fuori da un progetto archiviato senza `restore` | Riapre in uscita la porta che l'archiviazione chiude in entrata (§2) |
| `409` anche sul `PUT` idempotente verso lo stesso progetto archiviato | Rifiuta una richiesta che non muta niente, contro ADR-005 §5 (§2) |
| `403` invece di `409` | Il rifiuto dipende dallo stato, non dai permessi, ed è reversibile dal chiamante (ADR-004 §8) |
| `projectStatus` (o `archived`) in `TaskResponse` | Nessun invariante e nessun AC lo richiede; pubblicare un campo è irreversibile, non pubblicarlo no (§3) |
| `@Version` su `Project` | Non chiude TD-25, richiede una migrazione, e trasforma assegnazioni non in conflitto in `409` (§4) |
| `SERIALIZABLE` come livello di isolamento | Sposta il problema su un retry loop applicativo per tutta l'applicazione, per risolvere un conflitto che riguarda una riga |
| `FOR UPDATE` anche in L2 | Collo di bottiglia per progetto e conflitto falso fra assegnazioni che non si toccano (§4) |
| Lock condiviso sulla sola destinazione in una riassegnazione | Lascia scoperta l'origine, cioè proprio la riga da cui dipende la regola nuova di §2 (L4) |
| Lock applicativo (`synchronized`, lock in memoria) | Falso con più di un processo, ed è esattamente la classe di errori che la riga di database non ha |
| `503 + Retry-After` per il fallimento di lock | Policy pubblica nuova, fuori scope; appartiene a TD-07. Tracciata come TD-29 (§7) |
| Dichiarare «TD-19 chiuso a metà» | Non è uno stato tracciabile. Le due componenti si separano: (a) risolta, (b) → TD-28 (§5) |

## Conseguenze operative

- **Cambio di contratto osservabile**, su un endpoint pubblicato da TASK-003:
  `PUT /api/tasks/{id}/project` passa da `200` a `409` quando il task si trova dentro un
  progetto archiviato **e la richiesta lo sposta**. La conferma idempotente resta `200`.
- **La forma delle risposte non cambia**: `TaskResponse` è identico a quello di TASK-003.
- Lo schema resta a `V3`. Nessuna migrazione, nessun nuovo vincolo.
- **TD-25 chiuso**; **TD-19 risolto nella componente (a)**, con il testo del debito da
  aggiornare; **TD-28** aperto, **TD-29** aperto come MINOR subordinato a TD-07, **TD-30**
  aperto come MINOR (§5, §8).
- **TD-24**: si chiude solo se l'implementazione elimina davvero il self-invocation di un
  metodo `readOnly`, e la cosa è verificata da un test strutturale (AC-18). Se il test non si
  scrive, o non si riesce a farlo fallire per mutazione, **TD-24 resta aperto e lo si
  dichiara**. Non si chiude un debito perché il codice «adesso sembra diverso».
- **TD-26** (path lazy non esercitato) resta aperto e diventa marginalmente più rilevante: la
  nuova guardia legge `task.getProject()`. Legge sempre dentro la transazione del service, che
  è dove ADR-005 §9 ha già confinato l'entità, quindi non introduce un percorso nuovo.
- Un client non ha modo di sapere in anticipo che un task è congelato (§3): **fuori scope,
  deciso**, non una domanda lasciata aperta dentro TASK-004.
- Due riassegnazioni concorrenti dello stesso task restano last-write-wins (§8, TD-30). È un
  limite del perimetro, non un difetto dell'implementazione.
- I `GET` non prendono lock (L6): una lettura non rallenta mai un'archiviazione e viceversa.
- Le asserzioni di concorrenza richiedono test a due thread contro PostgreSQL reale, con
  transazioni controllate a mano. Devono essere verificati per mutazione — un test di
  concorrenza che non è stato visto fallire non dimostra niente.

## Questioni chiuse in approvazione

Nessuna questione resta aperta su questa ADR. I due punti che la revisione 2 lasciava in
sospeso sono stati decisi il 2026-09-12:

1. **TD-29 registrato**, come **MINOR subordinato a TD-07**, e riformulato: riguarda solo
   l'assenza di un contratto API normalizzato per gli errori infrastrutturali di concorrenza e
   locking. Nessun timeout, nessun retry, nessun `503` (§5, §7).
2. **La scopribilità dello stato congelato è fuori scope**, in via definitiva per questa task:
   nessun `projectStatus` e nessun campo equivalente in `TaskResponse` (§3).

Un solo esito resta **condizionale**, e non è una questione aperta ma un criterio da
verificare: **TD-24** si chiude se e solo se AC-18 passa con prova per mutazione. Altrimenti
resta `OPEN` e lo si dichiara, **senza bloccare la chiusura di TASK-004**.
