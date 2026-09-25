# FINAL HANDOFF — Consolidamento, PHASE 15 → PHASE 27

> **Da accettare.** Scritto per la Human Final Review richiesta dalla direttiva del 2026-09-25 (UX,
> orchestrazione, configurabilità, agenti, sicurezza, completamento della roadmap). Nessun agente
> esegue il merge in `master` senza la tua autorizzazione (charter §8).
>
> Handoff precedenti: `docs/handoff/FINAL_HANDOFF_PHASE_8_14.md`, `…_PHASE_3_7.md`, `…_PHASE_2.md`,
> `…_PHASE_1.md`.

- **`master`**: `e9c928c` (PHASE 3–14, integrate il 2026-09-25 con un fast-forward da te autorizzato).
- **Da rivedere**: `autonomous/phase-15-hardening`, 11 commit lineari sopra `master`, pushato. Un
  solo fast-forward integra tutto.
- **Suite**: **470** Java (469 verdi + 1 saltato su Windows) + **58** AI Engine + **27** console.
  CI verde su ogni commit del blocco fino all'ultimo verificato.
- **Stream**: `V19` → **`V24`** (tutte additive); seed di sviluppo `V1` → **`V2`**.
- **Smoke dal vivo**: **31/31** sul clone `aicompany_p8`, con Ollama e DeepSeek reali.

---

## 1. Fasi completate

| Fase | Contenuto | ADR |
|---|---|---|
| 15 | Sicurezza: utenti, account Admin, BCrypt, sessioni revocabili, ruoli, registro | 024 |
| 16 | Legame Agente → Modello → Provider → Execution Target; iniziale vs modificato; DeepSeek | 025 |
| 17 | Handoff senza API OpenAI/Anthropic, per ogni task | 025 |
| 18 | UX di assegnazione COSA → A CHI → CON → ATTRAVERSO | 025 |
| 19 | Skill e knowledge come file; Knowledge Hub riparato | 026 |
| 20 | Eliminazione reale di progetti e task | 027 |
| 21 | Avvio coordinato: Ollama, Open WebUI, 3D Omniverse | 028 |
| 22 | Terminale integrato (PowerShell, Claude Code, OpenCode) | 029 |
| 23 | Grafo del codice di un progetto | 030 |
| 24 | Governo dei costi: prezzi, budget, blocco (chiude TD-40) | 031 |
| 25 | Reference dalla console, dipartimento 3D | 032 |
| 26 | Gmail, Drive, ClickUp con credenziali | **non iniziata: decisione tua** |
| 27 | Verifica end-to-end dal vivo e correzioni emerse | — |

La numerazione è in `.company-os/ROADMAP_V2.md` §5 (le vecchie fasi 15–21, mai iniziate, sono
confluite qui).

## 2. Funzionalità aggiunte e modifiche UX

- **Accesso**: login con utente e password; menu utente (ruolo, *Account e sicurezza*, *Esci* che
  chiude la sessione anche sul server); banner se la password è quella iniziale generata.
- **Assegnazione** (§1 della direttiva): nel cassetto della task un riquadro **COSA** (task, codice,
  descrizione, progetto, fase, priorità) e la catena **Agente → Modello → Provider → Execution
  Target**; «Assegna agente» apre card con ruolo, sottoagente di, descrizione, catena e motivo del
  routing, e prima della conferma la frase «Stai assegnando «…» a X (ruolo), che userà MODELLO di
  PROVIDER attraverso TARGET». Niente select anonime né ID.
