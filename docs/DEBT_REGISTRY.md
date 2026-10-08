# DEBT REGISTRY — i due spazi di identificatori, disambiguati

> **Questo documento esiste per una sola ragione**: nel repository ci sono **due** registri del
> debito che usano **lo stesso formato di identificatore** (`TD-NN`) per debiti **diversi**, e
> senza una mappa una task futura può chiudere il debito sbagliato credendo di chiudere quello
> giusto.
>
> Creato da **TASK-011**, 2026-09-19. Un test (`DebtRegistryConsistencyTest`) lo tiene allineato
> all'audit: un identificatore che compare là e non qui rende rossa la suite.

## 1. Qual è autoritativo

**La numerazione viva in `.company-os/PROJECT_STATE.md` è autoritativa.** È quella che ADR,
artefatti di task e messaggi di commit citano, ed è quella che si usa per aprire un debito nuovo —
il prossimo numero libero è quello indicato in fondo alla §5.

`docs/audit/TECHNICAL_DEBT.md` è uno **snapshot datato**: la fotografia di ciò che TASK-000 trovò
nel repository iniziale. **Non va rinumerato e non va aggiornato come se fosse vivo.** Riscriverne
gli identificatori significherebbe riscrivere ciò che quella task osservò; e rinumerare quelli vivi
romperebbe ogni riferimento scritto finora, per sistemare un documento.

Perciò la collisione **non viene risolta rinumerando**: viene resa **interpretabile**. È la stessa
forma che il progetto dà alle migrazioni — non si riscrive ciò che c'è, si aggiunge ciò che manca.

**Come si cita, da oggi.** Un identificatore da solo è ambiguo solo per cinque valori, ma la
disciplina costa poco: `TD-20 (vivo)` oppure `TD-20 (audit)` quando il numero è uno dei cinque
della §2. Fuori da quei cinque, `TD-NN` significa il registro vivo.

## 2. Le cinque collisioni

Sono queste, e sono tutte. Ogni altro identificatore o coincide (§3) o esiste in un solo spazio
(§4, §5).

| ID | **Audit** (TASK-000) | **Vivo** (`PROJECT_STATE.md`) |
|---|---|---|
| **TD-14** | Build non riproducibile offline: `./mvnw -o test` fallisce per plugin assenti dalla cache. **Chiuso** (PHASE 32, verificato): la suite intera gira con `./mvnw -o test` (627 test) e in CI online (TD-14 vivo) | **CHIUSO** il 2026-09-23. `origin` → `Gabryx82/Ai-Company-OS`, `master` pushato (`14f70b3`), workflow `CI` **registrato e attivo**, e la **run `35863517006` è passata in verde** su `ubuntu-latest` in 1m26s, eseguendo `./mvnw -B clean test` con tutti e 9 gli step `success`. Non è un file YAML: è una CI che ha girato. `tasks/TASK-012/EVIDENCE_TD14.md` §11 |
| **TD-19** | Metadati `pom.xml` vuoti — **chiuso** (PHASE 32): metadati compilati, `AuditDebtGuardTest.td19…` | Componente del ciclo di vita del progetto. **Chiuso da TASK-004** |
| **TD-20** | Igiene Git — **chiuso** (PHASE 32): `backend/HELP.md` non più tracciato; `AuditDebtGuardTest.td20…` (nessun file ignorato e tracciato) | Le eccezioni che Spring solleva **prima** del nostro codice non entravano nel contratto d'errore. **Chiuso da TASK-005** |
| **TD-21** | `System.out.println` invece di logging — **chiuso** (PHASE 32): `AuditDebtGuardTest.td21…` | **Chiuso da TASK-005**, dentro la normalizzazione del contratto d'errore |
| **TD-22** | Configurazione porta e ambiente assenti — **chiuso** (PHASE 32): `AuditDebtGuardTest.td22…` | Mancava il test incrementale `V1 → V2`. **Chiuso da TASK-006**, con un test su **ogni** coppia consecutiva invece che su quella specifica |

**Nota importante su questi cinque.** Le voci della colonna «Audit» **non sono state rivalutate**
da TASK-011: non si sa, da questo documento, se il `pom.xml` abbia ancora i metadati vuoti. Dire
«TD-19 è chiuso» è vero nello spazio vivo e **non verificato** in quello dell'audit. È esattamente
l'equivoco che questo registro esiste per impedire.

## 3. Gli identificatori che **coincidono**

Qui i due spazi dicono la stessa cosa, ed è ciò che rende la collisione insidiosa: la maggior
parte degli identificatori si comporta bene, quindi chi ne verifica due o tre conclude che il
problema non esista.

| ID | Contenuto | Stato nel registro vivo |
|---|---|---|
| **TD-04** | Nessuna sicurezza / autenticazione | **Chiuso da TASK-013** (ADR-013): bearer token per nome su ogni `/api/**`, verificato per riflessione su tutte le rotte e per mutazione |
| **TD-07** | Nessuna gestione degli errori | **Chiuso da TASK-005** (ADR-007) |
| **TD-08** | `MasterOrchestrator` è un placeholder spacciato per AI | **Chiuso da TASK-020**: rimosso, sostituito da routing sul registro reale e dalle run di ADR-016 |
| **TD-11** | CORS incoerente | **Chiuso da TASK-014**: una policy globale, origini dichiarate, niente wildcard, `ETag` esposto |
| **TD-12** | Nessun dominio per `status` e `priority` | **Chiuso**: la metà `status` da TASK-010, la metà `priority` (TD-36) da TASK-016 |
| **TD-13** | Nessuna relazione tra entità | **Risolto da TASK-009** |
| **TD-15** | Lombok dichiarato e mai usato | **Chiuso** (PHASE 32): rimosso dal `pom.xml`; `AuditDebtGuardTest.td15…` fallisce se torna dichiarato senza usi |
| **TD-17** | `README.md` con escape markdown errati | **Chiuso** (blocco PHASE 3–7): README riscritto; `ReadmeTruthTest` fallisce se tornano |
| **TD-18** | `README.md` descrive uno stack inesistente | **Chiuso** (blocco PHASE 3–7): README descrive lo stack reale; `ReadmeTruthTest` rifiuta Supabase/Vercel/LangGraph e verifica le cartelle citate |

