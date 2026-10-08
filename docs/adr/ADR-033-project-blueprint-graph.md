# ADR-033 — Project Blueprint: il grafo dei componenti di un progetto, leggibile e modificabile

- **Stato**: Accettata e implementata (PHASE 28)
- **Data**: 2026-09-29
- **Decisa da**: agente, su direttiva umana del 2026-09-29 («Project Blueprint Graph»)
- **Relazione con ADR-030**: la **estende, non la sostituisce**. Il grafo del codice di ADR-030 resta
  com'è — file e import, calcolato a richiesta e scritto in `.aicos/` — e la scheda *Codice* continua a
  mostrarlo. Il Blueprint è un secondo livello: **componenti** (classi, endpoint, servizi, componenti
  React…) e **relazioni con un significato** (usa, chiama, implementa, legge…), **persistiti** e
  **modificabili** dall'operatore.

## 1. Il problema

La direttiva chiede, per ogni progetto, una vista a blocchi collegati «stile Blueprint» che documenti
la struttura tecnica, si sincronizzi con il codice e sia modificabile — senza che una modifica visuale
riscriva mai codice reale in modo implicito.

Tre richieste si tirano fra loro: *derivato dal codice* (quindi rigenerabile), *modificabile a mano*
(quindi da conservare), *mai distruttivo sul codice* (quindi ogni modifica che toccherebbe un file
diventa una proposta). Il modello dati esiste per tenere queste tre cose separate.

## 2. Modello dati (`V26`, additiva: cinque tabelle nuove)

| Tabella | Contenuto |
|---|---|
| `blueprint_nodes` | Un componente. `node_key` stabile per progetto (`java:com.acme.TaskService`, `ts:web/src/App.tsx#App`, `manual:<uuid>`), `kind`, `origin` (`DETECTED`/`MANUAL`), `status` (ciclo di vita scelto dall'operatore), `path_state` (che rapporto c'è fra il percorso e un file reale), nome, percorso, linguaggio, descrizione, pseudocodice, note, tag, **struttura** (JSON), **istantanea rilevata** (JSON), **override** (insieme di campi), righe, genitore ed `expanded` per l'espansione progressiva, `version` |
| `blueprint_edges` | Una relazione orientata: `source_node_id` → `target_node_id`, `relation`, `origin` (`DETECTED`/`MANUAL`/`PROPOSED`), etichetta, descrizione, **riferimento al codice** (`file:riga` più la riga), `version`. Unica per (sorgente, destinazione, relazione) |
| `blueprint_layouts` | Le posizioni sul canvas, una riga per progetto, **separate dai nodi** (§6) |
| `blueprint_scans` | La storia delle scansioni: quando, chi, quanti file, nodi e archi rilevati, aggiunti, spariti, relazioni non risolte |
| `blueprint_change_proposals` | Le **Proposed Code Change** (§7): file, motivo, conseguenze, diff, stato, task generata |

Vocabolari **chiusi**, imposti due volte (enum Java + `CHECK`), come ogni altro vocabolario del
progetto (ADR-004 §2, ADR-011):

- **21 tipi di nodo**: `FILE`, `CLASS`, `INTERFACE`, `ENUM`, `FUNCTION`, `CONTROLLER`, `SERVICE`,
  `REPOSITORY`, `DTO`, `ENTITY`, `CONFIGURATION`, `DATABASE`, `ENDPOINT`, `COMPONENT`, `HOOK`, `STORE`,
  `TEST`, `SCRIPT`, `SNIPPET`, `DOCUMENT`, `CUSTOM`;
- **19 relazioni**: `IMPORTS`, `USES`, `CALLS`, `EXTENDS`, `IMPLEMENTS`, `READS`, `WRITES`, `CREATES`,
  `CONTAINS`, `BELONGS_TO`, `VALIDATES`, `EMITS`, `SUBSCRIBES`, `MAPS_TO`, `ENDPOINT_TO_SERVICE`,
  `SERVICE_TO_REPOSITORY`, `ENTITY_TO_TABLE`, `TEST_COVERS`, `CUSTOM`;
