# ADR-018 — Il modello di dominio dell'ecosistema: nove concetti, non uno

- **Stato**: Accettata (PHASE 8); implementazione progressiva PHASE 8–12
- **Data**: 2026-09-24
- **Decisa da**: agente, su direttiva umana di riallineamento del 2026-09-24 (§27 «Separazione
  fondamentale dei concetti»)
- **Estende**: ADR-008 (Agent Registry), ADR-015 (model gateway), ADR-016 (run). Non ne supera
  nessuna.

## 1. Contesto

Dopo PHASE 7 il dominio conosce tre entità vive — `Project`, `Task`, `Agent` — più `TaskRun`.
`agents.model` è una stringa `provider:model` risolta dall'engine. La visione riallineata chiede che
l'orchestratore scelga **agente, modello, software, contesto e prompt** per ogni task, e che nove
concetti restino distinti anche quando sono collegati. Un orchestratore che sceglie «il software»
ha bisogno che il software esista come entità, con capability interrogabili.

## 2. Decisione: i concetti e le loro frontiere

| Concetto | Che cosa è | Che cosa **non** è | Entità / luogo |
|---|---|---|---|
| **Agent** | Un ruolo operativo: chi fa il lavoro, con quale prompt e quale harness | Non è un modello né un programma | `agents` (esistente, esteso in PHASE 12) |
| **Subagent** | Un agente specializzato subordinato a un altro | Non è un tipo diverso: è un `Agent` con un genitore | `agents.parent_id` (PHASE 12) |
| **Model** | Un LLM identificato da `provider:nome`, con ruolo e capability | Non sa chi lo usa; non è un'applicazione | `llm_models` (PHASE 8) |
| **Provider** | Chi serve i modelli (Ollama, Anthropic API, OpenRouter, echo, DwarfStar) | Non è un modello né un abbonamento | `model_providers` (PHASE 8) |
| **Software** | Un'applicazione eseguibile, un IDE, una CLI, un sito o un servizio locale | Non esegue prompt da sé: si apre, si prepara, si consegna | `software` (PHASE 8) |
| **Tool** | Una capability invocabile da un agente (funzione, comando, API) | Non è un'applicazione intera | `harness_resources` `kind=TOOL` (PHASE 12) |
| **Skill** | Una competenza impacchettata (istruzioni + risorse) | Non è conoscenza grezza | `harness_resources` `kind=SKILL` |
| **Knowledge** | Conoscenza consultabile (documenti, vault, reference) | Non è un comportamento | `harness_resources` `kind=KNOWLEDGE` |
| **MCP** | Un server del Model Context Protocol che espone tool/risorse | Non è un singolo tool | `harness_resources` `kind=MCP` |

Relazioni ammesse (tutte **riferimenti**, mai copie):

```
Agent ──model──▶ Model ──provider──▶ Provider
Agent ──parent──▶ Agent                       (sottoagente)
Agent ──harness──▶ Tool | Skill | Knowledge | MCP
Agent ──uses──▶ Software
Software ──for project types──▶ ProjectType
Model ──role──▶ (fast | general | coder | planner | vision | reasoning | cloud-premium)
```

## 3. Perché Tool/Skill/Knowledge/MCP stanno in una tabella sola, con un `kind` chiuso

Oggi i quattro hanno **esattamente** gli stessi attributi (chiave, nome, descrizione, sorgente,
configurazione, stato di installazione) e lo stesso ciclo di vita, e l'Explorer li cerca insieme.
Quattro tabelle identiche sarebbero quattro copie dello stesso schema. La separazione concettuale è
garantita dal **vocabolario chiuso** `kind` con `CHECK` nel database — la stessa garanzia che
`status` ha ovunque in questo codice — e dall'API, che li espone per tipo. Se un giorno un tipo
acquisisce attributi propri, separarlo è una migrazione additiva.

Model, Provider, Software e Agent hanno invece attributi e cicli di vita **diversi** fra loro, ed
è per questo che sono tabelle distinte.

## 4. Il catalogo: dati di riferimento, non seed di sviluppo

Software, provider e modelli conosciuti sono **dati di prodotto**, validi in ogni profilo. Non
vanno nello stream del seed di sviluppo (ADR-003), che è per fixture. Vivono in file versionati
(`backend/src/main/resources/catalog/*.json`) e un bootstrap all'avvio inserisce **solo le chiavi
mancanti** (`INSERT … ON CONFLICT (key) DO NOTHING`):

- una voce modificata dall'operatore **non viene mai sovrascritta** da un avvio successivo;
- una voce nuova nel catalogo appare al primo avvio dopo l'aggiornamento;
- una voce cancellata dal catalogo non viene cancellata dal DB (nessuna cancellazione implicita).

Gli agenti nuovi dell'ecosistema, invece, sono **fixture di sviluppo** come i tre esistenti, e
vanno nello stream del seed (ADR-003), con la stessa guardia `WHERE NOT EXISTS`.

## 5. Lo stato «installato» non si salva, si rileva

Se il DB dicesse «IntelliJ installato», mentirebbe il giorno dopo una disinstallazione. Il catalogo
registra **come riconoscere** un software (AppID del menu Start, eseguibile, URL di health); il
rilevamento avviene al momento della lettura, con una cache breve (ADR-019 §3).

## 6. Conseguenze

- `agents.model` resta una stringa `provider:model` (V11): diventa un riferimento *risolvibile* nel
  catalogo, non una chiave esterna. Un modello non catalogato resta utilizzabile se l'engine lo
  conosce — il catalogo aggiunge informazione, non toglie possibilità.
- L'orchestratore (ADR-021) sceglie leggendo questi cataloghi; nessuna tabella di routing nascosta.
- Nessuna colonna esistente cambia significato; tutte le migrazioni della roadmap V2 sono additive.
