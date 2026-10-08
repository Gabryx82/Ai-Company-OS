# ADR-035 — Ciò che si crea da AI Company OS si elimina da AI Company OS

- **Stato**: Accettata e implementata (PHASE 30)
- **Data**: 2026-10-06
- **Decisa da**: agente, su direttiva umana del 2026-10-06 («introdurre un sistema coerente, sicuro e
  riutilizzabile di eliminazione degli elementi direttamente dall'interfaccia»)
- **Modifica**: ADR-004 §3 e ADR-008 per gli **agenti** («gli agenti si disattivano, non si eliminano»);
  ADR-027 §3 per i **file** (che restavano sempre all'operatore). Estende ADR-027, non lo sostituisce:
  progetti e task si eliminano come prima.

## 1. Il principio, e le sue eccezioni

> Se l'operatore può creare o importare un elemento attraverso AI Company OS, deve poterlo anche
> rimuovere attraverso AI Company OS, senza Explorer, senza modificare file a mano, senza toccare il
> database.

Le eccezioni sono poche, **intenzionali** e motivate da integrità o sicurezza (tabella §9): ciò che
tornerebbe da solo (voci del catalogo spedito, blocchi del Blueprint derivati dal codice), ciò che è
identità del registro di sicurezza (persone), ciò che è la struttura del workspace (MASTER_PROMPT.md e
simili), la cartella di un progetto (che può essere un repository dell'operatore).

L'audit che ha preceduto questa decisione ha trovato **due incoerenze dichiarate** (agenti e
reference) e altre cinque dello stesso tipo: skill e knowledge creati o importati, documenti rimasti
orfani dopo l'eliminazione di una task, risorse adottate da un progetto (adottabili dalla console,
invisibili nella scheda del progetto), il Blueprint intero, i budget.

## 2. Agenti

Si eliminano davvero, **anche quelli del set iniziale e dei template**: il set è reinstallabile (§4),
quindi non è protetto. Disattivare resta il gesto reversibile (ADR-012) e la console lo offre accanto.

| Relazione | Destino | Perché |
|---|---|---|
| Configurazione (prompt, direttive, binding, collegamenti a skill e software) | **eliminata** | è l'agente |
| Task `OPEN` / `DONE` | **restano, senza agente** (`version + 1`) | le aperte si riassegnano; le chiuse sono storia |
| Task `IN_PROGRESS` | **bloccano** (`409 deletion-blocked`), oppure — su richiesta esplicita `inProgress=STOP` — **tornano `OPEN`** senza agente | il lavoro in corso non resta senza responsabile; STOP è la via di recupero di ADR-014 |
| Run `QUEUED` / `RUNNING` | **bloccano**, sempre | un'esecuzione in volo non si interrompe da qui |
| Run concluse | **restano**, con il nome dell'agente scritto nella riga (`V28`: `agent_name`, `agent_id` NULL) | lo storico continua a dire chi ha fatto il lavoro; il vincolo `task_runs_agent_known` vieta una run senza né l'uno né l'altro |
| Sottoagenti | **diventano principali** (`subagents=DETACH`, default) o **si eliminano con lui** (`subagents=DELETE`, esplicito, con le stesse regole per ciascuno) | il default non distrugge niente |
| Task di progetti archiviati | **perdono l'assegnazione** anche loro, e l'anteprima lo dice | l'eliminazione è un atto esplicito dell'admin che prevale sul congelamento; l'alternativa (rifiutare) costringerebbe a ripristinare, riassegnare e riarchiviare |
| Costi | le run di un agente eliminato restano nel riepilogo, sotto il suo nome, senza id | il denaro speso non sparisce con chi l'ha speso |

**Protezioni**: solo `ADMIN` (come ogni eliminazione che distrugge dati, ADR-024 §3); `If-Match` sulla
riga dell'agente (ADR-009); per una selezione, la versione letta di **ogni** agente, verificata sotto
il suo lock: una selezione fatta su dati vecchi è un `412` per tutta la selezione, mai un'eliminazione
parziale. Ogni eliminazione è un evento `DATA_DELETED`.

**Lock**. ADR-010 §3 ordina le classi di righe `tasks → projects → agents`. L'eliminazione scrive
task, quindi blocca **prima** le task che puntano agli agenti, **poi** gli agenti in ordine di id, poi
ricontrolla sotto i lock: un'assegnazione o una run accodata nel frattempo si vede lì, perché entrambe
prendono il lock condiviso dell'agente, che attende quello esclusivo dell'eliminazione.

## 3. Il pattern comune

**Server**. Ogni eliminazione nuova ha la stessa forma:

- `GET …/deletion` → un `DeletionPreview` uguale per ogni tipo di cosa: `deletes` (cosa se ne va),
  `detaches` (cosa viene scollegato e resta), `keeps` (cosa resta), `files` (cosa lascia il disco),
  `blockers` e `allowed`. È calcolato con le stesse regole che l'eliminazione applica, quindi anteprima
  e atto non possono divergere;
- `DELETE …` → **`204`**; `404` per ciò che non esiste; **`409 deletion-blocked`** con le stesse frasi
  dell'anteprima quando qualcosa dipende da ciò che si elimina e non si può scollegare; `400` per una
  richiesta invalida (un percorso fuori posto, una selezione vuota o ripetuta); `412`/`428` per il tag.
- Le eliminazioni di progetti e task di ADR-027 restano com'erano (`200` con l'impatto nel corpo): il
  contratto pubblicato non si cambia.

