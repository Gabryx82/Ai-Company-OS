# Gap Analysis V2 — visione riallineata vs. stato dopo PHASE 3–7

> Scritta il 2026-09-24 in risposta alla direttiva umana di **riallineamento architetturale del
> prodotto** (stessa data). PHASE 3–7 non sono fallite: il flusso progetto → task → agente →
> Start → risposta del modello è stato verificato dall'operatore. Questo documento misura il
> **delta** fra quella base e l'ecosistema descritto dalla direttiva, e non ripete l'audit di
> TASK-000 (`docs/audit/`).
>
> Legenda: ✅ già presente · 🟡 presente parzialmente · 🔧 implementato ma da estendere ·
> ❌ assente · ⛔ incompatibile con l'architettura corrente (serve una decisione/ADR) ·
> ⏸ da rimandare (fuori dal Minimum Usable).

## 0. Come è stato misurato

- Stato reale: branch `autonomous/phase-7-operator-console` (`670b6bf`), suite **329 Java + 51
  engine + 14 console = 394 verdi** rieseguita il 2026-09-24 prima di toccare qualsiasi cosa.
- Ambiente reale della macchina (inventario del 2026-09-24, registro di Windows, `Get-StartApps`,
  `Get-AppxPackage`, porte in ascolto, API di Ollama): i dati sono in §3 e §5, non stimati.
- Politiche di embedding dei servizi web: **misurate** con gli header HTTP (`X-Frame-Options`,
  `Content-Security-Policy: frame-ancestors`) il 2026-09-24, §4.

## 1. Matrice delle capability

| # | Capability della visione | Stato | Che cosa c'è | Che cosa manca |
|---|---|---|---|---|
| 1 | Progetti | 🔧 | Registry con ciclo di vita, concorrenza, archiviazione (ADR-004/006) | Tipologia, stack, cartella di lavoro, livello HITL, stato del piano |
| 2 | Task | 🔧 | Ciclo di vita a 4 archi, priorità, dettagli, assegnazione (ADR-010/014) | Appartenenza a una fase, codice `TASK-NNN`, documento `.md`, approvazione |
| 3 | Associazione task ↔ progetto | ✅ | ADR-005 | — |
| 4 | Agenti | 🔧 | Registry con modello per agente (V11), routing lessicale (TASK-020) | Prompt engineering (system role, responsabilità, limiti, output), dominio, capability |
| 5 | Sottoagenti | ❌ | — | Gerarchia agente → sottoagente |
| 6 | Harness engineering | ❌ | — | Direttive, skills, knowledge, MCP, tool, software per agente |
| 7 | Esecuzione reale di un agente | ✅ | Run attraverso l'AI Engine, stato canonico in Spring (ADR-016) | Il prompt non porta il contesto dai file (solo i campi del DB) |
| 8 | Master Orchestrator | 🟡 | Il vecchio `MasterOrchestrator` è stato **rimosso** (TD-08); esiste solo il routing lessicale | Piano, scelta agente/modello/software, contesto, prompt compatto, delega, verifica, review |
| 9 | Brainstorming → `MASTER_PROMPT.md` | ❌ | Il *repository* ha un `docs/MASTER_PROMPT.md`, i *progetti gestiti* no | Artefatto per progetto, registrato e letto dall'orchestratore |
| 10 | `IMPLEMENTATION_PLAN.md` / `PHASE_X.md` / `TASK-XXX.md` per progetto | ❌ | Esistono **per questo repository** (`.company-os/`, `tasks/`) come convenzione manuale | Generazione, struttura, legame file ↔ DB |
| 11 | Context engineering | 🟡 | `.company-os/CONTEXT_POLICY.md` descrive la politica per *questo* repository | Mappa del contesto per progetto/fase/task/agente, prompt compatti che rimandano ai file |
| 12 | Human-in-the-Loop | 🟡 | Una run non completa il task (ADR-016 §3): l'operatore legge e decide | Approvazione di piano e fasi, verdetto di review, livelli di autonomia progressivi |
| 13 | Learning with Agent | ❌ | — | Modalità didattica legata al livello HITL |
| 14 | Software Hub | ❌ | — | Catalogo, rilevamento installazione, launcher, capability, relazione con agenti/tipi di progetto |
| 15 | Claude Code / Codex / Antigravity / OpenCode come execution target | ❌ | Solo l'AI Engine è un target | Handoff su file + apertura dell'applicazione/terminale nella cartella del progetto |
| 16 | Terminale (PowerShell / Claude / OpenCode) | ❌ | — | Scheda terminale |
| 17 | Gmail, Drive, ClickUp nell'interfaccia | ❌ | — | Vedi §4: l'iframe è **impossibile** per policy; servono apertura controllata o una shell desktop |
| 18 | Open WebUI embedded | ❌ | — | Vedi §5: **conflitto di porta 8080** con il backend |
| 19 | Provider / Model catalog con ruoli | 🟡 | L'engine elenca i modelli vivi (`GET /v1/models`); `agents.model` | Catalogo persistente con ruolo, capability, dimensione, provider separato dal modello |
| 20 | OpenRouter | ❌ | — | Provider OpenAI-compatibile, spento senza chiave |
| 21 | DwarfStar4 | ⛔ | — | Hardware incompatibile su questa macchina (§5.3) |
| 22 | Consumi e quote dei modelli cloud | ❌ | Le run registrano i token (V10) | Scheda consumi, finestre di reset (giornaliera/5h/settimanale) |
| 23 | Daily Work | ❌ | — | Oggi/domani, referenze ai task di progetto senza duplicarli |
| 24 | Second Brain (grafo interattivo) | ❌ | — | Grafo animato ed esplorabile dell'ecosistema |
| 25 | Graph engineering (dependency/code/task graph) | ❌ | — | Generatori di grafi collegabili alle task |
| 26 | Reference visuali (Gemini, mockup) | ❌ | — | Cartella `references/` per progetto, parte del contesto |
| 27 | Template / Mockup Hub | ❌ | — | Provider estensibili |
| 28 | Framework / Skill / Knowledge Explorer | ❌ | — | Catalogo ricercabile, installabile su agenti/progetti |
| 29 | Separazione Agent/Model/Software/Provider/Tool/Skill/Knowledge/MCP/Subagent | 🟡 | Agent e Model sono già separati (`agents.model` è un riferimento, non un'entità) | Gli altri sette concetti non esistono ancora |
| 30 | Omniverse (3D) | 🟡 | Esiste come **applicazione separata** dell'operatore (`C:\Users\gabry\3D Omniverse`, porta 8800) | Registrazione come software/servizio, collegamento con i progetti 3D |
| 31 | UI dell'ecosistema (reference allegate) | 🔧 | Console React/TS con tipi generati (ADR-017) | Shell a navigazione estesa, tema, viste nuove |

**Nessuna capability è ⛔ per l'architettura** (Spring control plane + engine Python + console
React + PostgreSQL/Flyway): tutte si innestano come domini nuovi o estensioni additive. Le sole ⛔
sono vincoli **esterni** (hardware per DwarfStar4, policy di framing dei servizi web).