- `status`: `PLANNED`, `IN_PROGRESS`, `IMPLEMENTED`, `DEPRECATED`;
- `path_state`: `LINKED` (il file esiste), `TO_CREATE`, `DOCUMENT_REFERENCE`, `VIRTUAL`, `MISSING`
  (un componente rilevato che l'ultima scansione non ha più trovato).

Il **livello** (Backend, Frontend, Database, Test, Docs) e la **famiglia** di un tipo (File, Classi,
Metodi, API, Dati, Test, Custom) sono **derivati** dal tipo, dal linguaggio e dal percorso nel dominio
Java (`BlueprintVocabulary`), e la console li riceve da `GET /api/blueprint/vocabulary`: nessuna
classificazione vive nel frontend, che decide solo colori e icone.

Le FK verso `projects` **non hanno `ON DELETE`**, come ogni altra FK dello stream: l'eliminazione reale
di un progetto (ADR-027) cancella il blueprint in modo esplicito e lo conta nell'impatto. Le FK
**interne** al blueprint (arco → nodi, metodo → classe) sono invece `ON DELETE CASCADE`, ed è una scelta
dichiarata: un arco è una proprietà dei suoi due estremi, non un'associazione con vita propria.

## 3. Rilevato, manuale, proposto

La distinzione che la direttiva chiede di vedere è **un dato**, non uno stile:

| | Nodo | Arco |
|---|---|---|
| `DETECTED` | Prodotto dalla scansione; il percorso è un fatto e non si modifica; nome, tipo, descrizione e pseudocodice si possono **sovrascrivere** | Prodotto dalla scansione; si annota ma non si elimina (tornerebbe alla scansione successiva) |
| `MANUAL` | Creato dall'operatore; ogni campo è suo, struttura compresa | Creato dall'operatore trascinando una porta |
| `PROPOSED` | — | Suggerito (analisi o agente) e **non ancora confermato**: `confirm` lo rende `MANUAL`, eliminarlo è rifiutarlo |

**Override.** Quando l'operatore modifica un campo derivato di un nodo rilevato, il campo entra in
`overrides` e la scansione non lo tocca più; il valore rilevato resta nell'istantanea, e `revert` lo
riporta. La console mostra accanto a ogni campo *Rilevato dal codice* o *Modifica manuale*.

## 4. La sincronizzazione (codice → grafo)

`POST /api/projects/{id}/blueprint/scan` legge la cartella del progetto e riconcilia per `node_key`:

1. un nodo rilevato che esiste già è aggiornato **nei soli campi non sovrascritti**; uno nuovo è
   inserito e riceve una posizione dal layout del server;
2. un nodo rilevato che la scansione non trova più **non viene cancellato**: passa a `MISSING`, perché
   può portare note, tag e archi manuali dell'operatore. Si elimina a mano;
3. gli archi rilevati sono **sostituiti**: sono fatti derivati, e un fatto che il codice non afferma
   più non va conservato. Archi manuali e proposti non si toccano;
4. un nodo manuale con un percorso che adesso esiste diventa `LINKED` — «se il percorso è reale, il
   sistema prova ad associarlo»;
5. un blocco che il codice non ha cambiato **non viene scritto**: la sua versione resta, e un
   Inspector aperto su di lui non riceve un `412` per colpa di una scansione (verificato per mutazione).

La scansione **non compila né esegue nulla** e ha gli stessi limiti di ADR-030 (4000 file, 512 KB per
file, cartelle generate saltate).

## 5. Lo scanner: un plugin per linguaggio

`BlueprintLanguagePlugin` è l'interfaccia (`supports(path)`, `analyze(file) → componenti`); il resto —
risoluzione dei riferimenti fra file, riconciliazione, layout — è comune. Due livelli di fedeltà,
dichiarati nel risultato (`fidelity`):

- **Java: AST** (JavaParser 3.28, livello di linguaggio 21). Package, import, classi, interfacce, enum,
  record, `extends`, `implements`, campi, **iniezione per costruttore**, metodi con firma e visibilità,
  annotazioni, eccezioni dichiarate e lanciate, stereotipi Spring (`@RestController`, `@Service`,
  `@Repository`/`JpaRepository`, `@Entity` con `@Table`, `@Configuration`), **endpoint** (metodo HTTP,
  rotta composta con il prefisso del controller, DTO di richiesta e risposta, servizi chiamati, codici di
  stato), chiamate `campo.metodo(...)` su dipendenze note, `new` di tipi del progetto, test JUnit e il
  componente che coprono. **Pseudocodice** generato dall'AST dei corpi dei metodi (`IF`, `FOR EACH`,
  `CALL`, `REJECT`, `RETURN`).
- **TypeScript/JavaScript e Python: lessicale**, sulla base di ADR-030 — componenti React, hook, store,
  funzioni e classi esportate, test, import risolti. Un parser AST per questi linguaggi è un plugin
  futuro, e il punto di estensione esiste.