## 4. Solo nell'audit

Debiti che TASK-000 registrò e che la numerazione viva **non ha mai ri-assegnato**. Diversi sono
con ogni probabilità risolti — H2 è stato rimosso, Flyway possiede lo schema, la validazione
esiste, i DTO esistono, i test sono 223 — ma **TASK-011 non li ha rivalutati**, e dichiararli
chiusi senza verificarli sarebbe chiudere un debito perché il codice sembra diverso, che è
precisamente ciò che il charter vieta.

| ID | Contenuto | Stato |
|---|---|---|
| **TD-01** | Persistenza volatile (H2 in-memory) | **chiuso** (PHASE 32): PostgreSQL in ogni profilo, nessun H2 — `AuditDebtGuardTest.td01…` |
| **TD-02** | Schema generato da Hibernate senza migrazioni | **chiuso** (PHASE 32): Flyway possiede lo schema, `ddl-auto=validate` in ogni profilo — `AuditDebtGuardTest.td02…` |
| **TD-03** | Nessuna validazione degli input | **chiuso** (PHASE 32): ogni corpo di richiesta con vincoli è `@Valid` — `ApiShapeGuardTest.td03…` |
| **TD-05** | Entità JPA esposte come contratto API | **chiuso** (PHASE 32): nessuna entità raggiungibile da ciò che un controller risponde — `ApiShapeGuardTest.td05…` |
| **TD-06** | Deserializzazione basata su costruttore implicito | **chiuso** (PHASE 32): ogni corpo di richiesta è un record — `ApiShapeGuardTest.td06…` |
| **TD-09** | Copertura di test funzionale nulla | **chiuso** (PHASE 32): ogni rotta dei controller è raggiunta da un test della suite — `RouteCoverageTest`, che registra le rotte davvero chiamate e gira per ultimo; coperte le 14 che non lo erano |
| **TD-10** | Violazioni di layering nei package | **chiuso** (PHASE 32): i pacchetti `repository`, `controller` e `model` contengono solo il loro tipo di classe — `ApiShapeGuardTest.td10…` |
| **TD-16** | `open-in-view` non configurato | **chiuso** (PHASE 32): `open-in-view=false` in ogni profilo — `AuditDebtGuardTest.td16…` |

**Rivalutati in PHASE 32** (2026-10-08), ciascuno con un test che fallisce se il difetto torna
(`AuditDebtGuardTest`, `ApiShapeGuardTest`, `RouteCoverageTest`), insieme ai cinque della §2 e a
TD-15: lo spazio dell'audit non ha più voci aperte. Il testo che segue spiega perché TASK-011 non
lo fece. **Rivalutarli è una task a sé**, e vale la pena farla: un registro di debiti in cui metà delle voci
sono risolte senza che nessuno lo dica è rumore che nasconde le voci vere. Non è stata fatta qui
perché `AUTONOMOUS_LOOP.md` §4 mette il cleanup al livello 6 e il charter §4 vieta di allargare lo
scope per sistemare tutto.

## 5. Solo nel registro vivo

Debiti aperti **dopo** l'audit, cioè dal lavoro autonomo. Nessuna ambiguità possibile: l'audit si
ferma a `TD-22`.