**Console** (`components/Deletion.tsx`): un solo cestino (`DeleteButton`), sempre in fondo alla riga,
alla card o all'intestazione; un solo dialog (`ConfirmDeletion`) con *Annulla* / *Elimina*, l'anteprima
raggruppata, il gesto reversibile accanto quando esiste (*Disattiva*, *Archivia*) e — solo per le
eliminazioni pesanti — il nome da digitare; la selezione multipla (`useSelection`, `SelectionBar`). Le
conferme sono **rapide** per ciò che è piccolo (un'immagine, un file, un budget) e **forti** per ciò che
porta via molto (progetto, task, Blueprint). Dopo un'eliminazione la lista viene riletta subito dal
server, e un rifiuto resta nel dialog con la sua spiegazione.

## 4. Il set iniziale si reinstalla, non ritorna da solo

Nessun meccanismo di avvio ricrea agenti eliminati, e un test lo prova eseguendo ciò che un riavvio
esegue:

- il seed di sviluppo è una migrazione Flyway **versionata** di uno stream suo (ADR-003): gira una volta
  per database e mai più;
- `CatalogBootstrap` inserisce software, provider, modelli, quote e risorse — **mai agenti**;
- `AgentTemplates.recordTemplateOrigins` marca l'origine di agenti che esistono — **non ne crea**.

**I template sono dodici**, e fra loro c'è il set iniziale: Code Architect, Frontend Developer e Database
Specialist — che prima esistevano solo come righe del seed e, una volta eliminati, non tornavano più — sono
anche template, con esattamente la configurazione che il seed dà loro (aggiunto il 2026-10-07 su richiesta
dell'operatore). Dove il seed li ha creati, l'installazione li vede «presenti».

La reinstallazione è un gesto esplicito: `GET /api/agent-templates/plan[?key=]` dice, agente per agente,
che cosa verrebbe **creato** e che cosa è **già presente**; l'installazione crea solo i nomi mancanti e non
tocca né duplica gli altri (regola esistente, ora mostrata prima e provata).

## 5. File: solo cartelle gestite, a stadi

`deletion/ManagedFiles` è l'unico punto che rimuove file. Non esiste un'API capace di cancellare
percorsi arbitrari:

1. **La radice la calcola il server** da ciò che possiede (la cartella `references/` del progetto, la
   libreria delle skill), mai dalla richiesta;
2. il percorso relativo passa dalle guardie esistenti (ADR-020 W2: normalizzazione, niente assoluti,
   niente nomi di dispositivo, niente `..` che esca, percorso reale dentro la radice reale — chiude i
   link simbolici) e da quelle nuove (`WorkspacePaths.reference`, `deletableDocument`): sotto la
   cartella giusta, un file regolare, non una cartella né un link;
3. `ManagedFiles` ricontrolla tutto, perché è l'ultima linea prima del disco;
4. **a stadi**: ogni file viene prima rinominato nella sua cartella con un nome nascosto
   (`.<nome>.aicos-deleting-<uuid>`) che nessun elenco mostra — atomico, e fallisce se un altro
   programma tiene il file aperto, caso comune su Windows, riportato come `409` con il motivo. Al
   **commit** della transazione i file preparati si eliminano; al **rollback** tornano dov'erano. Una
   selezione si prepara tutta o niente.

Così una riga e il suo file se ne vanno insieme o non se ne va nessuno. Resta possibile un solo caso:
un crash tra il commit e la rimozione lascia un file nascosto preparato, invisibile (TD-70). Il caso
inverso — file sparito e riga rimasta — richiederebbe un fallimento del commit dopo il rename, e allora
il rollback rimette il file.

**File mancanti**: un file che non c'è più non impedisce di eliminare la riga che lo indicava (una skill
il cui file è stato cancellato a mano si elimina comunque). Una reference *è* il file: se manca, `404`.
In una selezione, i file già spariti si saltano — lo stato chiesto è già vero.

## 6. Reference e documenti

- **Reference**: ogni file di `references/` (immagini e altri: note, PDF, il README) si elimina dalla
  scheda *Reference*, singolarmente o selezionandone più d'uno. Operatore, non solo admin: è il rovescio
  del caricamento, come per le voci del Daily e i blocchi manuali del Blueprint. In un progetto
  archiviato le reference non si toccano (`409`), come non si caricano.
- **Documenti** (`docs/`, `tasks/`, `.aicos/handoffs/`): si eliminano **quando nessun record li usa**.
  Il documento di una task, di una fase o registrato da un handoff è bloccato con il nome di chi lo usa;
  eliminata la task, il suo documento diventa un avanzo dell'operatore e si elimina dalla scheda
  *Documenti*. È questo, e non una modifica di ADR-027, a chiudere il caso «task eliminata, file rimasto».
- **Struttura del workspace** (MASTER_PROMPT.md, AGENTS.md, CLAUDE.md, `.aicos/project.json`,
  `.aicos/plan.json`, `.aicos/CONTEXT_MAP.md`): si modifica, o si ricrea con «Prepara workspace», non si
  elimina da qui.

## 7. Skill, knowledge e voci del catalogo

Una voce **creata o importata** (dalla console, dal web, dalla cartella) si elimina con il suo file
(`skills/<chiave>/SKILL.md`, `knowledge/<chiave>.md`); gli altri file della cartella di una skill
restano, perché sono dell'operatore. Gli agenti e i progetti che la usavano perdono il collegamento e
restano. Una voce del **catalogo spedito** (`catalog/resources.json`) è rifiutata con il motivo:
`CatalogBootstrap` la reinserirebbe al prossimo avvio, quindi l'eliminazione non durerebbe — si
scollega, invece. Il criterio è la presenza della chiave nel catalogo, non il campo `origin`, che per
le voci create via `POST /api/resources` vale ancora `CATALOG`.

## 8. Blueprint

Il Blueprint di un progetto si elimina per intero (`DELETE /api/projects/{id}/blueprint`): blocchi,
relazioni, disposizione, scansioni e proposte. **Il codice non si tocca mai** (ADR-033). Le task create da
proposte accettate restano. Una nuova scansione ricostruisce ciò che il codice dice; i blocchi disegnati
a mano e la disposizione non tornano: per questo è admin, con il nome del progetto digitato, sotto il tag
della disposizione che il canvas tiene. La riga del progetto è bloccata per prima: ogni scrittura del
Blueprint — scansione compresa — la prende condivisa, quindi nessuna gira in parallelo.

## 9. Politica finale

| Entità | Archivia / reversibile | Elimina | Note |
|---|---|---|---|
| Agente (anche del set iniziale) | **Disattiva** / Riattiva | **Sì** — admin, `If-Match`, anteprima, selezione multipla | §2 |
| Sottoagente | come l'agente | **Sì**, o diventa principale | §2 |
| Progetto | **Archivia** / Ripristina | **Sì** — admin, nome digitato (ADR-027) | la cartella resta sul disco: può essere un repository dell'operatore |
| Task | si chiude (`DONE`), si riapre | **Sì** — admin, nome digitato (ADR-027) | il documento resta, poi è un documento orfano eliminabile (§6) |
| Reference (immagini e file di `references/`) | — | **Sì** — operatore, singola o selezione | §5–6 |
| Documento del progetto | — | **Sì, se nessun record lo usa** | la struttura del workspace no (§6) |
| Skill / knowledge / MCP / tool / framework creati o importati | — | **Sì** — admin, con il file | §7 |
| Voce del catalogo spedito | — | **No** (`409`) | tornerebbe all'avvio: si scollega |
| Collegamento agente↔risorsa, agente↔software, progetto↔risorsa | — | **Scollega** | la voce resta; per i progetti la console ora li mostra e li scollega |
| Assegnazione agente↔modello | — | si torna al default | «Default dell'engine» |
| Blueprint intero | segue il progetto | **Sì** — admin, nome digitato | mai il codice (§8) |
| Blocco / relazione manuale del Blueprint | — | **Sì** (ADR-033) | |
| Blocco derivato dal codice | — | **No** (ADR-033) | tornerebbe alla scansione: si cambia il codice o si sovrascrive un campo |
| Proposta di modifica al codice | **Rifiuta** | con il Blueprint o il progetto | storia delle decisioni |
| Fasi e piano | — | rigenerare sostituisce la bozza; con il progetto | |
| Esecuzioni, handoff, review | — | con la loro task | storia |
| Voce del Daily | — | **Sì** (PHASE 13) | |
| Budget di un provider | — | **Sì** — admin, `If-Match` | rimuoverlo **blocca** la spesa, non la libera (ADR-031) |
| Prezzo di un modello | — | si azzera (`PUT` con `null`) | |
| Modelli e provider del catalogo | ciclo di vita (`DEPRECATED`) | **No** | catalogo spedito |
| Persona (utente) | **Disattiva** / Riattiva | **No** | è l'identità del registro di sicurezza; un nome riusato confonderebbe la storia |
| Software del catalogo | **Disabilita** (`enabled`) | **No** | catalogo spedito |
| Software aggiunto via API | Disabilita | **No, per ora** (TD-68) | non si crea dalla console |
| Servizio di avvio dell'ecosistema aggiunto via API | autostart spento | **No, per ora** (TD-69) | non si aggiunge dalla console |
| Ruolo | — | — | non è un'entità: è un attributo testuale dell'agente; i ruoli delle persone sono due e fissi |

## 10. Test

Backend: `AgentDeletionApiTest` (12: senza dipendenze, inesistente, con task e run, in un progetto e in
uno archiviato, ruolo e harness, lavoro in corso, `STOP`, sottoagenti `DETACH` e `DELETE`, selezione
tutta-o-niente, template reinstallabili senza duplicati, vincolo di V28), `SeedAgentDeletionTest`
(l'intero seed eliminato non torna con ciò che esegue un riavvio), `WorkspaceDeletionApiTest` (7:
immagine dal disco e dall'elenco, file mancante, path traversal e file fuori dal progetto, selezione,
progetto archiviato, documento usato da una task e poi orfano, struttura protetta), `ManagedFilesTest`
(5: commit e rollback, radice, cartelle e link, file aperto da un altro programma su Windows),
`ResourceDeletionApiTest` (3), `BlueprintApiTest` (+1), `CostGovernanceApiTest` (+2), coperture
`PreconditionCoverageTest`, `ApiProblemCoverageTest`, `OpenApiContractTest` (contratto **solo additivo**).
Console: `Deletion.test` (5), `Agents.test` (7), `ProjectReferences.test` (4).

**Mutazioni**: 13 sulle guardie del backend, tutte **rosse** con un test fallito — la tredicesima (il `412`
della rimozione di un budget) solo dopo aver aggiunto l'asserzione che mancava: il test controllava il `428`,
che scatta nel controller, non la guardia del servizio. 8 sulla console: 7 rosse; l'ottava è sopravvissuta
perché uno stato locale che nascondeva subito i file eliminati era reso inutile dalla rilettura dal server —
rimosso il codice, e la mutazione della rilettura stessa è rossa.

**Smoke dal vivo** su un database separato: ha trovato un solo comportamento da cambiare — un file già
eliminato fuori dalla console, o un agente eliminato da altri nel frattempo, rispondeva `404` e il dialog lo
mostrava come errore. È lo stato che l'operatore chiedeva: ora la console lo dice e rilegge l'elenco.

## Revisione PHASE 32 (2026-10-08) — TD-68, TD-69 e TD-73 chiusi

| Entità | Elimina | Note |
|---|---|---|
| Software aggiunto con `POST /api/software` | **Sì** — admin, `If-Match`, anteprima (`GET /api/software/{key}/deletion`) | si scollega dagli agenti e dall'avvio coordinato; il programma installato non è toccato. Il catalogo spedito resta `409` (si disabilita) |
| Servizio aggiunto all'avvio coordinato | **Sì** — admin, `If-Match`, anteprima | `DELETE /api/admin/ecosystem/services/{key}`; i tre di serie restano `409` (si spegne l'avvio automatico) |