Migrazioni SQL (`db/migration/V*.sql`): `CREATE TABLE` diventa un nodo `DATABASE`, e un'entità JPA con
`@Table(name=…)` ne riceve un arco `ENTITY_TO_TABLE`.

## 6. Layout separato dal contenuto

Le posizioni stanno in `blueprint_layouts`, non sui nodi. Se stessero sui nodi, trascinare un blocco
cambierebbe la versione del nodo e l'Inspector aperto sullo stesso nodo riceverebbe un `412` a ogni
salvataggio: una modifica di presentazione non deve invalidare una modifica di contenuto. Il layout ha
la sua versione e il suo `If-Match`.

Il layout automatico è calcolato **dal server** (`BlueprintLayoutEngine`): colonne per livello
architetturale da sinistra a destra — test, frontend, endpoint e controller, servizi, repository,
entità, database, configurazione e documenti — perché la direzione degli archi (uscite a destra,
ingressi a sinistra) coincida con la direzione delle dipendenze; dentro ogni colonna, quattro passate
di baricentro riducono gli incroci. Una colonna alta si divide in sotto-colonne, e quante righe ne
tiene dipende dalla dimensione del Blueprint (fra 6 e 40, per un canvas di proporzioni da schermo):
la prima versione, a 18 righe fisse, sul repository stesso produceva un nastro largo venti schermi.
L'altezza di riga contiene un blocco al massimo dettaglio di zoom, così avvicinarsi non sovrappone i
blocchi — anche questo trovato dal vivo. Deterministico, quindi verificabile da un test.

## 7. Modalità lettura e modifica; nessuna scrittura implicita di codice

Il blueprint scrive **solo le proprie tabelle**. Nessun endpoint di questa ADR scrive un file
sorgente del progetto. Ciò che toccherebbe il codice diventa una **Proposed Code Change**:

- `CREATE_FILE` — un nodo manuale in stato `TO_CREATE`: il file, uno scheletro per il suo linguaggio, il
  diff di creazione;
- `ADD_DEPENDENCY` — un arco manuale `USES`/`CALLS` fra due classi Java rilevate: il campo `final`,
  l'import e il parametro del costruttore, come diff unificato sul file sorgente reale;
- `CUSTOM` — una proposta scritta dall'operatore (file, diff, motivo, conseguenze).

Accettare una proposta **non applica il diff**: crea una **task** nel progetto con il diff, il motivo e
le conseguenze nella descrizione, che poi segue il flusso già esistente — assegnazione, esecuzione da
un agente o handoff, review umana (ADR-016, ADR-021, ADR-022). È il workflow di governance esistente a
modificare il codice, non il grafo.

## 8. Azioni AI

`POST …/nodes/{nodeId}/ai-actions` con `EXPLAIN_COMPONENT`, `GENERATE_PSEUDOCODE`,
`DETECT_MISSING_RELATIONS`, `EXPLAIN_DEPENDENCY`, `SUGGEST_REFACTOR`, `FIND_ARCHITECTURAL_ISSUE`,
`GENERATE_DOCUMENTATION`, `CREATE_IMPLEMENTATION_TASK` crea una **task** con il contesto del
componente (tipo, percorso, struttura, relazioni in entrata e in uscita, istruzioni). Nessuna chiamata
diretta a un modello da questo modulo: l'esecuzione passa dall'orchestratore e quindi dal governo dei
costi (ADR-031) e dalla review. Il catalogo delle azioni sta nel vocabolario.

