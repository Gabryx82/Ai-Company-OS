# ADR-025 — Legame Agente → Modello → Provider → Execution Target, e handoff senza API

- **Stato**: Accettata e implementata (PHASE 16–18)
- **Data**: 2026-09-25
- **Decisa da**: agente, su direttiva umana del 2026-09-25 (§1 UX di assegnazione, §4 configurazione
  degli agenti, §5 legame agente-modello, §6 cloud AI senza API OpenAI/Anthropic)
- **Estende**: ADR-018 (concetti separati), ADR-021 (Master Orchestrator), ADR-023 (harness)

## 1. Quattro concetti, quattro posti

| Concetto | Dove vive | Esempio |
|---|---|---|
| **Agente** | riga `agents` | Frontend Developer |
| **Modello** | `agents.model` (chiave del catalogo) | `ollama:deepseek-coder-v2:16b` |
| **Provider** | del modello, da `llm_models.provider_key` | Ollama (locale) |
| **Execution target** | `agents.execution_target` (V21) | `engine`, `claude-code`, `codex`, … |

Il provider non è mai scritto sull'agente: si ricava dal modello, così non può contraddirlo.
`AgentBindingService` mette i quattro in fila (`Binding`: modello, provider, target, validità,
problemi, avvertenze, sintesi) e **rifiuta le combinazioni che non possono funzionare**
(`400 binding-invalid`): un modello ad abbonamento sull'AI Engine, un modello locale in Claude Code.
Le avvertenze (modello superato, provider spento, strumento non installato) non rifiutano.

## 2. Gli execution target

`catalog/execution-targets.json`: per ciascuno, il software che apre, i provider i cui modelli usa,
il file di contesto che legge e **come riceve il prompt** (`delivery`):

| Delivery | Target | Cosa succede |
|---|---|---|
| `ENGINE_RUN` | AI Engine | run interna (ADR-016), risposta nella console |
| `CLI_PROMPT` | Claude Code, OpenCode | Windows Terminal nella cartella, prompt come argomento |
| `IDE_FOLDER` | Antigravity IDE, VS Code + Continue, IntelliJ + Junie | IDE sulla cartella, prompt negli appunti |
| `APP_PASTE` | Codex, Antigravity, Devin, Kimi, Verdent, ChatGPT | app aperta, prompt negli appunti |
| `WEB_PASTE` | Gemini | la console apre il sito, prompt negli appunti |
| `MANUAL` | — | pacchetto scritto, nessuno strumento aperto |

I modelli usati tramite app hanno provider `SUBSCRIPTION` (abbonamento Claude, ChatGPT, account
Google, JetBrains AI, Cognition, Moonshot, Verdent) e chiavi `…:default`: il modello preciso lo sceglie
l'app. **Nessuna API di OpenAI o Anthropic è il percorso standard**: il provider `anthropic` (API)
resta spento senza chiave, come deciso in ADR-015; OpenRouter resta un provider API opzionale e
separato.

Una run interna chiesta a un agente legato a un'app è rifiutata (`409 agent-works-elsewhere`),
salvo che l'operatore scelga esplicitamente un modello dell'AI Engine per quella sola run.

## 3. Origine e configurazione iniziale

`agents.origin` (`SEED`, `TEMPLATE`, `USER`) e `agents.baseline` (la configurazione con cui l'agente
è nato, JSON). La console confronta campo per campo e mostra «iniziale» o «modificato», chi e quando
ha modificato (`customized_at`, `customized_by`), e permette di **ripristinare** la configurazione
iniziale (`POST /api/agents/{id}/configuration/reset`). Gli agenti del seed di sviluppo ricevono la
loro configurazione dallo stream del seed (`db/dev/V2`), riempiendo solo i campi vuoti: un valore già
impostato da una persona resta, e risulta «modificato».

Default del seed, con i modelli verificati su questa macchina (Ollama 0.33.3, 2026-09-25):

| Agente | Modello | Motivo |
|---|---|---|
| Code Architect | `ollama:qwen3.5:9b` | ragionamento e architettura |
| Frontend Developer | `ollama:deepseek-coder-v2:16b` | generazione di codice |
| Database Specialist | `ollama:deepseek-coder-v2:16b` | SQL e migrazioni |

DeepSeek Coder V2 è stato verificato con una richiesta reale: SQL corretto in 16,6 s (13,8 s di
caricamento a freddo). Non ha tool calling: adatto a generare codice su richiesta, non a lavorare da
agente con strumenti — per questo gli agenti che devono usare strumenti vanno legati a un'app (Claude
Code, Codex).

## 4. L'handoff, per ogni task

Prima (PHASE 11) solo una task di piano poteva essere consegnata. Ora ogni task con un agente:

1. **Cartella**: quella del progetto, **creata se manca** (scaffold, mai sovrascrivendo); per una task
   senza progetto, `<workspace root>/_inbox/task-<id>`.
