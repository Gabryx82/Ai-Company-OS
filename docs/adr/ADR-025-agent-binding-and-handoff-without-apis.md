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