La **generazione deterministica** del pseudocodice (dall'AST) è invece locale e immediata:
`POST …/nodes/{nodeId}/pseudocode`.

## 9. Contratto

Tutte le rotte sotto `/api/projects/{id}/blueprint` e `/api/blueprint/vocabulary`, autenticate
(ADR-013/024), errori dentro ADR-007, `If-Match` su ogni mutazione di una riga esistente (ADR-009,
`PreconditionCoverageTest`). Un progetto archiviato si legge ma non si modifica (`409
archived-project-is-immutable`, ADR-004 §8). Problemi nuovi: `blueprint-node-not-found`,
`blueprint-edge-not-found`, `blueprint-proposal-not-found`, `blueprint-derived-element` (eliminare ciò
che il codice afferma), `blueprint-proposal-decided`.

**Nomi degli schemi.** springdoc nomina gli schemi con il nome semplice della classe: i primi
record di questa ADR (`Edge`, `Graph`) sovrascrivevano in silenzio gli schemi omonimi del grafo del
codice (ADR-030) e cambiavano l'`operationId` di `/api/graph`. Ogni tipo esposto porta quindi il
prefisso `Blueprint` (o `Code…` per la struttura), e il contratto cambia solo per aggiunta (TD-59).

## 10. Che cosa non fa

- Non modifica file sorgente, mai (§7).
- Non fa analisi semantica completa: niente risoluzione dei tipi fra moduli Maven diversi, niente call
  graph interprocedurale, niente flusso dei dati. Una chiamata è rilevata quando il ricevitore è un
  campo o un parametro di un tipo del progetto.
- TypeScript e Python restano lessicali (§5).
- Le azioni AI non chiamano un modello da sole (§8).

## Revisione PHASE 32 (2026-10-08) — la riconciliazione scrive a lotti (TD-60)

Nodi e archi del Blueprint prendono l'id da una sequenza a blocchi di 50 (V33, ottimizzatore *pooled* di
Hibernate) invece che da `IDENTITY`, che obbligava a un `INSERT` per riga per leggere l'id; con
`hibernate.jdbc.batch_size=50` e `order_inserts` gli inserimenti di una scansione partono in lotti JDBC.
Gli aggiornamenti non sono riordinati: l'ordine in cui le righe si scrivono (e si bloccano) resta quello
del codice. `BlueprintScanBatchingTest`: 120 classi collegate (240 righe) si scrivono con meno di 60
statement preparati; senza batching il test è rosso.

## Revisione PHASE 32 (2026-10-08) — l'autorizzazione degli endpoint dalle regole per percorso (TD-62)

Un endpoint senza `@PreAuthorize` / `@Secured` diceva «autenticato (regola globale)» anche quando la
configurazione di sicurezza lo riservava a un ruolo. Ora il plugin Java legge anche le regole per percorso
di Spring Security — `requestMatchers(…)` (o `antMatchers`, `mvcMatchers`) seguito da `hasRole`,
`hasAnyRole`, `hasAuthority`, `permitAll`, `authenticated`, `denyAll`… e `anyRequest()` — nell'ordine in
cui sono scritte, con il metodo HTTP se c'è e i pattern letterali o costanti dello stesso file
(`ADMIN_ONLY`); una costante di un'altra classe non si legge, e la sua regola non assegna nulla.
Lo scanner, che vede tutti i file, dà a ogni endpoint la **prima** regola che combacia con metodo e rotta,
come fa Spring Security (`*` un segmento, `**` quanti se ne vuole, `{id}` della rotta solo con un
jolly), e lo scrive con la regola, il pattern e il punto del codice:
`hasRole('ADMIN') — regola per percorso DELETE /api/tasks/* (SecurityConfiguration.java:139)`.
Un'annotazione sul metodo resta quella mostrata. `BlueprintAccessRulesTest`, `BlueprintSelfScanTest`.

## Revisione PHASE 32 (2026-10-08) — l'esito di un'azione AI torna nel blocco (TD-63)

Rivede §8: un'azione AI resta una task (orchestratore, costi, review), ma ora è registrata con il suo
blocco (V34, `blueprint_ai_actions`: blocco, azione, task, chi l'ha chiesta). `GET …/nodes/{id}/ai-actions`
elenca le azioni chieste sul blocco con il loro esito, letto dov'è e mai copiato: l'output dell'ultima
esecuzione riuscita della task attraverso l'AI Engine, altrimenti la sezione **Esito** del documento della
task, che un agente esterno compila dopo un handoff (fino a `## Note` o alla fine del file; il segnaposto
non conta). L'Inspector mostra gli esiti; per «Spiega il componente» e «Genera pseudocodice», in modalità
modifica, «Usa come descrizione / pseudocodice» li applica come modifica manuale del blocco, con il suo tag
e ripristinabile (§3) — nessuna scrittura implicita (§7). Un blocco eliminato si porta via le sue richieste
(come le relazioni, V26); una task eliminata le toglie esplicitamente.

## Revisione PHASE 32 (2026-10-08) — il Blueprint si carica per livello di dettaglio e per area (TD-61)

`GET …/blueprint` restituiva l'intero grafo con tutti i testi (≈1,1 MB per 614 blocchi su questo
repository). Ora accetta `detail=outline` — blocchi, relazioni, posizioni e metriche senza i testi che il
canvas mostra solo da vicino: descrizioni e pseudocodice dei blocchi, etichette, descrizioni e punti del
codice delle relazioni — e `within=x0,y0,x1,y1` — i blocchi la cui area tocca quel rettangolo del canvas e
le relazioni fra loro. Posizioni, metriche e conteggi descrivono sempre l'intero Blueprint; la risposta dice
`outline` e `partial`. Senza parametri resta quella di prima.