## 2. Che cosa era già corretto e resta

- **La spina dorsale**: control plane Spring con stato canonico, engine Python senza stato,
  contratto versionato fra i due, console con tipi generati. La visione aggiunge domini, non
  cambia questa forma. ADR-001, 015, 016, 017 restano valide.
- **La disciplina di dominio**: vocabolari chiusi con `CHECK`, `If-Match` su ogni scrittura di
  una risorsa esistente, problem details uniformi, Flyway padrone dello schema. I domini nuovi le
  adottano.
- **HITL già presente come principio**: «una run non completa il task» è la prima pietra dei
  livelli di autonomia.
- **Il costo cloud spento per costruzione** (ADR-015 §5): coerente con «non voglio dipendere dalle
  API di Claude Code o Codex».

## 3. Inventario reale della macchina (2026-09-24)

### 3.1. Software richiesti dalla direttiva

| Software | Rilevato | Come si lancia (dato reale) |
|---|---|---|
| Antigravity (agent manager 2.0.11) | ✅ | Start menu `Google.Antigravity` |
| Antigravity IDE 2.5.5 | ✅ | `Google.AntigravityIDE`; CLI `antigravity-ide.cmd <cartella>` |
| Apache NetBeans 23 | ✅ | `C:\Program Files\NetBeans-23\netbeans\bin\netbeans64.exe` |
| Devin 3.8.20 | ✅ | `%LOCALAPPDATA%\Programs\Devin\Devin.exe` |
| IntelliJ IDEA 2026.1 (+ Junie, plugin) | ✅ | `idea64.exe <cartella>` |
| Kimi 3.1.7 | ✅ | `com.moonshot.kimichat` |
| Notepad++ 8.9.7 | ✅ | `notepad++.exe` |
| PyCharm 2025.1.3.1 | ✅ | `pycharm64.exe <cartella>` |
| Visual Studio 2022 Community 17.14 | ✅ | `devenv.exe` |
| Visual Studio Code 1.136 + Continue.dev 2.0.0 | ✅ | `code.cmd <cartella>`; estensione `continue.continue` presente |
| Warp | ✅ | `dev.warp.Warp` |
| Verdent 2.12.3 | ✅ | `ai.verdent.deck` |
| WebStorm 2025.3.4 **e** 2026.2 | ✅ | `webstorm64.exe <cartella>` (si registra la 2026.2) |
| MySQL Workbench 8.0.43 (+ MySQL Server 8.0 su 3306) | ✅ | `MySQLWorkbench.exe` |
| Oracle VirtualBox 7.2.14 | ✅ | `VirtualBox.exe` |
| Postman 11.99 | ✅ | `com.squirrel.Postman.Postman` |
| draw.io 31.4.5 (Store) | ✅ | `draw.io.draw.ioDiagrams_…!draw.io.draw.ioDiagrams` |
| ChatGPT Classic (Store) | ✅ | `OpenAI.ChatGPT-Desktop_…!ChatGPT` |
| ChatGPT / Codex (Store, nome in Start: «ChatGPT») | ✅ | `OpenAI.Codex_…!App` |
| Claude (desktop, Store) | ✅ | `Claude_…!Claude` |
| Claude Code CLI 2.1.263 | ✅ | `claude.exe` (`claude "<prompt>"`, `-p` headless) |
| OpenCode CLI 1.18.18 | ✅ | `opencode [cartella] --prompt`, `opencode run`, `opencode serve`, `opencode web` |
| Open WebUI desktop 0.0.20 | ✅ | `com.openwebui.desktop`; server locale **127.0.0.1:8080** |
| Ollama 0.33.3 | ✅ | `127.0.0.1:11434` |
| 3D Omniverse (applicazione dell'operatore) | ✅ | `C:\Users\gabry\3D Omniverse\start.ps1`, UI su `localhost:8800` |
| Windows Terminal | ✅ | `wt.exe` |
| Blender 5.0.1 (non richiesto, rilevato) | ✅ | utile al dominio 3D |

Codex CLI **non** è nel `PATH`: Codex è disponibile come applicazione desktop.

### 3.2. Modelli locali (Ollama 0.33.3)

| Modello | Dimensione | Famiglia | Data | Ruolo attuale | Valutazione |
|---|---|---|---|---|---|
| `llama3.2:3b` | 2.0 GB | Llama 3.2 | 2024 | veloce/economico, usato negli smoke test | **superato**: sostituto candidato `qwen3.5:4b` (3.4 GB, 256K contesto, tools, vision, thinking) |
| `deepseek-coder-v2:16b` | 8.9 GB | DeepSeek Coder V2 | 2024 | codice | **superato**, niente tool calling: candidato `qwen3-coder:30b` (19 GB, MoE 3B attivi) — decisione umana per disco/RAM; nel frattempo `qwen3.5:9b` copre il ruolo |
| `qwen3.5:9b` | 6.6 GB | Qwen 3.5 | 2026 | generale / ragionamento / pianificazione | attuale, resta |
| `gemma4:e4b` | 9.6 GB | Gemma 4 | 2026 | multimodale (immagini/reference) | attuale, resta |

Hardware: Intel Core Ultra 7 258V, **Intel Arc 140V (memoria condivisa)**, 31.5 GB RAM, nessuna
GPU CUDA/ROCm/Metal. È il vincolo che decide DwarfStar4 (§5.3) e la taglia massima utile.

## 4. Applicazioni web: che cosa si può incorporare davvero (misurato)

| Servizio | Header rilevato | Iframe | Strategia realistica |
|---|---|---|---|
| Gmail | pagine Google con policy anti-framing (redirect a login che rifiuta il framing) | ❌ | Apertura controllata in finestra dedicata; API Gmail con OAuth (decisione umana) |
| Google Drive | idem | ❌ | idem; API Drive con OAuth |
| ClickUp | `frame-ancestors 'self' https://clickup.com …` | ❌ | Apertura controllata; API ClickUp con token personale (decisione umana) |
| GitHub | `X-Frame-Options: deny`, `frame-ancestors 'none'` | ❌ | Collegamento (icona); `gh`/API per lo stato dei repository |
| GitLab | `SAMEORIGIN` | ❌ | Collegamento |
| Supabase | `DENY`, `frame-ancestors 'none'` | ❌ | Collegamento |
| Vercel | `DENY` | ❌ | Collegamento |
| ChatGPT web | `SAMEORIGIN` | ❌ | App desktop (ChatGPT Classic) dal launcher |
| Gemini | CSP restrittiva | ❌ | Collegamento; le immagini tornano nel progetto tramite `references/` |
| OpenRouter | `SAMEORIGIN` | ❌ | Provider via API nell'engine |
| draw.io web | `frame-ancestors 'self' …` | ❌ | App desktop dal launcher |
| **Open WebUI (locale)** | servizio dell'operatore su loopback | ✅ | **Iframe**, stessa *site* della console (`localhost`) |
| **3D Omniverse (locale)** | applicazione dell'operatore su loopback | ✅ | **Iframe** o apertura |

**Conclusione onesta**: nessun servizio web di terze parti fra quelli richiesti può stare in un
iframe della console. Le sole vie per «dentro l'interfaccia» sono:

1. **Apertura controllata** (finestra nominata e riutilizzata, un clic dal Software Hub) — subito;
2. **Shell desktop** (Electron/Tauri con `WebView`, dove le policy di framing non si applicano
   perché il servizio è una pagina di primo livello) — richiede una decisione umana (§6);
3. **API** (Gmail/Drive via OAuth, ClickUp via token) per mostrare *dati* nella console — richiede
   credenziali, quindi l'operatore.

## 5. Vincoli scoperti

### 5.1. Porta 8080: Open WebUI contro il backend
La configurazione dell'operatore (`%APPDATA%\open-webui\config.json`) avvia il server di Open WebUI
su `127.0.0.1:8080`, la porta di default del backend di AI Company OS. Oggi non si scontrano solo
perché non sono mai stati accesi insieme. Risolto nel codice (ADR-019 §5), non nella
configurazione dell'operatore.

### 5.2. Le quote reali esistono, ma solo in locale
Nessuna API pubblica espone le quote degli abbonamenti Claude/ChatGPT. Esistono però dati **reali
e locali**: le sessioni di Codex (`~/.codex/sessions/**/*.jsonl`) registrano `rate_limits` con la
percentuale usata, la finestra (5 h, 7 giorni) e `resets_at`; i log di Claude Code
(`~/.claude/projects/**/*.jsonl`) registrano i token per messaggio. La scheda consumi si fonda su
questi, e dichiara quando un dato è configurato a mano invece che letto.

### 5.3. DwarfStar4
Identificato senza ambiguità: **DwarfStar 4 (`ds4`)** di Salvatore Sanfilippo (antirez),
<https://github.com/antirez/ds4>, licenza MIT — un motore di inferenza nativo dedicato a DeepSeek V4
Flash/PRO (e GLM 5.x, Qwen 3.8 Flash), con server HTTP locale (`127.0.0.1:8000`). Backend
supportati: **Metal, CUDA, ROCm**; requisito dichiarato **≥ 96 GB di RAM** (sotto, streaming da
SSD). Questa macchina ha una GPU Intel Arc a memoria condivisa e 31.5 GB: **non è installabile qui
in modo utile, e non è stato installato**. Ruolo assegnato: *provider locale ad alte prestazioni
opzionale*, raggiungibile tramite il provider OpenAI-compatibile dell'engine se in futuro gira su
un'altra macchina della rete. Registrato nel catalogo come `INCOMPATIBLE_HARDWARE`.

## 6. Decisioni che la gap analysis produce

| Decisione | Dove |
|---|---|
| Otto concetti distinti (Agent, Subagent, Model, Provider, Software, Tool, Skill, Knowledge, MCP) e le loro relazioni | ADR-018 |
| Software Hub: catalogo, rilevamento, launcher ad allowlist, politica di embedding, porta del backend | ADR-019 |
| Il contesto vive nei file del progetto; il DB ne è l'indice; `plan.json` come formato canonico del piano | ADR-020 |
| Orchestrazione: decisione di dispatch, prompt compatto, execution target esterni senza API finte | ADR-021 |
| Human-in-the-Loop progressivo e Learning with Agent | ADR-022 |

La roadmap che ne discende è `.company-os/ROADMAP_V2.md`.