2. **Documento della task**: quello del piano, oppure `tasks/TASK-<id>.md` (o `TASK.md` nell'inbox),
   scritto solo se non esiste.
3. **Ambiente dello strumento**: `CLAUDE.md` / `AGENTS.md` già presenti nel progetto; `.junie/guidelines.md`
   per Junie e `.continue/rules/aicos.md` per Continue, scritti **solo se assenti o già generati** (un
   marcatore distingue i nostri file da quelli dell'operatore, che non vengono mai toccati).
4. **Pacchetto** `.aicos/handoffs/<TASK>-<target>.md`: cartella, prompt, ruolo (descrizione, system
   prompt, responsabilità, direttive, limiti, output, politica di contesto), modello e provider,
   skill/knowledge/tool/MCP con la loro configurazione, file da leggere, regole di Human-in-the-Loop,
   cosa lasciare scritto alla fine.
5. **Prompt compatto** («Leggi il pacchetto ed esegui») e **prompt completo** (tutto inline, per le app
   che non leggono file); quello giusto va negli appunti.
6. **Apertura** secondo la delivery; la task passa a `IN_PROGRESS`; l'handoff è registrato.

La console chiede sempre conferma con il riepilogo **COSA → A CHI → CON QUALE MODELLO → ATTRAVERSO**.

## 5. Onestà sui limiti

- Nessuna automazione dell'interno delle app: AI Company OS apre lo strumento e prepara tutto, ma
  non «preme invio» dentro Codex o Kimi, e non legge in automatico l'esito — l'operatore fa la review.
- Registrare un MCP su un agente resta un metadato (TD-48): il pacchetto ne riporta la configurazione,
  ma non la installa nello strumento.

## 6. Test

`AgentBindingApiTest` (target, combinazioni valide e rifiutate, template con modifiche e ripristino,
run rifiutata per un agente che lavora con Claude Code, vecchio endpoint coerente),
`HandoffAnywhereApiTest` (task fuori piano, inbox, regole di Junie senza sovrascrivere l'operatore,
prompt completo per le app, web non lanciato, engine non consegnabile), `DevSeedMigrationTest`
(seed marcati, modelli, nessuna modifica), `OrchestratorApiTest`; console: `TaskDrawer.test`
(assegnazione con la frase COSA → A CHI → CON → ATTRAVERSO), `OrchestratorPanel.test` (conferma prima
dell'apertura, pannello anche fuori piano).

## Revisione PHASE 32 (2026-10-08) — gli MCP dell'agente nello strumento (TD-48)

Rivede il §5: un MCP registrato su un agente non è più solo un metadato. All'handoff, gli MCP dell'agente
la cui voce dice come avviarli — una sola riga, un launcher noto (`npx`, `uvx`, `node`, `python`,
`docker`…), argomenti semplici, nessun segnaposto se non la cartella del progetto (`<cartella>`) — vengono
scritti nel file di progetto che lo strumento legge: `.mcp.json` per Claude Code, `opencode.json` per
OpenCode. Solo le voci mancanti si aggiungono; quelle già presenti sono dell'operatore e restano come sono;
un file che non è un oggetto JSON non si tocca. Gli altri MCP (un token da chiedere, un server configurato a
mano su questa macchina, uno strumento di cui AI Company OS non scrive la configurazione) sono elencati nel
pacchetto come «da configurare a mano». La scrittura passa da `writeToolConfig`, che accetta solo quei due
nomi: l'API dei documenti non può scriverli. Test: `HandoffMcpTest` (payload inerti rifiutati: `&rem`,
`>NUL`, `|more`, `$HOME`, segnaposto, più righe), `OrchestratorApiTest` (rosso senza la configurazione).

## Revisione PHASE 32 (2026-10-08) — l'handoff a OpenCode si segue dalla sua API locale (TD-49)

Rivede il §5 per OpenCode: la TUI che l'handoff apre serve anche l'API locale di OpenCode (`--port`), su una
porta libera del loopback che AI Company OS sceglie e registra con l'handoff (`task_handoffs.follow_port`,
V38). `GET /api/handoffs/{id}/session` legge da `127.0.0.1:<porta>` — mai altrove — la sessione più
recente (non quelle dei sotto-agenti): al lavoro, in attesa o in riprova, i file cambiati (+/−), l'ultima
risposta (tagliata a 4000 caratteri). Solo lettura: nulla viene inviato alla sessione, e la review resta
dell'operatore. Senza risposta sulla porta lo dice («OpenCode non risponde»: la finestra è stata chiusa); un
handoff verso un altro strumento non si segue (`NOT_FOLLOWED`). Nella console, il pannello del Master
Orchestrator mostra la sessione dell'ultimo handoff seguibile e la rilegge ogni 5 secondi mentre è aperto.
API verificata su OpenCode 1.18.18 (`/session`, `/session/status`, `/session/{id}/message`). Test:
`OrchestratorApiTest` (un OpenCode finto in HTTP sulla porta scelta; rosso se si segue un sotto-agente),
console `OrchestratorPanel.test.tsx` (rosso se si segue un handoff non seguibile).

## Revisione PHASE 34 (2026-11-07) — Claude Code nell'app desktop, consegnato come la CLI

Direttiva dell'operatore del 2026-11-07: «aggiungi Claude Code app desktop come soluzione per consegnare la task come
per la cli». Il motivo è nei fatti: delle 17 sessioni di Claude Code sul progetto *interfaccia-movieseseries* solo una
era partita da una consegna, e dal 3 novembre tutte venivano da Claude Desktop, che non era un execution target. Quelle
sessioni partivano da «Esegui TASK-NNN…» incollato a mano, senza pacchetto: l'agente non riceveva ruolo, skill,
knowledge né MCP, e nessun handoff restava registrato.

- **Nuovo target `claude-desktop`** («Claude Code (app desktop)»), provider `claude-subscription`, file di contesto
  `CLAUDE.md`, sul software `claude-desktop` del catalogo.
- **Nuova delivery `APP_LINK`**: l'app si apre con il suo link, sulla cartella e con il prompt già nel compositore.
  Per Claude Desktop è `claude://code/new?folder=<cartella>&q=<prompt>`, documentato da Anthropic (articolo di
  supporto 14729294) e verificato nel codice dell'app installata (4.23118: legge `q` o `prompt`, `folder`, `file`).
  Il prompt non parte da solo e l'app chiede sempre di confermare la cartella: l'operatore conferma e invia. Il
  prompt è quello compatto, «Leggi .aicos/handoffs/<TASK>-claude-desktop.md ed esegui la task che descrive.»;
  `promptToClipboard` è falso, come per la CLI.
- **Il link è del catalogo** (`execution-targets.json`, campo `link`, solo per `APP_LINK`, e un `APP_LINK` ne ha
  sempre uno e un software: lo impone il record). `LaunchPlan.forLink` mantiene gli invarianti di ADR-019 come
  per la CLI: il programma è `explorer.exe`, che passa il link al gestore che Windows ha registrato per lo schema
  (I1); lo schema deve essere di un'app — rifiutati web, `file:`, `ms-settings:`, `shell:` e un link del catalogo
  con query o frammento; la cartella è quella risolta dal control plane ed esiste (I2); nessuna shell, e i due
  valori sono codificati per cento (RFC 3986, spazio come `%20`), così il link contiene solo lettere, cifre,
  `%._*-` e i separatori `?=&` (I3); il prompt ha al più 14.000 caratteri, quanti l'app ne tiene.

Test: `LaunchPlanTest` (link, prompt ostile che resta un solo valore, schemi rifiutati, app non installata o
disabilitata, cartella mancante, prompt vuoto o troppo lungo), `ExecutionTargetCatalogTest`, `AgentBindingApiTest`
(il target nell'elenco, l'abbonamento valido e un modello locale no), `HandoffAnywhereApiTest` (pacchetto,
`explorer.exe` con il link, task in corso, handoff registrato); console `OrchestratorPanel.test.tsx` (la consegna
all'app non copia nulla negli appunti e dice di confermare e inviare).

## Revisione PHASE 36 (2026-11-08) — la consegna si verifica prima, e porta tutto (ADR-042)

- **§4, il pacchetto**: il modello è descritto sul target della consegna, non su quello dell'agente: a uno strumento
  che non usa il provider del modello dell'agente il pacchetto dice «scelto nell'app o nella CLI» invece di dichiarare
  un modello che non può usare. Porta anche il software dell'agente, le capability, il dominio e il genitore.
- **Claude Code nell'app desktop**: scrive `.mcp.json` come la CLI (è Claude Code, legge lo stesso file del progetto),
  e il prompt, oltre che nel compositore tramite il link, è **negli appunti** (`promptToClipboard = true`): la revisione
  di PHASE 34 diceva il contrario.
- **Prima della consegna**: `GET /api/tasks/{id}/readiness` dice, per quel target, che cosa troverà l'agente — ogni
  voce ok, avviso o bloccante — e la consegna rifiuta i bloccanti con `409 delivery-not-ready` **prima** di scrivere il
  pacchetto o aprire lo strumento (prima uno strumento non installato faceva scrivere i file e poi falliva al lancio).
  La run fa lo stesso. Il pannello del Master Orchestrator mostra la verifica e la conferma «Prepara e apri» resta
  disabilitata finché c'è un bloccante.
- **Il prompt mostrato** (documento della task, pannello, `CONTEXT_MAP.md`) porta al pacchetto della consegna; se la
  task non è stata consegnata, l'agente chiede la consegna invece di partire senza ruolo, skill e knowledge.
- **Con il launcher spento** (`aicos.launcher.enabled=false`, o un host non Windows) la consegna non fallisce più con
  `503` dopo aver scritto il pacchetto: prepara tutto, registra il comando che avrebbe eseguito, mette il prompt negli
  appunti e risponde con `notOpened`; la verifica lo dice prima, come avviso (ADR-042 §6.1).

Test: `HarnessChainApiTest`, `TaskReadinessApiTest`, `DeliveryPathApiTest`, `HandoffAnywhereApiTest`,
`OrchestratorPanel.test.tsx`.
