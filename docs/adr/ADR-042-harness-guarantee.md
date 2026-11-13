# ADR-042 — Garanzia dell'harness: ciò che si configura arriva all'agente, e lo si vede prima

- **Stato**: Implementata (PHASE 36), in attesa della review umana; non committata
- **Data**: 2026-11-08
- **Decisa da**: agente, su direttiva dell'operatore del 2026-11-08 («garantire, con test end-to-end, che ogni agente
  riceva per ogni task il contesto, le risorse e le skill giuste, e che ciò che si configura nella pagina di modifica
  degli agenti venga davvero salvato, riletto e caricato»; «non rimanere debiti aperti»)
- **Rivede**: ADR-023 §4, ADR-025 §4, ADR-026 §3 (e la revisione PHASE 35), ADR-020 W1, ADR-041 §8. Chiude TD-97, TD-98,
  TD-99.

## 1. Problema

La pagina dell'agente ha quattordici campi e sei tipi di legame (skill, knowledge, tool, framework, MCP, software).
Nessun test seguiva un campo dall'API fino a ciò che l'agente riceve: il pacchetto di handoff per ogni strumento e il
prompt che una run dell'AI Engine registra. Un test end-to-end campo per campo (`HarnessChainApiTest`, 28 casi) ha
trovato **sette difetti**, tutti nell'ultimo tratto — salvataggio, database e rilettura erano corretti:

| # | Campo | Dove si fermava |
|---|---|---|
| 1 | Software dell'agente | salvato, mai nel pacchetto, nella run né nella decisione del Master Orchestrator |
| 2 | Politica di contesto, descrizione | nel pacchetto, non nel prompt della run |
| 3 | Capability, dominio | né nel pacchetto né nella run |
| 4 | Sottoagente di | il genitore non era nominato |
| 5 | Knowledge non pertinente | tolta dal pacchetto degli strumenti con skill native (ADR-041 §8) |
| 6 | Modello | dichiarato anche a uno strumento che non può usarlo («Qwen 3.5 4B, Ollama» a Claude Code) |
| 7 | MCP a Claude Code nell'app desktop | «da configurare a mano», benché legga lo stesso `.mcp.json` della CLI |

E restavano le lacune note: il prompt mostrato nel documento della task, nel pannello e in `CONTEXT_MAP.md` saltava il
pacchetto; nessuno vedeva, prima di aprire lo strumento, se la copia del Knowledge Hub era nella sua cartella; una skill
assegnata senza file in libreria, o con un frontmatter non valido, spariva in silenzio.

## 2. Invarianti

- **I1 — Ogni campo arriva.** Un campo della pagina salvato dalla API si ritrova nel database, nella rilettura, nel
  pacchetto di ogni target e nel prompt della run. `HarnessChainApiTest` lo verifica per Claude Code (CLI e app),
  Codex, OpenCode, Antigravity (app e IDE) e il pacchetto manuale.
- **I2 — Niente sparisce in silenzio.** Una voce che non arriva intera è detta: nel pacchetto all'agente, nella
  verifica all'operatore.
- **I3 — Prima di aprire, si sa.** `GET /api/tasks/{id}/readiness` dice che cosa troverà l'agente su quel target; la
  consegna e la run si fermano su ciò che blocca, prima di scrivere o aprire qualcosa.
- **I4 — Il prompt mostrato porta al pacchetto.**
- **I5 — Un testo generato si aggiorna solo se è ancora com'era**: ciò che l'operatore ha cambiato resta.

## 3. Ogni campo arriva (difetti 1–7)

- **Pacchetto** (`HandoffPackager`): in «Chi sei» anche capability, dominio e «Sei un sottoagente di X»; in «Con che
  cosa» la riga **Software che puoi usare**; il prompt completo la ripete. Il modello è descritto **sul target della
  consegna** (`AgentBindingService.describe(modello, target)`): se lo strumento non usa quel provider il pacchetto dice
  «scelto nell'app o nella CLI» e non nomina il modello — la verifica avvisa l'operatore.
- **Run** (`RunContext`): nella parte stabile del prompt di sistema anche descrizione, capability, dominio, genitore,
  politica di contesto e «Software you may use». Un agente senza nessuno di questi campi e senza harness produce ancora
  il prompt di PHASE 6, byte per byte.