- **Consegna a uno strumento**: card per target (consigliato quello dell'agente), finestra di conferma
  con lo stesso riepilogo, poi cartella, file scritti, prompt compatto e completo da copiare.
- **Agenti**: pagina rifatta (vedi §4).
- **Knowledge Hub**: funziona (il crash era un `children: null` dei template) e ogni skill si apre come
  file.
- **Eliminazione**: pulsante «Elimina» solo per gli admin, con anteprima di cosa se ne va e il nome da
  digitare; «Archivia» resta il gesto reversibile.
- **Terminale** nella pagina, **Codice** nel progetto, **Costi** in Consumi, **Ecosistema all'avvio** in
  Impostazioni e Integrazioni, **Reference** con carica/incolla.
- Etichette dei campi collegate agli input; un errore in una vista non spegne più tutta la console
  (error boundary per vista).

## 3. Agent → Model → Provider → Execution Target

Quattro concetti in quattro posti: il modello è dell'agente, il **provider è sempre quello del
modello** (mai scritto sull'agente), l'execution target è dell'agente. Combinazioni impossibili
rifiutate (`400 binding-invalid`); una run nell'AI Engine di un agente legato a un'app è rifiutata
(`409 agent-works-elsewhere`).

| Execution target | Consegna | Provider dei modelli |
|---|---|---|
| AI Engine | run interna | Ollama, OpenRouter, Anthropic API, echo |
| Claude Code | CLI nel terminale con il prompt | abbonamento Claude |
| Codex | app, prompt negli appunti | abbonamento ChatGPT |
| OpenCode | CLI con `--prompt` | Ollama, OpenRouter, abbonamenti |
| Antigravity IDE / agent manager | IDE sulla cartella / app | account Google |
| VS Code + Continue | IDE, `.continue/rules/` | Ollama, OpenRouter |
| IntelliJ + Junie | IDE, `.junie/guidelines.md` | JetBrains AI |
| Devin, Kimi, Verdent, ChatGPT | app, prompt negli appunti | i rispettivi |
| Gemini, 3D Omniverse | web, prompt negli appunti | — |
| Manuale | nessuno strumento | — |

**Mappa attuale dei tuoi agenti del seed** (dopo il primo avvio sul DB live):

| Agente | Modello | Provider | Execution target |
|---|---|---|---|
| Code Architect | Qwen 3.5 9B | Ollama (locale) | AI Engine |
| Frontend Developer | DeepSeek Coder V2 16B | Ollama (locale) | AI Engine |
| Database Specialist | DeepSeek Coder V2 16B | Ollama (locale) | AI Engine |

Nuovi template: **Claude Code Engineer** (abbonamento Claude → Claude Code) e **Codex Engineer**
(abbonamento ChatGPT → Codex); **3D Artist** lavora in 3D Omniverse.

## 4. Configurazione di agenti e sottoagenti

Editor in cinque schede: **Identità** (nome, ruolo, specializzazione, descrizione, capability,
sottoagente di, stato), **Modello ed esecuzione** (card dei target con disponibilità, modelli filtrati
per compatibilità, anteprima dal vivo della catena), **Prompt e direttive** (system/role prompt,
responsabilità, direttive, limiti, output, politica di contesto, dominio), **Skill e strumenti**
(skill, knowledge, tool, MCP, framework), **Software**.

**Iniziale o modificato**: ogni agente ha un'origine (`Seed iniziale`, `Da template`, `Creato da te`) e
una baseline. Accanto a ogni campo compare «iniziale» o «modificato» (con il valore iniziale nel
tooltip), in testa l'elenco delle modifiche con autore e data, e «Ripristina iniziale». Gli agenti da
template installati prima di PHASE 16 vengono riconosciuti all'avvio, con la configurazione di oggi
come baseline (la loro storia precedente non è ricostruibile).

## 5. DeepSeek, Ollama e modelli disponibili

Verificati con Ollama 0.33.3 il 2026-09-25:

| Modello | Parametri | Quant. | Ruolo | Stato |
|---|---|---|---|---|
| `qwen3.5:9b` | 9.7B | Q4_K_M | pianificazione, ragionamento | attivo |
| `qwen3.5:4b` | 4.7B | Q4_K_M | veloce | attivo (sostituto verificato di llama3.2) |
| `deepseek-coder-v2:16b` | 15.7B | Q4_0 | codice, SQL | **attivo, associato** a Frontend Developer e Database Specialist |
| `gemma4:e4b` | 8.0B | Q4_K_M | visione, reference | attivo |
| `llama3.2:3b` | 3.2B | Q4_K_M | — | superato, **non usato da nessun agente**, non rimosso |

DeepSeek dal vivo: SQL corretto in 16,6 s a freddo, run complete in 45 s e 80 s. Non ha tool calling:
va bene per generare codice, non per lavorare da agente con strumenti — per quello c'è il legame con
Claude Code o Codex. Correzione a un debito precedente: sul tuo DB gli agenti del seed **non avevano
nessun modello** (usavano `echo`), non `llama3.2:3b`.

## 6. Claude Code, Codex e IDE agentici senza API OpenAI/Anthropic

Per ogni task con un agente, anche fuori da un piano e anche senza progetto, «Prepara e apri»:

1. cartella: quella del progetto (creata se manca) o `…/_inbox/task-<id>`;
2. documento della task (`tasks/TASK-<id>.md` o quello del piano), mai sovrascritto;
3. regole dello strumento (`.junie/guidelines.md`, `.continue/rules/aicos.md`; `CLAUDE.md` e
   `AGENTS.md` del progetto), mai sovrascrivendo file tuoi;
4. pacchetto `.aicos/handoffs/<TASK>-<target>.md`: cartella, prompt, ruolo e istruzioni dell'agente,
   modello e provider, skill con i percorsi dei loro file, contesto da leggere, regole HITL, esito;
5. prompt compatto (e completo, per le app che non leggono file) negli appunti;
6. apertura: terminale con `claude "<prompt>"` / `opencode --prompt`, IDE sulla cartella, app, sito.

Cosa **non** c'è, di proposito: nessuna automazione dentro le app (non si preme «invio» in Codex) e
l'esito non torna da solo (TD-55): lo leggi nel documento della task e fai la review. OpenRouter resta
un provider API opzionale e separato, spento senza chiave.

## 7. Skill e modifica manuale

Ogni skill è `%USERPROFILE%\.aicos\library\skills\<chiave>\SKILL.md`, ogni knowledge
`…\knowledge\<chiave>.md`: frontmatter (`key`, `name`, `kind`, `description`, `tags`, `source`) e
istruzioni in Markdown, la forma dei `SKILL.md` di Claude Code. Dalla console: vedi percorso e
contenuto, crea il file per una skill di catalogo, modifica e salva, apri in VS Code, crea una skill
nuova, importa da URL (solo https, host pubblici, niente redirect, max 256 KB, link GitHub «blob»
convertiti). A mano: modifica il file, poi «Rileggi cartella». Versionabile con git (o sposta la
libreria con `aicos.library.root`). Le skill arrivano ai modelli: nel prompt delle run (verificato dal
vivo) e come percorsi nei pacchetti di handoff.

## 8. Eliminazione di progetti e task

Solo admin, con il **nome digitato**, il tag della riga (If-Match) e mai durante un'esecuzione. Una
task porta via esecuzioni, handoff e review; le voci del Daily Work restano come voci personali. Per un
progetto scegli sempre se le task **restano** (staccate) o **vengono eliminate**: nessun default. I
file su disco non vengono mai toccati. Ogni eliminazione finisce nel registro di sicurezza.
Archiviare resta il gesto reversibile dell'operatore.

## 9. Avvio di Open WebUI e 3D Omniverse

All'avvio del control plane, in background e in ordine: Ollama → Open WebUI → 3D Omniverse. Per
ciascuno: se risponde non si avvia (**nessun doppione**); se l'app è già aperta non si riapre; se no si
avvia col comando del catalogo e si attende la sua salute fino al timeout. Esito registrato e visibile
(Impostazioni, Integrazioni); un errore non blocca gli altri né il sistema. Comandi, ordine, attese e
autoavvio si configurano (admin), con variabili d'ambiente e **senza percorsi della macchina nel
codice**. Dal vivo: Ollama e Omniverse OK; l'app desktop di **Open WebUI** si apre ma accende il suo
server solo dal suo interno (TD-56), e il sistema lo dice.

## 10. Sicurezza implementata

| Requisito | Come |
|---|---|
| Autenticazione | login utente/password → token di sessione (256 bit, conservato solo come SHA-256) |
| Account Admin | creato al primo avvio dalla configurazione locale |
| Password | BCrypt costo 12; min 12 caratteri, max 72 byte, niente nome utente, niente password ovvie |
| Sessioni | scadenza 8 h di inattività / 72 h assolute; revoca a logout, cambio password, disattivazione, reset |
| Tentativi | blocco dopo 5 errori (15 min); 20 tentativi/min per indirizzo; stessa risposta per utente ignoto e password errata |
| Autorizzazione | `OPERATOR` e `ADMIN`; solo admin: utenti, registro, eliminazioni, terminale, catalogo software, ecosistema, prezzi e budget |
| Endpoint | tutto `/api/**` autenticato, tranne login e health; terminale con ticket monouso |
| Input | Bean Validation, file validati per contenuto, percorsi confinati, SSRF bloccato negli import |
| Errori | problem detail senza dettagli interni; 500 con messaggio fisso |
| Segreti | fuori dal repository (`%USERPROFILE%\.aicos\local.env`, ACL solo utente); nessun token di default |
| CORS/CSRF | origini dichiarate; token in header e mai in cookie, quindi niente superficie CSRF |
| Header | CSP `default-src 'none'`, `Referrer-Policy: no-referrer`, nosniff, no-store sul login |
| Log di sicurezza | tabella + logger `aicos.security`: accessi, blocchi, password, utenti, accessi negati, eliminazioni, processi |
| Test | `AuthApiTest`, `LoginThrottleTest`, contratti di autenticazione, test per ruolo in ogni area admin |

Trovato e corretto nello smoke: l'utente di default di Spring Boot stampava una password generata nel
log (ora escluso e fissato da un test); `/v3/api-docs` spento in produzione.

## 11. Credenziali Admin

- **Utente**: `admin`
- **Password iniziale**: generata a caso da `scripts/init-local-secrets.ps1`; è nel report in chat
  e **di proposito non in questo file** (che è in git). Rivederla:
  `.\scripts\init-local-secrets.ps1 -Show`.
- **Dove è configurata**: `%USERPROFILE%\.aicos\local.env` (`AICOS_ADMIN_PASSWORD`), fuori dal
  repository, leggibile solo dal tuo account.
- **Quando vale**: al primo avvio su un database senza admin. Il tuo DB live `aicompany` non ha
  ancora admin: lo riceverà con questa password al primo `start-dev.ps1`.
- **Come cambiarla**: console → menu utente → *Account e sicurezza* → *Cambia password* (le altre
  sessioni si chiudono). Se la dimentichi: nuova password in `AICOS_ADMIN_PASSWORD` nel file, avvio con
  `AICOS_ADMIN_RESET=true`, poi togli la variabile.

## 12. File e documenti principali

- ADR **024–032** (`docs/adr/`), `.company-os/ROADMAP_V2.md` §5, `.company-os/PROJECT_STATE.md`,
  `docs/RUNNING.md` (§0b percorso completo, accesso e credenziali), `docs/DEBT_REGISTRY.md`, `README.md`.
- Backend nuovi pacchetti: `user`, `binding`, `deletion`, `ecosystem`, `terminal`, `cost`,
  `graph/code`, `harness/library`; cataloghi `execution-targets.json`, provider e modelli ad
  abbonamento, due template.
- Console: `Agents`, `KnowledgeHub`, `Settings`, `Terminal`, `CodeGraphView`, `OrchestratorPanel`,
  `TaskDrawer`; componenti `BindingChain`, `DeleteDialog`, `EcosystemPanel`, `CostsCard`,
  `TerminalTab`, `ErrorBoundary`.
- `scripts/init-local-secrets.ps1`; `start-dev.ps1` carica i segreti locali.

## 13. Migrazioni e schema

| Versione | Contenuto |
|---|---|
| V20 | `app_users`, `auth_sessions`, `security_events` |
| V21 | agenti: `description`, `capabilities`, `execution_target`, `origin`, `baseline`, `customized_*` |
| V22 | risorse: `origin`, `file_path`, `file_synced_at` |
| V23 | `ecosystem_autostart` |
| V24 | prezzi dei modelli, `task_runs.cost_usd`, `cost_budgets` |
| seed V2 | legami, prompt e baseline degli agenti del seed (riempie solo campi vuoti) |

Tutte additive. Il DB live `aicompany` è a **V11** e non è stato toccato: al primo avvio riceverà
V12–V24 e il seed V2.

## 14. Test, CI, smoke

- **Suite**: 470 Java (+10 classi nuove), 58 engine, 27 console; typecheck e build della console.
- **Mutazioni** sulle nuove guardie: legame, budget, conferma di eliminazione, ruoli, ticket del
  terminale, SSRF, blocco del login — **7/7 rilevate**.
- **Smoke dal vivo 31/31**: sicurezza (401, credenziali errate, registro, admin), legami del seed,
  **regressione PHASE 3–7** (Progetto → Task → associazione → Agente → Start → risposta DeepSeek in
  45 s), **Piano → Fase → Task → Agente → Modello → Execution Target → Risultato → Review** (piano
  importato, 409 prima dell'approvazione, approvazione, orchestrazione, run DeepSeek in 80 s con la
  skill e il documento della task nel prompt, review → DONE), handoff manuale con pacchetto, delete,
  grafo del codice della console (46 file, 179 import), ecosistema, costi, shell del terminale.
- Lo smoke ha **trovato 5 difetti**, tutti corretti con test: password nel log, Open WebUI riaperto a
  ogni riavvio, blocco «progetto archiviato» mancante nella decisione, origine dei vecchi agenti da
  template, e un **ciclo di import nella console stessa** trovato dal grafo del codice.
- **CI**: verde su ogni commit del blocco verificato; controlla l'ultimo prima del merge.

## 15. Debiti residui

| Debito | Sintesi |
|---|---|
| TD-44 / PHASE 26 | Gmail, Drive, ClickUp dentro la console: servono credenziali e una scelta |
| TD-53 | token di sessione nel `sessionStorage` (mitigato da React, scadenza, revoca) |
| TD-54 | una run senza modello (default dell'engine) non passa dal budget; oggi il default è `echo` |
| TD-55 | l'esito di un handoff non torna da solo: review manuale |
| TD-56 | Open WebUI desktop accende il server solo dal suo interno |
| TD-48, TD-49 | MCP come metadato; API di `opencode serve` non usata |
| TD-51, TD-52 | rimozione di `llama3.2:3b`; coder più recente (19 GB) — decisioni tue |

Chiusi in questo blocco: **TD-40** (costi), **TD-42** (terminale); TD-46 in parte.

## 16. HEAD, branch, working tree

- Branch `autonomous/phase-15-hardening`, pushato; `master` a `e9c928c`.
- Working tree pulito dopo l'ultimo commit (l'unico file locale escluso da git è la configurazione
  di anteprima `frontend/vite.preview.local.mjs`, in `.git/info/exclude`).

## 17. Cosa puoi verificare a vista

1. Accesso con `admin`, banner della password, *Account e sicurezza*, cambio password.
2. **Agenti**: catena di ogni agente, editor, anteprima del legame, «iniziale/modificato», ripristino.
3. **Task**: riquadro COSA, «Assegna agente» con le card e la frase di conferma; pannello Master
   Orchestrator con i target e la conferma della consegna.
4. **Knowledge Hub**: apri una skill, «Crea il file», modificala, salvala, «Apri in VS Code».
5. **Impostazioni**: utenti, registro di sicurezza, ecosistema all'avvio.
6. **Progetto → Codice** (grafo), **Reference** (incolla un'immagine con Ctrl+V), elimina una task di
   prova digitando il nome.
7. **Terminale**: apri PowerShell nella cartella di un progetto.
8. **Consumi → Costi**; **Modelli** → prezzo di un modello a consumo.

## 18. Avvio

```powershell
.\scripts\start-dev.ps1
```

Poi <http://localhost:5173>, utente `admin`, password dal file locale (§11). Il control plane è su **8081**; all'avvio
partono anche Ollama, Open WebUI e 3D Omniverse se non sono attivi. Per provare prima su un clone:
`.\scripts\start-dev.ps1 -Database aicompany_try` (vedi l'intestazione dello script). Dettagli:
`docs/RUNNING.md` §0 e §0b.

## HUMAN ACTION REQUIRED

**1. Accettare il blocco e integrarlo in `master`.**
- *Problema*: PHASE 15–27 sono su `autonomous/phase-15-hardening`; `master` è a PHASE 14.
- *Perché serve il consenso*: il merge è l'accettazione (charter §8).
- *Opzioni*: (a) fast-forward di `master` all'ultimo commit del branch; (b) chiedere modifiche.
- *Default consigliato*: **(a)**, dopo aver provato i punti del §17.

**2. Primo avvio sul DB live `aicompany`.**
- *Problema*: il DB è a V11; il nuovo backend applicherà V12–V24 e il seed V2, e creerà l'admin.
- *Perché*: tocca i tuoi dati (solo in aggiunta; il seed V2 riempie solo i modelli vuoti degli agenti
  del seed).
- *Default consigliato*: `pg_dump` di sicurezza, poi `start-dev.ps1`.

**3. Integrazioni Gmail, Drive, ClickUp (PHASE 26).**
- *Problema*: non si incorporano (policy di framing misurate); dentro la console servono le loro API.
- *Perché*: credenziali OAuth Google e un token ClickUp sono tuoi, e la scelta cambia il prodotto.
- *Opzioni*: (a) restare con l'apertura controllata di oggi; (b) API in sola lettura con credenziali
  che crei tu; (c) una shell desktop con WebView.
- *Default consigliato*: **(a) ora**, (b) quando vorrai i dati dentro la console.

Nessun'altra azione è necessaria per usare il sistema.