`POST /api/resources` registra ora `origin = USER`; all'avvio le voci etichettate `CATALOG` che il
catalogo spedito non contiene sono rietichettate `USER`. La console mostra «Elimina» nel Software Hub
e «Togli dall'avvio» nel pannello dell'ecosistema solo per ciò che si può eliminare.

## Revisione PHASE 32 (2026-10-08) — TD-71 chiuso: le voci del catalogo spedito si congedano

Cambia la regola del §7 e quella della revisione precedente («il catalogo spedito resta `409`»).
Una voce del catalogo spedito — software (`catalog/software.json`), voce della libreria
(`catalog/resources.json`), modello (`catalog/models.json`) — **si elimina**, e l'eliminazione dura:

- la tabella `catalog_retirements` (V36: catalogo, chiave, chi, quando) tiene le voci congedate, e
  `CatalogBootstrap` non inserisce ciò che vi trova. Il congedo è scritto nella stessa transazione
  dell'eliminazione;
- l'anteprima lo dice («la voce viene congedata e il prossimo avvio non la reinserisce») invece di
  rifiutare: `allowed` è vero, i vincoli restano quelli di sempre (admin, `If-Match`, scollegamenti);
- i modelli entrano nel pattern: `GET /api/catalog/models/deletion?key=` e
  `DELETE /api/catalog/models?key=` (admin). Esce la voce, non il modello: niente è rimosso da Ollama
  né dal provider, e gli agenti che lo usano lo tengono fuori catalogo;
- il ritorno è esplicito: `GET /api/admin/catalog/retirements` elenca le voci congedate e
  `DELETE /api/admin/catalog/retirements/{catalogo}/{chiave}` ne ripristina una **com'è nel
  catalogo** (non com'era prima del congedo), registrando `DATA_RESTORED`. Nella console: «Congeda»
  nel Software Hub e in Modelli LLM (nel Knowledge Hub il gesto resta «Elimina» e l'anteprima dice
  del congedo); Impostazioni › Voci congedate › «Ripristina».

Disabilitare (software) e scollegare (libreria) restano i gesti reversibili senza congedo. Il criterio
di «spedita» resta la presenza della chiave nel file del catalogo, non il campo `origin`.
Test: `CatalogRetirementApiTest` (rosso con un `CatalogBootstrap` che ignora i congedi),
`ResourceDeletionApiTest`, `SoftwareHubApiTest`; console `Models.test.tsx`, `Settings.test.tsx`.