| ID | Contenuto | Stato | Origine |
|---|---|---|---|
| **TD-23** | L'elenco delle tabelle è dichiarato a mano, e la spiegazione diceva il contrario | chiuso | TASK-006 |
| **TD-24** | Una scrittura che funzionava solo perché il self-invocation aggirava il proxy e ignorava un `readOnly = true` | chiuso — e reso **inesprimibile**, non solo assente (AC-18) | TASK-004 |
| **TD-25** | Finestra fra il controllo «il progetto è `ACTIVE`» e il commit: lo stato poteva cambiare nel mezzo | chiuso interamente da L2+L3+L4, nessun residuo | TASK-004 |
| **TD-26** | Il path lazy non è esercitato fuori transazione | **chiuso** (PHASE 32): `LazyAssociationsOutsideTransactionTest` — fuori transazione gli id restano leggibili, l'attraversamento fallisce, servizio ed endpoint rispondono | TASK-004 |
| **TD-27** | Due dialetti d'errore sulla **stessa rotta**: `GET /api/projects/999/tasks` e `.../abc/tasks` rispondevano in forme diverse | chiuso | TASK-005 |
| **TD-28** | `PUT /api/projects/{id}` esposto alla sovrascrittura con dati stantii | chiuso | TASK-008 |
| **TD-29** | Forma della risposta per gli errori di locking | chiuso | TASK-005 |
| **TD-30** | Due riassegnazioni concorrenti dello stesso task: last-write-wins | chiuso | TASK-008 |
| **TD-31** | `Agent` usava un booleano dove `Project` usa un enum chiuso. Unificarli eliminava una colonna → era **hard stop #3** | **CHIUSO** 2026-09-19 da **TASK-012**: decisione umana esplicita, `V8`, ADR-012. Backfill biiettivo, contratto pubblico invariato | TASK-007 |
| **TD-32** | L'entity-tag deriva dalla versione di riga, non dai byte della rappresentazione | **chiuso** (PHASE 32): ETag `"<versione>.<digest dei byte>"` (`RepresentationTagAdvice`), `If-Match` confronta la versione; orari delle entità alla precisione del database (`DatabaseClock`) — `RepresentationTagTest`, `PreconditionContractTest` | TASK-008 |
| **TD-33** | I listati non portano ETag: mutare N risorse costa N letture | **chiuso** (PHASE 32): `version` per elemento nei listati di progetti, task e agenti — `ListingVersionTest` | TASK-008 |
| **TD-34** | Nessun modo di chiedere «i task fermi su agenti inattivi» | **chiuso** (PHASE 32): `GET /api/tasks?agentStatus=INACTIVE`, filtro «Solo su agenti disattivati» nel Task Board — `TaskAgentAssignmentApiTest` | TASK-009 |
| **TD-35** | Nessun `DELETE` dell'associazione con l'agente | **chiuso** (PHASE 32): `DELETE /api/tasks/{id}/agent` sotto `If-Match`, con la regola del progetto archiviato; «Togli agente» nella console — `TaskAgentAssignmentApiTest` | TASK-009 |
| **TD-36** | `Task.priority` resta una stringa libera senza vincolo DB | **chiuso** da TASK-016: `TaskPriority`, `V9`, censimento | TASK-010 |
| **TD-37** | Nessun percorso muta lo `status` di un task esistente: vocabolario chiuso, ciclo di vita non percorribile | **chiuso** da TASK-015: quattro archi, ADR-014 | TASK-010 |
| **TD-38** | Nessuna storia delle transizioni: non si sa chi ha mosso un task né quando | **chiuso** (PHASE 32): ogni arco del ciclo di vita è registrato in `task_transitions` (V31) con chi, quando e da dove (`API`, `RUN`, `HANDOFF`, `REVIEW`), nella transazione che lo prende; `TaskTransitions` è l'unico punto che chiama `Task.apply` (verificato sul sorgente). `GET /api/tasks/{id}/transitions`, sezione «Storia» nella console; la storia va con la task cancellata; una task avviata e fermata blocca la sostituzione del piano — `TaskTransitionHistoryApiTest`, `PlanningApiTest`, `TaskDrawer.test.tsx` | TASK-015 |
| **TD-39** | Una run in corso non si può cancellare: si attende la fine o il timeout di lettura (10 min) | **chiuso** (PHASE 32): `POST /api/runs/{id}/cancel` registra la run `FAILED` con il tipo `run-failure:cancelled` sotto il lock della riga; dopo il commit il thread che attende l'engine è interrotto, la connessione si chiude e l'AI Engine annulla la chiamata al provider quando il chiamante se ne va (`until_caller_leaves`, risposta 499). La risposta tardiva non sovrascrive la cancellazione; una run già finita è restituita com'è. Console: «Annulla» sulla run in corso — `TaskRunApiTest`, `test_cancellation.py`, `TaskDrawer.test.tsx` | TASK-019 |
| **TD-40** | Nessuna contabilità né limite di costo per le run su modelli a consumo (`anthropic`): i token sono registrati per run, mai sommati o limitati | **chiuso** in PHASE 24 (ADR-031): costo per run, budget mensili per provider a consumo, prezzi per modello; nessuna run a consumo senza budget e prezzo | TASK-019 |