La console carica il contorno; quando lo zoom arriva al livello in cui i testi si vedono (§14) chiede l'area
in vista, con un margine, e li tiene finché la riga non cambia (`version`); scegliere una relazione di cui
non ha ancora i testi chiede l'area dei suoi due blocchi. `BlueprintApiTest` (il contorno è più leggero
almeno di quanto ha tolto, l'area contiene solo relazioni interne), `blueprintModel.test.ts`,
`BlueprintView.test.tsx`.

## Revisione PHASE 32 (2026-10-08) — un indice dei tipi al posto del symbol solver; il call graph (TD-58)

Il plugin Java legge prima **tutti** i file Java del progetto (`BlueprintLanguagePlugin.prepare`, per
scansione: i plugin sono condivisi) e ne fa un indice dei tipi — supertipi, campi, che cosa restituiscono i
metodi, i record e i loro accessor, i tipi annidati — poi legge i file uno a uno con quell'indice. Così una
chiamata arriva al tipo su cui è fatta anche quando il tipo non è scritto lì: su un **campo ereditato**
(`audit.record()` dichiarato nella superclasse), sul **risultato di un'altra chiamata**
(`orders.findById(id).orElseThrow().customer().notify()`: un repository Spring Data restituisce la sua entità,
`orElseThrow`/`get`/`getFirst`… quello che il wrapper contiene, poi il tipo di ritorno dei metodi del
progetto), su un **tipo qualificato per package** (`com.acme.mail.Mailer.send()`). Ne nascono relazioni
`CALLS` che prima mancavano. La libreria JavaParser Symbol Solver non è disponibile offline: l'indice copre ciò
che il Blueprint chiede, senza risolvere i generics né l'overloading (un metodo per nome).

Ogni metodo dice inoltre quali metodi del progetto **invoca** (`CodeMethod.invokes`, come
`com.acme.Type#metodo`, il tipo che dichiara il metodo, proprio o ereditato): è il call graph
interprocedurale, servito intero da `GET /api/projects/{id}/blueprint/call-graph` (chi chiama chi, con i
blocchi dei due tipi) e mostrato nell'Inspector sotto ogni metodo («invokes»). Una struttura salvata prima ne è
priva (lista vuota) finché non si riscansiona. Test: `BlueprintCallGraphTest` (rosso senza l'indice),
`CodeGraphApiTest`, console `StructurePanel.test.tsx`.

## Revisione PHASE 32 (2026-10-08) — TypeScript, JavaScript e Python con i loro parser (TD-57)

I plugin di TypeScript/JavaScript e di Python leggono ora i file con i parser dei loro linguaggi, quando ci
sono: il compilatore di TypeScript sotto Node.js (`ts.createSourceFile`) e l'`ast` di Python. Una volta per
scansione, tutti i file di quel linguaggio passano su stdin a uno script fornito con AI Company OS
(`blueprint/ts-ast.js`, `blueprint/py-ast.py`) che **analizza soltanto**: nulla del progetto viene eseguito. Ne
tornano gli stessi fatti che i pattern ricavavano — esportazioni, classi, componenti React e loro props, hook,
import (anche `import()` e `require`), route `/api/…` chiamate; per Python classi, funzioni, route
FastAPI/Flask, prefissi `APIRouter`, import — ma senza scambiare per codice un commento, una stringa o una
docstring. Blocchi e relazioni si costruiscono dagli stessi fatti, quindi la fedeltà cambia senza cambiare il
modello: la scansione dichiara `ast` per quei linguaggi.

Dove sono: `AICOS_NODE` (default `node` sul PATH), `AICOS_TYPESCRIPT` (la cartella del pacchetto `typescript`;
default la copia della console, `frontend/node_modules/typescript`), `AICOS_PYTHON` (default `python`). Se un
runtime manca, risponde con un errore, supera i 90 secondi o un file non si analizza, quel linguaggio — o quel
file — si legge con i pattern, come prima, e la scansione dichiara `lexical`. Test: `ExternalAstTest` (rosso se
i fatti del parser vengono ignorati; il ripiego senza runtime), `BlueprintScannerTest`.