- **Knowledge** (rivede ADR-041 §8): agli strumenti con skill native la knowledge pertinente si chiede di leggerla; quella
  non pertinente è **nominata** con «consultala solo se la task la richiede» e il percorso, lettura facoltativa nelle
  misure (`task_handoff_reads.required = false`). Le letture obbligatorie misurate da ADR-041 §6 non cambiano.
- **MCP**: `claude-desktop` scrive `.mcp.json` come `claude-code` (`HandoffMcp.PROJECT_FILES`).
- **Decisione del Master Orchestrator**: il software dell'agente compare per primo in «Software necessari».

## 4. Niente sparisce in silenzio

`SkillLibrary.check` dà lo stato del file di una skill o knowledge: `OK`, `MISSING`, `INVALID` (frontmatter assente o
illeggibile, chiave diversa, kind diverso dalla voce). Il pacchetto lo scrive sotto la voce («⚠ nessun file nella
libreria: di questa voce hai solo la descrizione», «⚠ il file non è un documento valido della libreria (motivo):
leggilo com'è»), la verifica lo mostra come **avviso**. Non è bloccante: l'agente riceve comunque nome e descrizione, e
fermare il lavoro per un file mancante sarebbe peggio che dirlo.

## 5. Il prompt mostrato porta al pacchetto, anche nei progetti già iniziati

- `PlanMarkdown.compactPrompt` diventa «Esegui TASK-NNN partendo dal suo pacchetto di consegna, il più recente
  `.aicos/handoffs/TASK-NNN-*.md` (ruolo, skill, knowledge e regole); se non c'è, chiedi all'operatore di consegnare la
  task da AI Company OS prima di cominciare». Lo usano il documento della task («Come si consegna»), il pannello («Prompt
  da incollare a mano», chiuso sotto la verifica e la consegna) e l'orchestrazione anche per le task fuori piano.
- `AGENTS.md` (template): il primo passo è il pacchetto; se non c'è, chiedere la consegna. `CONTEXT_MAP.md`: il prompt
  della consegna e quello a mano.
- **Rivede ADR-020 W1** — *aggiornamento dei testi generati*: alla consegna `ProjectWorkspaceService.upgradeGenerated`
  confronta `AGENTS.md`, `.aicos/CONTEXT_MAP.md` e i `tasks/*.md` con i testi che AI Company OS scriveva prima
  (`GeneratedTextUpgrades`: i passi di `AGENTS.md` fino a PHASE 33 e il primo passo di PHASE 34, il prompt della mappa,
  le sezioni «Prompt compatto» e «Come si consegna» di PHASE 34). Un passaggio trovato **identico** è sostituito con la
  versione corrente, gli a capo del file restano; tutto il resto del file è intatto. Prima di scrivere, il file com'era
  è copiato in `.aicos/upgrades/<data-ora>/`. Un passaggio cambiato dall'operatore non è più identico e resta: la
  verifica lo segnala come avviso («non porta al pacchetto»). Lo scaffold continua a non sovrascrivere (W1 per lo
  scaffold resta com'è).
- **Claude Code nell'app desktop** (rivede ADR-025, PHASE 34): il prompt va nel compositore con il link e **anche negli
  appunti** (`promptToClipboard = true` per `APP_LINK`), per quando l'app non lo prende.

## 6. La verifica prima della consegna e della run

`GET /api/tasks/{id}/readiness?target=…&model=…` (senza target: quello dell'agente). Ogni voce ha un'area, uno stato
(`OK`, `WARNING`, `BLOCKING`) e il motivo:

| Area | Che cosa si verifica |
|---|---|
| `TASK` | i rifiuti della task (archiviata, completata, fase non approvata, nessun agente, agente inattivo, progetto senza cartella) — **bloccanti**, con il problema che avevano già |
| `BINDING` | lo strumento si apre: le regole di `LaunchPlan`, senza lanciare (`SoftwareService.launchProblem`) — **bloccante** se no; avviso se l'agente è configurato per un altro target; per la run, l'agente che lavora altrove |
| `MODEL` | sul target: compatibile (ok) o scelto dallo strumento (avviso); per la run: eseguibile dall'engine, nel budget (bloccanti), servito ora (avviso se no) |
| `CONTEXT` | `AGENTS.md` (porta al pacchetto, si aggiorna, o modificato), `CLAUDE.md`/`GEMINI.md` che importano `AGENTS.md`, il file dello strumento, il documento della task e della fase, i file che la task nomina |
| `SKILL`, `KNOWLEDGE` | file in libreria (manca, non valido); per gli strumenti con skill native la copia del Knowledge Hub nella loro cartella: allineata, modificata nell'app (ok), mancante o indietro (avviso, con l'azione «Copia nello strumento»), estranea o in conflitto (avviso, si decide nel Knowledge Hub); per la run, quali arrivano con la parte essenziale |
| `MCP` | scritto nel file del progetto che lo strumento legge (ok) o da configurare a mano (avviso); l'engine non usa MCP (avviso) |
| `TOOL` | tool, framework, template provider: nel pacchetto con la configurazione, nella run per nome |
| `SOFTWARE` | installato (ok) o no (avviso) |

La consegna (`POST /api/tasks/{id}/handoffs`) e la run (`POST /api/tasks/{id}/runs`) chiamano la stessa verifica — senza
interrogare l'AI Engine, che dentro la loro transazione terrebbe il lock della task per un avviso al più
(`check(…, probeEngine = false)`, blocchi e contesto da `OrchestrationService.basics`) — e,
fuori dall'area `TASK` (che ha già i suoi rifiuti e i suoi problemi), rifiutano ciò che blocca con
**`409 delivery-not-ready`**, prima di scrivere il pacchetto, di avviare la task o di aprire lo strumento; `errors` nomina
ogni voce. Prima uno strumento non installato faceva scrivere i file e poi falliva al lancio.

Nella console il pannello del Master Orchestrator mostra «Verifica prima della consegna» per il target dell'agente, con
la scelta di un altro (anche l'AI Engine, per la run); la conferma «Prepara e apri» mostra la verifica di quel target e
resta disabilitata finché c'è un bloccante. I record sono `Readiness`, `ReadinessItem`, `ReadinessAction`,
`ReadinessStatus`: nomi propri, per non finire sotto lo schema di un altro (TD-97).

### 6.1 Con il launcher spento la consegna si prepara comunque (trovato dallo smoke)

Con `aicos.launcher.enabled=false`, o un control plane non su Windows, la verifica diceva «pronta» e la consegna scriveva
il pacchetto e poi falliva con `503 launcher-unavailable` al momento di aprire lo strumento. Ora il launcher dice prima
perché non aprirebbe nulla (`SoftwareLauncher.unavailable()`, con due motivi distinti: spento per configurazione, o host
non Windows); la verifica lo mostra come **avviso** («la consegna prepara pacchetto e prompt ma non apre lo strumento»), e
la consegna prepara tutto — cartella, governance, pacchetto, task avviata, consegna registrata con il comando che *avrebbe*
eseguito (`SoftwareService.planIn`/`planLink`, le stesse regole di `LaunchPlan` senza avviare) — e risponde con
`notOpened` e il prompt negli appunti. La console lo dice: «Lo strumento non è stato aperto: … Aprilo tu nella
cartella». Il pulsante «Avvia» del Software Hub resta un `503` come prima: lì aprire è tutto il gesto.

### 6.2 Un modello che l'engine non elenca

Per la run la verifica chiede all'AI Engine se serve il modello ora (solo nella `GET`); se non lo elenca riporta il motivo
che l'engine dà per il provider («Ollama is not reachable…»). Nello smoke è capitato una volta, con Ollama lento a
rispondere a `/api/tags` entro i 3 s dell'engine: un avviso transitorio, non un blocco.

### 6.3 Un Ollama acceso il cui elenco dei modelli non arriva (segnalato dall'operatore il 2026-11-09)

La Dashboard diceva «Modelli pronti: 1», solo `echo:default`. Il log di Ollama mostra perché: alle 23:55 del 2026-11-08
il suo aggiornamento dei «model recommendations» da `ollama.com` è scaduto (`context deadline exceeded`), e nei due minuti
seguenti ogni `GET /api/tags` è rimasto appeso 12–55 s per finire in `500`, mentre `/api/version` rispondeva in 1 ms;
poco dopo la prima chiamata ha impiegato ancora 15 s, le successive 7–10 ms. L'engine aspettava `/api/tags` 3 s e, oltre,
dichiarava Ollama «non raggiungibile»: la console contava solo l'echo, e la verifica della run avvisava a torto.

Ora `OllamaProvider.models`, se `/api/tags` non arriva, chiede `/api/version` (2 s): se Ollama risponde è acceso, e i suoi
modelli restano disponibili — quelli dell'ultimo elenco riuscito, o, senza un elenco precedente, quelli installati nella
sua cartella (`manifests/<registry>/<namespace>/<modello>/<tag>`, con i nomi che dà `/api/tags`), con il motivo nel
dettaglio di ciascuno. Solo un Ollama che non risponde a niente resta «non raggiungibile»; un Ollama acceso senza nulla
da elencare lo dice. La cartella è `AICOS_ENGINE_OLLAMA_MODELS_DIR`, altrimenti `OLLAMA_MODELS` come per Ollama,
altrimenti `~/.ollama/models`; viene solo letta. La risposta dell'engine resta entro i 10 s che il backend gli concede
(3 + 2 s). Test: quattro in `ai-engine/tests/test_providers.py` (visti rossi, e di nuovo rossi togliendo il ripiego), più
quello della variabile. Dal vivo: un engine isolato (8097) collegato a un finto Ollama con `/api/tags` appeso 20 s ha
risposto in 4 s con gli 8 modelli installati su questa macchina, gli stessi nomi di `/api/tags`. Perché Ollama non
raggiunga `ollama.com` (rete, proxy) è fuori da AI Company OS.

## 7. Debiti chiusi

- **TD-97** — `OpenApiSchemaNamesTest`: percorre ogni tipo che una rotta prende o restituisce, record dentro record, e
  fallisce se due classi diverse hanno lo stesso nome semplice.
- **TD-98** — a 375 px la barra in alto va a capo (la ricerca su una riga sua, data e nome dell'utente nascosti) e le
  tabelle di `CostsCard` e della pagina Modelli scorrono dentro `.table-scroll`. Misurando dal vivo è venuta fuori la
  causa comune delle altre pagine larghe (Dashboard 453 px, Modelli 607 px): `.stack` è una griglia a una colonna, e
  senza `grid-template-columns: minmax(0, 1fr)` un figlio largo allarga la colonna oltre lo schermo. Con la correzione,
  sotto i 600 px intestazioni e hero vanno a capo e `.grid-2` è a una colonna. Misurato a 375 px: Dashboard, Task,
  Consumi, Agenti, Progetti, Modelli, Knowledge Hub, Software Hub, Impostazioni, Daily Work tutte larghe 375 px.
- **TD-99** — `testTimeout` di Vitest a 15 s nella configurazione: il chunk di three.js e il render più grande del
  Knowledge Hub superano i 5 s sotto carico; un timeout non nasconde un'asserzione sbagliata, solo la lentezza.

## 8. Test

`HarnessChainApiTest` (I1, 28 casi), `TaskReadinessApiTest` (verifica per Claude Code, Codex e l'engine, copia proposta e
fatta, file mancante e non valido nel pacchetto, strumento non installato che ferma la consegna senza scrivere nulla,
task senza agente, run di un agente che lavora altrove), `DeliveryPathApiTest` (prompt mostrato, aggiornamento di un
progetto iniziato prima con backup, il prompt riscritto dall'operatore intatto, secondo handoff che non riscrive),
`GeneratedTextUpgradesTest`, `OpenApiSchemaNamesTest`, `ProcessSoftwareLauncherTest` (i due motivi del launcher) e in
`TaskReadinessApiTest` la consegna con il launcher spento; nella console `Agents.test.tsx` (la pagina salva e rilegge ogni
campo, viste rosse due mutazioni) e `OrchestratorPanel.test.tsx` (verifica, bloccante che disabilita la consegna, copia
nello strumento, Claude Desktop con gli appunti, strumento non aperto). Viste rosse undici mutazioni del backend (verifica
ignorata dalla consegna, politica di contesto tolta dalla run, `.mcp.json` tolto a Claude Desktop, file mancante taciuto,
a capo CRLF persi, knowledge tolta di nuovo, due schemi con lo stesso nome, modello dichiarato a ogni strumento, software
tolto dalla run, aggiornamento non chiamato, launcher spento ignorato) e quattro della console (campo non inviato, campo
non riletto, «Prepara e apri» non disabilitato, copia nello strumento non fatta). Rivisti perché fissavano le decisioni superate: `HandoffSkillsApiTest`
(knowledge non pertinente nominata, lettura facoltativa) e `HandoffAnywhereApiTest` (Claude Desktop con gli appunti).

## 9. Smoke dal vivo (2026-11-08)

Isolato: backend 8095 (jar della fase), engine 8096, console 5182, database `aicompany_p36`, home, libreria, Hub e cartelle
dei progetti nello scratchpad della sessione, launcher spento, password e token generati per lo smoke; l'8081 e il
`~/.aicos` dell'operatore non toccati. Uno script (`p36_smoke.py`, accanto a `start-smoke.sh`) crea un agente con ogni
campo della pagina, un genitore, due skill della libreria (una con la copia del Hub in Claude Code, una senza), una skill
senza file, una con il frontmatter rotto, due knowledge (una pertinente, una no), un tool, un framework, due MCP (uno
configurabile, uno a mano), due software; un progetto che adotta una knowledge e il tool; una task. **79 controlli su 79**:

- la rilettura di ogni campo; la verifica per i sette target e per l'engine (nessun bloccante; avvisi: launcher spento,
  modello dell'agente non usabile dallo strumento, copia mancante, file mancante e non valido, MCP a mano, agente
  configurato per un altro target);
- la consegna a Claude Code (CLI e app), Codex, OpenCode, Antigravity (app e IDE) e manuale: in ogni pacchetto ogni campo,
  ogni risorsa, il genitore, il software, i due «⚠», nessun modello che lo strumento non usa, il prompt che porta al
  pacchetto; `.mcp.json` e `opencode.json` con il server; `AGENTS.md`, `CLAUDE.md` (con `@AGENTS.md`), `GEMINI.md`,
  `CONTEXT_MAP.md`; i comandi registrati (`wt.exe … claude …`, `explorer.exe claude://code/new?folder=…&q=…`, l'app Codex,
  `wt.exe … opencode --prompt …`, `Antigravity.exe`, `Antigravity IDE.exe <cartella>`);
- una run dell'AI Engine con `qwen3.5:4b`: riuscita (470 token in, 1.135 out, 162 s; prompt di sistema 1.673 caratteri) e
  nel prompt registrato ogni campo, il genitore, le risorse, il software e la sezione essenziale della skill pertinente;
- una copia dei file di *interfaccia-movieseseries* (letti, mai scritti): alla prima consegna `AGENTS.md` parte dal
  pacchetto, la mappa del contesto ha i due prompt, i **34** documenti con il «Prompt compatto» generato passano a «Come si
  consegna», i **2** che l'operatore aveva riscritto (TASK-034 «Non eseguire…», TASK-036 con una frase sua) restano byte
  per byte, fuori dalla sezione nessun documento cambia, `CLAUDE.md` intatto, una copia di ogni file cambiato in
  `.aicos/upgrades/`.

Nella console dello smoke: la verifica nel pannello per l'engine e per Claude Code, «Copia nello strumento» che porta la
skill da avviso a ok, e la larghezza a 375 px (§7, TD-98).

## 10. Che cosa resta fuori

- **L'apertura reale degli strumenti** con il launcher acceso non è parte dei test né dello smoke isolato (aprirebbe
  sessioni vere sul desktop dell'operatore, con il suo abbonamento): lo smoke verifica pacchetti, file scritti, comandi
  registrati e prompt; i comandi sono quelli che `LaunchPlan` costruisce, coperti da `LaunchPlanTest`.
- **I progetti reali** non sono stati aggiornati da questa fase: lo saranno alla loro prima consegna, con la copia in
  `.aicos/upgrades/`.
- **Codex, Antigravity e Antigravity IDE** leggono gli MCP solo dalla configurazione dell'utente: AI Company OS non la
  scrive (ADR-025, revisione PHASE 32), e la verifica lo dice come avviso.