| **TD-40** *(nota PHASE 8)* | Ora vale anche per OpenRouter (`AICOS_ENGINE_OPENROUTER_API_KEY`). La scheda Consumi **somma** i token delle run a consumo per finestra, ma non **limita** nulla | **chiuso** in PHASE 24 (ADR-031), anche per OpenRouter | PHASE 8 |
| **TD-41** | La fase di un task passa a `IN_PROGRESS`/`DONE` da handoff e review **senza lock di riga** sulla fase: un'approvazione concorrente può produrre un conflitto di `@Version` (500) invece di un 409 | **chiuso** (PHASE 32): la fase è riletta sotto lock esclusivo prima di scriverne l'avanzamento — `PhaseProgressRaceTest`, con intreccio deterministico | PHASE 11 |
| **TD-42** | Il terminale integrato nella pagina (PTY nel browser) non esiste: la scheda Terminale apre Windows Terminal nella cartella del progetto | **chiuso** in PHASE 22 (ADR-029): PTY + WebSocket + xterm.js, solo admin, ticket monouso | PHASE 8 |
| **TD-43** | Una generazione di piano non si può annullare; col 9B locale dura ~13 minuti (misurato) | **chiuso** (PHASE 32): `POST /api/plan-runs/{id}/cancel` registra la generazione `FAILED` (`cancelled: ...`) sotto il lock della riga e interrompe il thread che attende l'engine; la generazione si ferma prima della fase successiva, e il piano si scrive solo sotto lo stesso lock e solo se la run è ancora in corso: annullata dopo l'ultima risposta, non importa nulla. Il fallimento che segue l'interruzione non sovrascrive la cancellazione. Console: «Annulla» sulla generazione in corso, «Ultima pianificazione annullata» — `PlanningApiTest`, `ProjectPlan.test.tsx` | PHASE 10 |
| **TD-44** | Gmail, Drive, ClickUp, ChatGPT web, Gemini non sono incorporabili (policy di framing misurate): si aprono in finestre dedicate | **aperto** → PHASE 18, **decisione umana** (shell desktop, credenziali OAuth) | PHASE 8 |
| **TD-45** | Nessuna metrica di apprendimento né suggerimento automatico del livello di autonomia (ADR-022 §5) | **chiuso** (PHASE 32): ogni review registra il livello a cui è data (V32); `GET /api/projects/{id}/learning` misura, al livello corrente, task riviste, accettate alla prima review, modifiche chieste, task completate a mano (storia di TD-38) e pezzi `TODO(operatore)` aperti nel codice, e suggerisce di salire (≥80% delle ultime 5/8/10 accettate al primo colpo, e da GUIDED nessun pezzo aperto), scendere (3 delle ultime 4 rimandate) o restare, con i motivi. Mai applicato da solo: «Passa a …» nella Panoramica cambia il profilo con il suo tag. Le «domande risposte» restano fuori: vivono nello strumento dell'operatore (ADR-022, revisione PHASE 32) — `LearningApiTest`, `LearningSuggestionTest`, `ProjectLearning.test.tsx` | PHASE 11 |
| **TD-46** | Graph engineering: il Second Brain mostra il grafo dell'ecosistema, ma non esistevano dependency graph e code graph del codice di un progetto | **chiuso in parte** in PHASE 23 (ADR-030): grafo di file, import, dipendenze esterne e cicli per progetto, con `.aicos/CODE_GRAPH.md` come contesto; resta fuori il call graph semantico | PHASE 14 |
| **TD-47** | I tipi della console per le risposte annidate usano `Deep<T>`: la nullabilità dei campi interni non è espressa dal contratto generato | **chiuso** (PHASE 32): il contratto dice che ogni campo di un record di risposta è sempre presente e quali possono essere `null` (`ResponseShapes`, da `@Nullable` jspecify); `ResponseContractTest` confronta ogni risposta JSON della suite con il contratto e fallisce su un `null` non dichiarato. La console usa i tipi generati: `Deep<T>` e le correzioni a mano sono eliminati (ADR-017, revisione PHASE 32) — `ResponseContractTest`, `OpenApiContractTest` | PHASE 9 |
| **TD-48** | Associare un MCP o un tool a un agente è metadato: AI Company OS non installa né configura il server MCP nello strumento che lo userà | **chiuso** (PHASE 32): all'handoff gli MCP dell'agente avviabili con un comando semplice sono scritti in `.mcp.json` (Claude Code) o `opencode.json` (OpenCode) del progetto, senza toccare le voci dell'operatore; gli altri sono elencati «da configurare a mano» (ADR-025, revisione TD-48) — `HandoffMcpTest`, `OrchestratorApiTest` | PHASE 12 |
| **TD-49** | Gli handoff aprono la TUI di OpenCode; l'API locale di `opencode serve` non è ancora usata per seguire l'esecuzione | **chiuso** (PHASE 32): l'handoff apre OpenCode con l'API locale su una porta registrata (V38); `GET /api/handoffs/{id}/session` ne legge la sessione (stato, file, ultima risposta) e la console la segue (ADR-025, revisione TD-49) — `OrchestratorApiTest`, `OrchestratorPanel.test.tsx` | PHASE 11 |
| **TD-50** | Quote: Codex è misurato solo fino all'ultima sessione locale; il reset settimanale di Claude va impostato a mano; nessuna API espone le quote degli abbonamenti | **aperto**, vincolo esterno | PHASE 8 |
| **TD-51** | `llama3.2:3b` è `DEPRECATED` (sostituto `qwen3.5:4b` verificato). **Correzione (PHASE 16)**: sul DB live gli agenti del seed non avevano *alcun* modello (NULL, quindi il default dell'engine `echo`), non `llama3.2:3b`; ora il seed V2 dà loro `qwen3.5:9b` e `deepseek-coder-v2:16b` (ADR-025). Nessun agente usa più `llama3.2:3b`: rimuoverlo da Ollama (2 GB) è una scelta dell'operatore | **aperto**, decisione umana (rimozione) | PHASE 8 |
| **TD-52** | Il ruolo `CODER` è coperto da `deepseek-coder-v2:16b` (2024, senza tool calling); il candidato `qwen3-coder:30b` (19 GB) attende una decisione umana su disco e RAM | **chiuso** il 2026-09-30 (PHASE 29, ADR-034): misurato sulla macchina, `qwen3.5:9b` batte DeepSeek su codice, ragionamento, output strutturato e strumenti; DeepSeek è `DEPRECATED` e il lavoro di codice va a Qwen. I modelli da 27–35B (Qwen 3.6/3.8, MoE A3B) non stanno nei ~9–13 GB liberi: Qwen3.8-27B quantizzato a 10 GB scende a 0,3–0,9 token/s e porta la RAM libera a 1,3 GB | PHASE 8 |
| **TD-53** | Il token di sessione della console vive nel `sessionStorage`: uno script iniettato nella console potrebbe leggerlo. Mitigato da React (niente HTML non fidato), scadenza e revoca; un cookie `HttpOnly` richiederebbe protezione CSRF e un proxy same-origin (ADR-024 §8) | **aperto**, MINOR | PHASE 15 |
| **TD-54** | Una run senza modello (quello di default dell'AI Engine) non passa dal controllo del budget: se `AICOS_ENGINE_DEFAULT_MODEL` fosse un modello a consumo, spenderebbe senza limite. Oggi il default è `echo` | **chiuso** (PHASE 32): una run senza modello chiede all'AI Engine il suo default, lo sottopone allo stesso controllo del budget dei modelli nominati e lo invia per nome: il modello controllato è quello usato. Se il budget lo rifiuta la run fallisce con `run-failure:budget` senza chiamare il provider — `CostGovernanceApiTest`, `TaskRunApiTest` | PHASE 24 |
| **TD-55** | L'esito di un handoff (Claude Code, Codex, IDE) non torna da solo nella console: l'operatore legge la sezione Esito del documento della task e fa la review | **chiuso** (PHASE 32): `GET /api/tasks/{id}/outcomes` legge ciò che la task ha prodotto — l'output dell'ultima run riuscita e la sezione Esito che l'agente scrive nel documento della task dopo un handoff (cartella del progetto o inbox) — e il pannello della task lo mostra in «Esito», rileggendolo da solo mentre la task è in corso; la review resta la decisione (ADR-021, revisione PHASE 32) — `HandoffAnywhereApiTest`, `TaskDrawer.test.tsx` | PHASE 17 |
| **TD-56** | L'app desktop di Open WebUI si apre ma avvia il suo server locale solo dal suo interno: l'avvio coordinato lo riconosce e lo dice, ma non può accenderlo | **chiuso** il 2026-09-25 (review umana): Open WebUI è ora il container Docker `open-webui` su localhost:3000, quello che 3D Omniverse incorpora; si avvia con `docker start` (V25) | PHASE 27 |

| **TD-57** | Blueprint: TypeScript, JavaScript e Python sono letti in modo **lessicale** (un blocco per file, import e rotte), non con un AST; il punto di estensione `BlueprintLanguagePlugin` esiste, i plugin AST no (ADR-033 §5) | **aperto** | PHASE 28 |
| **TD-58** | Blueprint Java senza symbol solver: le chiamate su risultati di catene (`a.b().c()`), su campi ereditati o su tipi qualificati per package non generano relazioni; nessun call graph interprocedurale | **aperto**, MINOR | PHASE 28 |
| **TD-59** | springdoc nomina gli schemi con il nome **semplice** della classe: due record omonimi in package diversi si sovrascrivono in silenzio nel contratto. Trovato e evitato in PHASE 28 (prefisso `Blueprint`), ma nessun test impedisce che si ripeta. **Ripetuto in PHASE 31** (`Summary`, `Opened`, `Installed`, e `operationId` duplicati): trovato confrontando il contratto con il precedente, evitato con nomi univoci | **chiuso** (PHASE 32): `ApiShapeGuardTest.td59…` fallisce se due tipi raggiungibili dal contratto hanno lo stesso nome semplice o due handler lo stesso nome di metodo. Ha trovato cinque collisioni reali che il contratto descriveva male (`Edge`, `Node`, `ProfileRequest`, `ReviewRequest`, `SoftwareRef`) e 19 `operationId` numerati: rinominati | PHASE 28 |
| **TD-60** | La riconciliazione di una scansione salva nodi e archi uno a uno (≈12 s per 614 blocchi e 2289 relazioni su questo repository): senza batching JDBC | **chiuso** (PHASE 32): nodi e archi prendono l'id da sequenze a blocchi di 50 (V33) e gli inserimenti di una scansione partono in lotti JDBC (`hibernate.jdbc.batch_size=50`, `order_inserts`); gli aggiornamenti non sono riordinati (ADR-033, revisione PHASE 32) — `BlueprintScanBatchingTest` | PHASE 28 |
| **TD-61** | `GET …/blueprint` restituisce l'intero grafo (≈1,1 MB per 614 blocchi): nessun caricamento progressivo per livello o per area del canvas | **chiuso** (PHASE 32): `GET …/blueprint?detail=outline` toglie i testi (descrizioni, pseudocodice, etichette e codice delle relazioni) e `within=x0,y0,x1,y1` restituisce un'area del canvas con le sue relazioni interne; la console carica il contorno e chiede i testi dell'area in vista quando lo zoom li mostra (ADR-033, revisione PHASE 32) — `BlueprintApiTest`, `blueprintModel.test.ts`, `BlueprintView.test.tsx` | PHASE 28 |
| **TD-62** | L'autorizzazione di un endpoint nel Blueprint viene solo dalle annotazioni (`@PreAuthorize`, `@Secured`): le regole per percorso di `SecurityConfiguration` non sono lette, e il campo dice «autenticato (regola globale)» | **chiuso** (PHASE 32): il plugin Java legge le regole per percorso di Spring Security (`requestMatchers(…).hasRole(…)`, `anyRequest()…`, costanti dello stesso file) nell'ordine in cui sono scritte, e lo scanner dà a ogni endpoint senza annotazione la prima che combacia con metodo e rotta, con il punto del codice (ADR-033, revisione PHASE 32) — `BlueprintAccessRulesTest`, `BlueprintSelfScanTest` | PHASE 28 |
| **TD-63** | Le azioni AI del Blueprint creano task, ma l'esito (spiegazione, pseudocodice, relazioni trovate) resta nella task: non torna da solo nel blocco | **chiuso** (PHASE 32): ogni azione AI è registrata con il suo blocco (V34); `GET …/nodes/{id}/ai-actions` restituisce l'esito letto dove sta — l'output dell'ultima run riuscita della task o la sezione Esito del suo documento dopo un handoff — e l'Inspector lo mostra, con «Usa come descrizione / pseudocodice» in modalità modifica (una modifica manuale, ripristinabile) (ADR-033, revisione PHASE 32) — `BlueprintApiTest`, `BlueprintView.test.tsx` | PHASE 28 |

| **TD-64** | PHASE 29 non ha potuto eseguire la suite Java (Testcontainers) né l'E2E del control plane: Docker Desktop 4.83 va in crash all'avvio su socket AF_UNIX orfani del 2026-09-29 (`%LOCALAPPDATA%\Docker\run\dockerInference`, `%LOCALAPPDATA%\docker-secrets-engine\engine.sock`, errore 1920). Il backend compila (main e test); V27, seed V3 e `LocalModelUpgradeMigrationTest` non sono stati eseguiti | **chiuso** il 2026-10-01: Docker riavviato dall'operatore; suite Java 518 test, 0 falliti; E2E del control plane 17/17 su un clone del database reale; rollback della parte database verificato nei due sensi (ADR-034 §6–7) | PHASE 29 |
| **TD-65** | Una run che esaurisce `max_tokens` (4096) prima di rispondere è registrata `SUCCEEDED` con output vuoto e `finish_reason: length`: la console non la distingue da una risposta. Con il ragionamento spento per default (ADR-034 §3.6) il caso misurato non si presenta più, ma resta possibile con `AICOS_ENGINE_OLLAMA_THINK=true` o con risposte molto lunghe | **chiuso** (PHASE 32): una risposta vuota con `finish_reason: length` è registrata `FAILED` con il tipo `run-failure:no-answer` e un dettaglio che dice quanti token ha speso; modello servito, token e latenza restano sulla run e il costo è contabilizzato — `CostGovernanceApiTest` | PHASE 29 |
| **TD-66** | Il ruolo `CODER` del catalogo non ha più un modello `ACTIVE`: il lavoro di codice arriva a `qwen3.5:9b` per il ripiego sul ruolo `PLANNER`, e la console lo dice come «ripiego». Un modello ha un solo ruolo | **chiuso** (PHASE 32): un modello ha un ruolo e può servirne anche altri (`also_serves`, V35); `qwen3.5:9b` serve anche CODER, e il lavoro di codice senza agente gli arriva come modello CODER, non come ripiego su PLANNER (ADR-034, revisione PHASE 32) — `ModelRoleRoutingApiTest`, `Models.test.tsx` | PHASE 29 |
| **TD-67** | L'iGPU Intel Arc 140V (Vulkan, `OLLAMA_IGPU_ENABLE=1`) accelera l'elaborazione del prompt di 2–3,7× su Qwen 3.5 e Gemma 4 ma dimezza la generazione di LFM2.5; Ollama la attiva per tutti i modelli insieme. Lasciata spenta: servirebbe una scelta per modello (`num_gpu`) nell'engine | **chiuso** (PHASE 32): `AICOS_ENGINE_OLLAMA_NUM_GPU_BY_MODEL` sceglie per modello i layer sulla GPU (`num_gpu`); di default `lfm2.5:8b=0`, così con l'iGPU accesa LFM2.5 resta sulla CPU (ADR-034, revisione TD-67). Accendere l'iGPU (`OLLAMA_IGPU_ENABLE=1` in Ollama) resta una scelta dell'operatore: proposta in `docs/PHASE32_DECISIONS.md` — `test_providers.py` | PHASE 29 |

| **TD-68** | Un software aggiunto con `POST /api/software` (admin, solo API: la console non ne crea) non si elimina: si disabilita (`enabled`). Quando la console permetterà di aggiungerne, servirà l'eliminazione, scollegando `agent_software` ed `ecosystem_autostart` (ADR-035 §9) | **chiuso** (PHASE 32): anteprima e `DELETE /api/software/{key}` (admin, `If-Match`) per le voci aggiunte, scollegandole da agenti e avvio; il catalogo spedito resta `409` (poi congedato con TD-71); «Elimina» nel Software Hub — `SoftwareHubApiTest` | PHASE 30 |
| **TD-69** | Un servizio dell'avvio coordinato aggiunto con `POST /api/admin/ecosystem/services` (solo API) non si rimuove: se ne spegne l'autostart (ADR-035 §9) | **chiuso** (PHASE 32): anteprima e `DELETE /api/admin/ecosystem/services/{key}` per i servizi aggiunti; quelli di serie `409`; «Togli dall'avvio» nella console — `EcosystemApiTest` | PHASE 30 |
| **TD-70** | Un crash tra il commit e la rimozione dei file preparati lascia nella cartella un file nascosto `.<nome>.aicos-deleting-<uuid>`: invisibile a ogni elenco, ma nessuno lo spazza (ADR-035 §5) | **chiuso** (PHASE 32): `ManagedFiles.sweep` rimuove i file in staging rimasti, mai quelli di una transazione ancora aperta; `StagedFileSweeper` lo esegue a ogni avvio sulla libreria e, per ogni progetto, su `references/` e sulle cartelle dei documenti — `ManagedFilesTest`, `StagedFileSweeperTest` | PHASE 30 |
| **TD-71** | Le voci del catalogo spedito (skill, framework, software, modelli) non si possono congedare: si scollegano ma restano negli elenchi, perché l'avvio le reinserisce. Servirebbe un elenco di chiavi congedate letto da `CatalogBootstrap` (ADR-035 §7) | **chiuso** (PHASE 32): `catalog_retirements` (V36) letta da `CatalogBootstrap`; eliminare una voce spedita (software, libreria, modello — nuovo `DELETE /api/catalog/models`) la congeda; Impostazioni › Voci congedate la ripristina com'è nel catalogo (ADR-035, revisione TD-71) — `CatalogRetirementApiTest` | PHASE 30 |
| **TD-72** | Due forme di eliminazione convivono: progetti e task (ADR-027) rispondono `200` con `DeletionImpact` e usano il dialog con il nome digitato; le eliminazioni di PHASE 30 rispondono `204` con `DeletionPreview` e `ConfirmDeletion`. Unificarle cambierebbe un contratto pubblicato (non additivo) | **aperto**, MINOR | PHASE 30 |
| **TD-73** | `POST /api/resources` registra `origin = CATALOG` anche per una voce creata da una persona: l'eliminazione decide sulla presenza della chiave nel catalogo spedito e non ne è toccata, ma la console etichetta quella voce «catalogo» | **chiuso** (PHASE 32): una voce creata con `POST /api/resources` è `USER`; quelle registrate prima come `CATALOG` e assenti dal catalogo spedito sono rietichettate all'avvio — `HarnessApiTest` | PHASE 30 |

| **TD-74** | Plugin di Codex: nessuna verifica di aggiornamenti. Il catalogo locale `~/.codex/.tmp/plugins` è di un altro canale e più vecchio delle versioni installate (misurato: `github` 0.1.0 contro 0.1.12): confrontarlo darebbe solo falsi segnali. Codex li aggiorna da sé (ADR-036 §8) | **aperto**, vincolo esterno | PHASE 31 |
| **TD-75** | I plugin di Claude Desktop, Claude Code e Codex non si aggiornano dal Knowledge Hub: il Hub segnala la versione più recente e apre l'app, o mostra il comando (`claude plugin update …`). Sono gli store delle app (ADR-036 §2) | **aperto**, per scelta | PHASE 31 |
| **TD-76** | La categoria funzionale di una skill è un'euristica a parole chiave (`SkillCategories`): qualche voce finisce nella categoria vicina (es. `algorithmic-art` in «Sviluppo»); nessuna correzione manuale per voce | **chiuso** (PHASE 32): la categoria si corregge a mano, per nome (`PUT /api/skill-hub/categories`, tabella `skill_categories`, V37); vuota torna all'euristica; selettore «Categoria» nel dettaglio (ADR-036, revisione TD-76) — `SkillHubApiTest`, `SkillHub.test.tsx` | PHASE 31 |
| **TD-77** | Eliminare una skill della libreria (ADR-035) lascia le sue copie nelle app (stato «non più in libreria», si tolgono dal dettaglio della copia) e, per un pacchetto installato, i file accanto a `SKILL.md`; l'anteprima di eliminazione non lo dice | **chiuso** (PHASE 32): l'anteprima dice che le copie nelle app restano («non più in libreria», con app e cartella) e nomina gli altri file del pacchetto che restano nella libreria (ADR-036, revisione TD-77) — `SkillHubApiTest` | PHASE 31 |
| **TD-78** | Le skill a livello di progetto (`.claude/skills`, `.agents/skills`, `.agent/skills` dentro i repository) non sono lette: solo quelle dell'utente e delle app | **chiuso** (PHASE 32): `.claude/skills`, `.agents/skills` e `.agent/skills` nel `workspace_path` dei progetti non archiviati sono posizioni in sola lettura (tipologia `PROJECT`), un gruppo per progetto nella console; il Hub non vi scrive (ADR-036, revisione TD-78) — `SkillHubApiTest`, `hubModel.test.ts` | PHASE 31 |
| **TD-79** | GitHub anonimo concede 60 richieste all'ora sull'API: con molti repository o verifiche ravvicinate il limite si raggiunge (il Hub lo dice con l'ora di ripristino). Nessun token configurabile | **chiuso** (PHASE 32): `AICOS_GITHUB_TOKEN` (`aicos.hub.github-token`, nel `local.env`) porta il limite a 5000 richieste l'ora; il token va solo a `api.github.com`; senza token il messaggio di limite dice come alzarlo (ADR-036, revisione TD-79; RUNNING.md) — `HttpHubWebTest`, `SkillHubApiTest` | PHASE 31 |
| **TD-80** | Una skill personale scritta fuori dal Hub (es. `~/.verdent/skills/pdf`, copia di `anthropics/skills`) non è collegata alla sua fonte online: nessun aggiornamento proposto finché non la si importa, e anche allora la fonte è l'app, non il marketplace | **chiuso** (PHASE 32): `PUT /api/skill-hub/library/{key}/source` collega una skill della libreria al suo pacchetto online (anche se importata da un'app): allineata se identica, altrimenti l'ultima versione è un aggiornamento da confermare; «Collega a una fonte online» cerca il nome nei marketplace (ADR-036, revisione TD-80) — `SkillHubApiTest`, `SourceLink.test.tsx` | PHASE 31 |
| **TD-81** | Le knowledge (`CLAUDE.md`, `AGENTS.md`, knowledge items di Antigravity) si importano nella libreria ma non si copiano nelle app, e una knowledge importata si aggiorna reimportandola | **chiuso** (PHASE 32): una knowledge importata si aggiorna dalla sua fonte (`src:<chiave>`, con conferma se modificata e backup) e si copia nelle app come una skill fatta del suo file, con le regole delle copie; i file di istruzioni globali dell'operatore restano suoi (ADR-036, revisione TD-81) — `SkillHubApiTest`, `KnowledgeHub.test.tsx` | PHASE 31 |
| **TD-82** | La sincronizzazione automatica non rilegge la cartella della libreria (lo fanno «Sincronizza ora» e «Rileggi cartella»): `SkillLibrary.sync` aggiorna la versione di ogni voce anche quando il file non è cambiato, e farlo in background renderebbe obsoleti gli editor aperti | **chiuso** (PHASE 32): rileggere la cartella della libreria tocca solo le voci il cui file è cambiato, e la sincronizzazione pianificata ora rilegge anche la libreria (ADR-036, revisione PHASE 32) — `LibraryApiTest`, `SkillHubApiTest` | PHASE 31 |

| **TD-83** | I connettori dell'account claude.ai (quelli aggiunti su claude.ai e non da un plugin) non sono salvati in chiaro sul PC: il Hub conosce solo quelli che Claude Code ha già usato (`claudeAiMcpEverConnected`), per nome | **aperto**, vincolo esterno | PHASE 31 |
| **TD-84** | I plugin interni di Claude Code (`@builtin`: agents-md, plugin-authoring, telemetry) non hanno una cartella né un manifest: non sono elencati | **chiuso** (PHASE 32): i plugin `@builtin` sono elencati tra quelli di Claude Code da `pluginUsage` e `enabledPlugins` (solo nome, uso e stato), senza percorso né cartella, fotografati la prima volta (ADR-036, revisione TD-84) — `SkillHubApiTest`, `SkillHub.test.tsx` | PHASE 31 |
| **TD-85** | La scheda Code dell'app Claude riceve anche i plugin di Claude Desktop (`@inline`): il Hub li mostra una volta, sotto Claude Desktop, e lo dice nella descrizione di Claude Code invece di duplicarli | **aperto**, per scelta | PHASE 31 |

| **TD-86** | Claude Desktop riceve plugin e connettori remoti dall'account: il Hub non li installa, dice dove aggiungerli nell'app e la apre. Installa solo i connettori locali, in `claude_desktop_config.json` (ADR-036 §6b) | **aperto**, vincolo esterno | PHASE 31 |
| **TD-87** | ChatGPT (chat) non tiene sul PC né skill né app: il Hub vede le app dell'account solo dalla cache che ne tiene Codex (`~/.codex/cache/codex_apps_tools`), e solo se Codex è stato usato; le skill caricate su chatgpt.com non sono visibili (ADR-036 §2b) | **aperto**, vincolo esterno | PHASE 31 |
| **TD-88** | Un plugin che chiede opzioni all'installazione (`userConfig`, `claude plugin install --config k=v`) non le riceve dal Hub: la CLI lo segnala e l'opzione si completa in Claude Code (`/plugin`) | **aperto**, MINOR | PHASE 31 |
| **TD-89** | Connettori del registro MCP non installabili dal Hub, per prudenza: indirizzi remoti con query string, `%`, variabili da completare; pacchetti con argomenti obbligatori; per Codex, connettori remoti con un header d'autenticazione (la CLI non lo accetta). La scheda dice «da configurare a mano» e perché | **aperto**, MINOR | PHASE 31 |
| **TD-90** | Plugin e connettori installati dal Marketplace si disinstallano dalle loro app (`claude plugin uninstall`, `claude mcp remove`, `codex mcp remove`…), non dal Hub; nessuna verifica di aggiornamenti per i connettori | **aperto**, MINOR | PHASE 31 |

| **TD-91** | Una shell del terminale integrato non sopravviveva a un ricaricamento o alla chiusura della scheda del browser: il socket chiuso terminava il processo | **chiuso** il 2026-10-07 (ADR-029 §5): la shell vive sul server, staccata con gli ultimi 512 KiB e le modalità del terminale; la console la riaggancia dopo un ricaricamento e la elenca «In background»; finisce se chiusa dalla console, dopo `aicos.terminal.detached-ttl` o con il control plane | PHASE 31 |
| **TD-92** | Le shell del terminale ereditavano l'ambiente del backend, compresi i marcatori di una sessione Claude Code che lo avesse avviato (Claude Code nella console non salvava la trascrizione) | **chiuso** il 2026-10-07 (ADR-029 §5): su Windows ambiente dell'utente (`CreateEnvironmentBlock`), marcatori di sessione tolti ovunque | PHASE 31 |

**Prossimo identificatore libero: `TD-93`.**

## 6. Come si apre un debito nuovo

1. Si usa il prossimo numero libero **della numerazione viva** (§5), mai uno dello spazio audit.
2. Lo si registra in `PROJECT_STATE.md`, nella sezione «Debito aperto rilevante», con l'id, il
   contenuto e la task che lo ha aperto.
3. Lo si aggiunge alla tabella §5 di questo documento.
4. Non si riusa mai un identificatore chiuso: un id chiuso resta chiuso, e il suo significato
   resta leggibile per chi trova un vecchio riferimento.

## 7. Che cosa questo documento **non** fa

- **Non chiude nessun debito.** Mappa e disambigua.
- **Non rinumera niente**, in nessuno dei due spazi.
- **Non fonde i due registri**: uno è la fotografia di un momento, l'altro è vivo, e fonderli
  perderebbe la data.
- **Non rivaluta lo stato dei debiti dell'audit** (§4). Quella è la task successiva più ovvia su
  questo materiale, ed è registrata come tale.
