# FINAL HANDOFF — Riallineamento V2, PHASE 8 → PHASE 14

> **Da accettare.** Scritto per la Human Final Review richiesta dalla direttiva del 2026-09-24
> («riallineamento architetturale prima del merge»). Nessun agente esegue il merge in `master`
> (charter §8): è il gesto con cui un umano accetta il lavoro.
>
> Handoff precedenti: `docs/handoff/FINAL_HANDOFF_PHASE_3_7.md` (PHASE 3–7),
> `docs/handoff/FINAL_HANDOFF_PHASE_2.md`, `docs/handoff/FINAL_HANDOFF_PHASE_1.md`.

- **`master`**: `d166870`, **intoccato**. Nessun force-push, nessuna history riscritta.
- **Da rivedere**: `autonomous/phase-8-ecosystem-realignment` (10 commit sopra
  `autonomous/phase-7-operator-console` = `670b6bf`, che resta intatto). La catena è lineare:
  `master → phase-3 → … → phase-7 → phase-8-ecosystem-realignment`. Un solo fast-forward integra
  PHASE 3–14.
- **Suite**: **417** Java (416 verdi + 1 saltato: link simbolici su Windows senza privilegi) + **56** AI
  Engine + **18** console = **490 verdi**. **CI verde** su `1835162` e su ogni commit precedente del blocco.
- **Stream**: `V11` → **`V19`**, tutte additive. **Live DB `aicompany`: `V11`** (migrato
  dall'operatore durante la review di PHASE 3–7); al primo avvio del nuovo backend riceverà V12–V19.
- **MU-OS raggiunto** (fine PHASE 11) e **verificato dal vivo** sulla macchina dell'operatore.

---

## 1. Visione / Gap

Documento completo: [`docs/realignment/GAP_ANALYSIS_V2.md`](docs/realignment/GAP_ANALYSIS_V2.md).

PHASE 3–7 avevano costruito un **task tracker con esecuzione LLM**: sicurezza, ciclo di vita dei
task, AI Engine, run, console. La visione chiede un **ecosistema operativo**: progetti con un
workflow Idea → Master Prompt → Piano → Fasi → Task → Esecuzione → Review, un orchestratore che
sceglie agente/modello/software/contesto, un hub dei programmi della macchina, agenti con harness,
un secondo cervello. La gap analysis ha misurato — non supposto — che cosa c'era, che cosa mancava
e che cosa era impossibile:

| Area | Prima (PHASE 7) | Ora (PHASE 14) |
|---|---|---|
| Progetto | nome + descrizione | tipo, stack, cartella di lavoro, livello di autonomia, stato del piano, documenti |
| Piano | assente | `MASTER_PROMPT.md` → `.aicos/plan.json` → `IMPLEMENTATION_PLAN.md`, `PHASE_X.md`, `TASK-XXX.md` |
| Orchestratore | assente (sostituito in PHASE 6 da un router lessicale) | decisione motivata, handoff, review |
| Human-in-the-loop | approvazione implicita | gate per fase, 4 livelli di autonomia |
| Software | assente | 38 voci a catalogo, 25 rilevate installate, lancio sicuro |
| Modelli/provider/quote | un modello fisso | catalogo, ruoli, sostituzioni, consumi reali |
| Agenti | nome, ruolo, modello | prompt engineering, sottoagenti, harness (skill, knowledge, MCP, tool, framework, template) |
| Second Brain, Daily Work, Knowledge/Mockup Hub | assenti | presenti |

**Ciò che era già giusto e resta** (§2 della gap analysis): il control plane come unica fonte di
verità, ADR-009 (precondizioni), il contratto OpenAPI tenuto dal test, l'engine come gateway, la
sicurezza di PHASE 3. Nulla di PHASE 3–7 è stato riscritto; tutto è stato esteso.

## 2. Roadmap

Documento: [`.company-os/ROADMAP_V2.md`](.company-os/ROADMAP_V2.md). PHASE 1–7 non rinumerate.

| Fase | Contenuto | Stato |
|---|---|---|
| 8 | Cataloghi dell'ecosistema: Software Hub, provider/modelli, consumi, OpenRouter, nuova shell | ✅ |
| 9 | Workspace di progetto: profilo, cartella governata, documenti sicuri | ✅ |
| 10 | Pianificazione: master prompt → piano a stadi, import da agente esterno, gate HITL | ✅ |
| 11 | Esecuzione: decisione, handoff, review, contesto dai file nelle run | ✅ → **MU-OS** |
| 12 | Ecosistema agenti: prompt/harness engineering, sottoagenti, Knowledge Hub, Mockup Hub | ✅ |
| 13 | Daily Work | ✅ |
| 14 | Second Brain (grafo animato) | ✅ |
| 15 | Terminale integrato (PTY nel browser: PowerShell / claude / opencode) | da fare |
| 16 | Graph engineering (dependency graph, code graph dei progetti) | da fare |
| 17 | Template Hub: provider esterni di mockup e template | da fare |
| 18 | Integrazioni esterne (Gmail, Drive, ClickUp) — **richiede decisioni umane** | da fare |
| 19 | Governo dei costi | da fare |
| 20 | 3D (Omniverse come dominio di progetto) | da fare |
| 21 | Release | da fare |

**Minimum Usable AI Company OS** (definito in ROADMAP_V2 §2, raggiunto): creare un progetto,
scriverne il Master Prompt, generare il piano, avere fasi e task con i loro documenti, assegnare
agenti, navigare il contesto, lanciare i programmi dal Software Hub, far decidere l'orchestratore,
approvare, eseguire con un agente reale, rivedere, vedere lo stato del progetto.

## 3. Software Hub

ADR-019. Vista **Strumenti → Software**.

- **Catalogo** (`catalog/software.json`, 38 voci) con AppID/percorsi reali misurati sulla macchina.
  Tutti i programmi della direttiva sono presenti; GitHub, GitLab, Supabase, Vercel come
  collegamenti con icona.
- **Rilevamento al momento della lettura**, mai memorizzato: menu Start (`Get-StartApps`), percorsi
  con variabili d'ambiente e wildcard (versioni in cartella), `PATH`, sonda HTTP per i servizi
  (Ollama, Open WebUI, Omniverse). Stati: `INSTALLED`, `RUNNING`, `STOPPED`, `NOT_FOUND`,
  `INCOMPATIBLE_HARDWARE`. **Misurato**: 25 installati, Ollama in esecuzione, DwarfStar4
  incompatibile.
- **Icone reali** estratte dagli eseguibili e dai manifest dello Store.
- **Lancio** con sei invarianti (I1–I6): solo voci del catalogo; l'unico argomento variabile è la
  cartella del progetto risolta dal database; nessuna shell; CLI dentro Windows Terminal; i servizi
  web non si «lanciano», si aprono; fuori da Windows il lancio è dichiarato non supportato.
- **Aprire un progetto in un IDE**: dal dettaglio del progetto, scheda *Strumenti*.

## 4. Agent ecosystem

ADR-018 (separazione dei concetti), ADR-023 (harness). Vista **Lavoro → Agenti**.

- **Concetti separati** nel dominio e nel database: *Agent* (chi lavora), *Model* (con che cosa
  pensa), *Provider* (chi serve il modello), *Software* (dove si lavora), *Tool/Skill/Knowledge/MCP/
  Framework/Template provider* (risorse dell'harness), *Subagent* (un agente con `parent_id`).
- **Prompt engineering** per agente: obiettivo, istruzioni di sistema, stile di output, vincoli.
- **Harness engineering**: risorse collegate all'agente, software preferito; tutto finisce nel
  contesto della run (`RunContext`).
- **Sottoagenti** gerarchici; **template di agenti** (`agent-templates.json`) installabili, mai
  sovrascritti.
- **Knowledge Hub** (risorse per tipo, collegabili ad agenti e progetti) e **Mockup Hub**
  (reference del progetto, template).
- **Modelli locali** (regola della direttiva applicata alla lettera): inventario → ruolo →
  sostituto → verifica → migrazione → *solo dopo* valutare la rimozione. `qwen3.5:4b` è stato
  **verificato** (run reale, 226 s) e marcato `ACTIVE`; `llama3.2:3b` è `DEPRECATED` con
  `replacedBy`, **non rimosso** (gli agenti del seed lo usano ancora: TD-51). Nessun modello
  eliminato.

## 5. Orchestrator

ADR-021. Il Master Orchestrator ha due metà.

**Pianificazione** — da `MASTER_PROMPT.md` a `.aicos/plan.json` (formato canonico), per due vie
che convergono nello stesso validatore (`PlanDocument`) e nello stesso importatore:
1. **Engine locale**: generazione *a stadi* (prima le fasi, poi i task di ciascuna fase) con
   **decodifica vincolata da JSON Schema** su Ollama. Misurato con `qwen3.5:9b`: 12,8 minuti per
   5 fasi e 17 task. Avanzamento visibile nella console.
2. **Agente esterno** (Claude Code, Codex, Antigravity, OpenCode): la console prepara il prompt di
   handoff; l'agente scrive `plan.json`; «Importa» lo valida.

Un piano in bozza si rigenera finché nessun suo task è stato toccato; poi è bloccato.

**Esecuzione** — per ogni task: una **decisione motivata** (agente, modello, software, file di
contesto, prompt, destinazioni, ciascuno con il suo perché), poi:
- **run interna** con l'engine (locale o cloud), con contesto letto dai file del progetto;
- **handoff** verso uno strumento esterno: file `.aicos/handoffs/TASK-XXX-<destinazione>.md` scritto nel progetto
  e strumento aperto nella cartella. **Nessuna dipendenza dalle API di Claude Code o Codex**: si
  usano come programmi dell'operatore.
- **review** append-only (ACCEPTED / CHANGES_REQUESTED / REJECTED) che muove il task.

## 6. Project workflow

ADR-020, ADR-022. Vista **Lavoro → Progetti**.

1. **Nuovo progetto** — procedura in 4 passi: idea, tipo (catalogo `project-types.json`) e stack,
   cartella di lavoro, livello di autonomia.
2. **Master Prompt** — scheda dedicata, modificabile, salvata in `MASTER_PROMPT.md`.
3. **Piano** — genera o importa; fasi e task con i loro documenti.
4. **Approvazione** — per fase (livelli `GUIDED`, `SUPERVISED`) o del piano (`DELEGATED`,
   `FINAL_REVIEW`). Un task di una fase non approvata **non parte** (409, provato da test).
5. **Esecuzione** — pannello dell'orchestratore nel task.
6. **Review** — il task va a DONE solo con una review accettata.

Il livello di autonomia cambia davvero il comportamento: finisce in `AGENTS.md`, nel prompt di
sistema delle run e negli handoff (in `GUIDED` il modello ha chiesto all'operatore di prevedere
l'esito prima di procedere — osservato nello smoke reale).

**Il flusso di PHASE 3–7 resta intatto**: un task senza piano si crea, si assegna, si avvia e si
esegue esattamente come prima, e **il prompt inviato al modello è byte per byte quello di PHASE 6**
(due test lo fissano: task libero e task di progetto fuori piano).

## 7. Files

Cartella di lavoro di ogni progetto (default `~/AI-Company-Projects/<slug>`, confinata sotto la
radice configurata — W2/W3):

| File | Contenuto | Chi lo scrive |
|---|---|---|
| `AGENTS.md`, `CLAUDE.md` | regole per gli agenti esterni, livello di autonomia | AI Company OS |
| `MASTER_PROMPT.md` | visione, obiettivi, vincoli, stack | operatore |
| `CONTEXT_MAP.md` | mappa dei file di contesto | AI Company OS |
| `REFERENCES.md`, `references/` | immagini e riferimenti (anche da Gemini) | operatore |
| `.aicos/plan.json` | piano canonico | engine o agente esterno |
| `docs/IMPLEMENTATION_PLAN.md` | piano leggibile | AI Company OS |
| `docs/phases/PHASE_X.md` | obiettivo, task, criteri di uscita | AI Company OS |
| `tasks/TASK-XXX.md` | obiettivo, scope, criteri di accettazione, file, agente | AI Company OS |
| `.aicos/handoffs/TASK-XXX-<destinazione>.md` | prompt compatto per lo strumento esterno | AI Company OS |
| `.aicos/project.json` | manifest del progetto (profilo, mappa del contesto) | AI Company OS |

**Context engineering**: il contesto sta nei file; i prompt restano compatti e li nominano. La
console legge e modifica solo file dentro la cartella del progetto (niente `..`, niente link
simbolici che escono, niente percorsi assoluti).

## 8. Database

Otto migrazioni, **tutte additive** (nessuna colonna rimossa, nessun dato riscritto):

| Versione | Contenuto |
|---|---|
| V12 | `software` |
| V13 | `model_providers`, `llm_models`, `quota_plans` |
| V14 | profilo del progetto (`project_type`, `stack`, `workspace_path`, `autonomy_level` = GUIDED, `plan_status` = NONE) |
| V15 | `project_phases`, `tasks.phase_id/code/document_path`, `plan_runs` |
| V16 | `task_handoffs`, `task_reviews` |
| V17 | `plan_runs.progress` |
| V18 | profilo agenti, `parent_id`, `harness_resources`, `agent_resources`, `agent_software`, `project_resources` |
| V19 | `daily_items` |

I cataloghi (software, provider, modelli, quote, risorse, template di agenti) sono inseriti
all'avvio **solo se mancano**: le modifiche dell'operatore non vengono mai sovrascritte.

**Live DB**: al primo avvio del nuovo backend Flyway applicherà V12–V19 su `aicompany` (oggi V11).
Tutte le verifiche di questo blocco sono girate sul clone `aicompany_p8` (creato con
`CREATE DATABASE … TEMPLATE aicompany`), che contiene anche i progetti di prova «Smoke Rubrica».
Il clone si può eliminare quando si vuole; non è stato eliminato perché è un dato dell'operatore.

## 9. Test

| Suite | Prima | Ora |
|---|---|---|
| Control plane (Java) | 329 | **417** (di cui 1 saltato su Windows) |
| AI Engine (Python) | 51 | **56** |
| Console (Vitest) | 14 | **18** |

- **Regressione di PHASE 3–7**: tutte le suite precedenti verdi senza modifiche di comportamento;
  aggiornati solo i pin che contano versioni e colonne (migrazioni, forma di `TaskResponse` con i
  tre campi nuovi) e le etichette della console tradotte in italiano.
- **Coperture strutturali** estese: ogni nuovo percorso di scrittura è in `PreconditionCoverageTest`
  (lock + If-Match, o esenzione documentata per le creazioni); ogni nuovo slug d'errore in
  `ApiProblemCoverageTest`; contratto OpenAPI rigenerato e fissato.
- **Mutazioni** sulle guardie critiche:

  | # | Mutazione | Esito |
  |---|---|---|
  | M1 | il launcher accetta sintassi da terminale in un argomento CLI | rosso |
  | M2 | un percorso fuori dalla radice del workspace è accettato | rosso |
  | M3 | il gate HITL rimosso: un task di una fase non approvata parte | rosso |
  | M4 | un task di progetto fuori piano riceve istruzioni di autonomia (prompt di PHASE 6 alterato) | **sopravvissuto** → test aggiunto → rosso |
  | M5 | una bozza di piano già lavorata si può sostituire | **sopravvissuto** → test aggiunto → rosso |

- **Smoke reale** (2026-09-24, backend 8081, engine 8090, Ollama): il percorso MU-OS completo, §0
  dello stato. Verifica visiva di tutte le viste con istantanee.
- **CI**: tre job verdi su ogni commit del blocco.

## 10. Debiti

Registro: [`docs/DEBT_REGISTRY.md`](docs/DEBT_REGISTRY.md). Nuovi: **TD-41…TD-52**; prossimo
libero **TD-53**. I più rilevanti:

| Debito | Sintesi | Destinazione |
|---|---|---|
| TD-41 | stato della fase aggiornato da handoff/review senza lock di riga: in concorrenza, 500 invece di 409 | MINOR |
| TD-42 | terminale nel browser assente: si apre Windows Terminal | PHASE 15 |
| TD-43 | la generazione del piano non si annulla (~13 min col 9B) | MINOR |
| TD-44 | Gmail/Drive/ClickUp/ChatGPT web/Gemini non incorporabili | PHASE 18, **decisione umana** |
| TD-48 | collegare un MCP a un agente è metadato, non installazione | — |
| TD-50 | quote: nessuna API; dati locali; reset Claude da impostare | vincolo esterno |
| TD-51 | `llama3.2:3b` deprecato ma usato dagli agenti del seed | **decisione umana** |
| TD-52 | ruolo CODER su `deepseek-coder-v2` (2024) | **decisione umana** (19 GB) |

## 11. Applicazioni esterne

Politiche di framing **misurate** (gap analysis §4), non supposte.

| Applicazione | Stato | Come si usa oggi | Limite onesto |
|---|---|---|---|
| **Gmail** | collegamento | apertura in finestra dedicata dal Software Hub | anti-framing di Google; dati in console solo via API con OAuth (TD-44) |
| **Google Drive** | collegamento | idem | idem |
| **ClickUp** | collegamento | idem | `frame-ancestors`; API con token personale (TD-44) |
| **ChatGPT** | installato | ChatGPT Classic (app desktop) dal launcher | la versione web non si incorpora |
| **Claude Code** | installato (CLI 2.1.263) | destinazione di handoff: si apre nella cartella del progetto con il prompt compatto; consumi reali letti dai log locali | nessuna API usata, per scelta |
| **Codex** | installato (app desktop) | destinazione di handoff; finestre di quota reali (5 h / 7 giorni) dai log locali | CLI non nel `PATH`; quota misurata solo fino all'ultima sessione |
| **Antigravity** | installato (agent manager + IDE) | destinazione di handoff; l'IDE si apre nella cartella | — |
| **Gemini** | collegamento | le immagini generate entrano nel progetto via `references/` e Mockup Hub | non incorporabile |
| **OpenCode** | installato (CLI 1.18.18) | destinazione di handoff in Windows Terminal | API `opencode serve` non ancora usata (TD-49) |
| **Open WebUI** | installato, server su 8080 | **incorporato** in *Integrazioni* (stessa origine locale) quando è acceso | per questo il backend è passato a **8081** |
| **Ollama** | in esecuzione | provider locale dell'engine; inventario modelli | — |
| **OpenRouter** | pronto, spento | provider OpenAI-compatibile dell'engine | si accende solo con una chiave (decisione umana) |
| **DwarfStar4** | identificato, **non installato** | `antirez/ds4`: richiede Metal/CUDA/ROCm e ≥ 96 GB | incompatibile con Intel Arc 140V e 31,5 GB; a catalogo come `INCOMPATIBLE_HARDWARE` |
| **Omniverse (3D)** | installato | incorporato in *Integrazioni* (`localhost:8800`) quando è acceso | dominio 3D completo in PHASE 20 |

## 12. UI

Console in italiano, tema scuro, orientata alle nove tavole di riferimento.

- **Navigazione** a gruppi — *Lavoro*: Dashboard, Progetti, Task, Agenti, Daily Work, Second Brain;
  *Strumenti*: Software, Terminale, Integrazioni; *Risorse*: Knowledge, Mockup, Modelli, Consumi,
  Impostazioni. Ricerca globale.
- **Dashboard** con progetti attivi, task, consumi, software.
- **Progetto**: schede Panoramica, Master Prompt, Piano (fasi, approvazioni, avanzamento della
  generazione), Documenti, Reference, Strumenti.
- **Task**: pannello dell'orchestratore (decisione motivata, run, handoff, review).
- **Agenti**: studio con prompt engineering, harness, sottoagenti, template.
- **Second Brain**: grafo animato (d3-force) di progetti, fasi, task, agenti, modelli, software,
  risorse; selezione, ricerca, trascinamento.
- **Consumi**: finestre di quota con reset giornaliero/settimanale, token reali di Claude Code,
  percentuali reali di Codex; ciò che è configurato a mano è dichiarato come tale.

## 13. Avvio

```powershell
.\scripts\start-dev.ps1
```

Poi <http://localhost:5173>, token operatore `dev-operator-token-change-me` in sviluppo. Il
control plane ascolta su **8081** (8080 è di Open WebUI). Il percorso completo dalla console, passo
per passo: [`docs/RUNNING.md` §0b](docs/RUNNING.md). Per la generazione locale del piano serve
Ollama con `qwen3.5:9b` (presente); per le run veloci `qwen3.5:4b` (presente).

## 14. HUMAN ACTION REQUIRED

Nessuna di queste azioni blocca l'uso del sistema. Sono le decisioni che la direttiva riserva
all'operatore.

**1. Accettare il blocco e integrarlo in `master`.**
- *Problema*: PHASE 3–14 vivono su `autonomous/phase-8-ecosystem-realignment`; `master` è a PHASE 2.
- *Perché serve il consenso*: il merge è l'accettazione (charter §8) e la direttiva lo riserva a te.
- *Opzioni*: (a) fast-forward di `master` a `1835162`+ (tutto PHASE 3–14); (b) merge solo di
  `autonomous/phase-7-operator-console` e revisione separata di 8–14; (c) richiedere modifiche.
- *Conseguenze*: (a) un solo passo, history lineare; (b) due review, stesso risultato finale.
- *Default consigliato*: **(a)**, dopo aver provato il percorso di `docs/RUNNING.md` §0b.

**2. Primo avvio sul DB live.**
- *Problema*: `aicompany` è a V11; il nuovo backend applicherà V12–V19.
- *Perché*: tocca i tuoi dati (solo in aggiunta).
- *Opzioni*: avviare così; oppure fare prima un dump (`pg_dump`) per sicurezza.
- *Default consigliato*: **dump, poi avvio**. Le migrazioni sono additive e provate su un clone.

**3. Integrazioni Gmail, Drive, ClickUp «dentro» l'interfaccia (TD-44, PHASE 18).**
- *Problema*: i servizi rifiutano l'iframe (misurato).
- *Perché*: richiede credenziali OAuth/token e una scelta di prodotto.
- *Opzioni*: (a) restare con l'apertura controllata; (b) shell desktop (Tauri/Electron) con
  WebView; (c) API per mostrare i dati nella console (serve creare le credenziali Google e un token
  ClickUp).
- *Conseguenze*: (b) aggiunge un'applicazione da installare e mantenere; (c) dati veri in console,
  gestione di credenziali.
- *Default consigliato*: **(a) ora, (c) in PHASE 18**.

**4. Modelli locali (TD-51, TD-52).**
- *Problema*: gli agenti del seed usano `llama3.2:3b` (deprecato); il ruolo CODER usa un modello
  del 2024.
- *Perché*: modifica i tuoi agenti; `qwen3-coder:30b` sono 19 GB di disco e molta RAM.
- *Opzioni*: migrare gli agenti a `qwen3.5:4b` (verificato) dalla vista Agenti; scaricare o no
  `qwen3-coder:30b`; rimuovere `llama3.2:3b` **solo dopo** la migrazione.
- *Default consigliato*: **migrare gli agenti; non scaricare il 30B** (la macchina ha 31,5 GB
  condivisi con la GPU); tenere `llama3.2:3b` finché non ti fidi del sostituto.

**5. Chiavi cloud (Anthropic, OpenRouter).**
- *Perché*: credenziali e costi.
- *Default consigliato*: **nessuna chiave per ora**; il sistema lavora in locale e con i tuoi
  strumenti tramite handoff. Da riconsiderare dopo TD-40.

**6. Ancora del reset settimanale di Claude** (vista *Consumi*): un giorno e un'ora da impostare,
perché nessuna fonte locale li espone. Default: lasciarlo vuoto finché non ti serve.
