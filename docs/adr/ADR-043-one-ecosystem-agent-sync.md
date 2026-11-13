# ADR-043 — Un ecosistema solo: gli agenti nascono in AI Company OS e arrivano agli strumenti

- **Stato**: Implementata (PHASE 37), in attesa della review umana; non committata. **Rivista da ADR-045** (2026-11-12):
  LM Studio esce dall'ecosistema (§2, §4, §7), TD-100 è chiuso. **Rivista da ADR-046** (2026-11-12): la lista degli
  agenti di OpenClaw non si aspetta più (§9: letta in background, tenuta 30 s per leggere e 60 s per scrivere).
- **Data**: 2026-11-10
- **Decisa da**: agente, su direttive dell'operatore del 2026-11-10 («Ho installato OpenClaw, integralo nel workflow»;
  «Sincronizza anche gli agenti in AI Company OS e in OpenClaw, deve risultare un unico ecosistema»; «AI Company OS deve
  diventare il sistema principale… userò lui per creare agenti che si sincronizzeranno con Open WebUI, LiteLLM, LM
  Studio e OpenClaw»)
- **Estende**: ADR-019 (Software Hub), ADR-025 (target di esecuzione e handoff senza API), ADR-036 (Knowledge Hub fra
  le app), ADR-042 (verifica prima della consegna). Apre TD-100.

## 1. Problema

Un agente si definiva in AI Company OS e viveva solo lì: per parlarci in Open WebUI, per instradarlo dal gateway
LiteLLM o per dargli una task in OpenClaw bisognava rifarlo a mano in ogni strumento, e tenerlo allineato a mano.
OpenClaw, appena installato, non esisteva per il control plane: né come programma, né come posto in cui consegnare una
task, né come cartella di skill.

## 2. Decisione

**AI Company OS è la fonte.** Un agente si crea e si modifica qui; ogni strumento ne riceve una copia nella forma che
quello strumento ha per un agente. La copia si scrive quando l'operatore lo chiede, mai da sola.

| Strumento | Che cosa diventa l'agente | Come si scrive | Verificato |
|---|---|---|---|
| **OpenClaw** | un agente con il suo spazio di lavoro: `AGENTS.md` (programma operativo), `IDENTITY.md`, `SOUL.md`, identità e descrizione nella configurazione | i comandi di OpenClaw (`agents add`, `config patch --file`, `agents delete`) e i tre file dello spazio di lavoro | CLI reale 2026.11.2, in una cartella di stato isolata |
| **Open WebUI** | un modello dell'area di lavoro sopra il modello Ollama dell'agente, con il prompt di sistema | la sua API (`/api/v1/models`), con una chiave dell'operatore | sorgente della 0.11.0 installata; vedi §9 |
| **LiteLLM** | un alias del gateway che porta al modello dell'agente | un blocco marcato nel file di configurazione del gateway | file reale letto; scrittura su copie |
| ~~LM Studio~~ | — | tolto il 2026-11-12 (ADR-045 §2): non è installato | — |

## 3. Invarianti

1. **Niente si scrive senza un gesto.** La lettura dello stato non scrive in nessuno strumento; `POST` e `DELETE` di
   `/api/agent-sync/**` sono dell'amministratore, come ciò che il launcher può eseguire.
2. **Si scrive solo ciò che si è fatto.** In ogni strumento gli agenti di AI Company OS hanno un identificatore che
   comincia con `aicos-`; un connettore non crea, aggiorna o rimuove nient'altro. Un file dello spazio di lavoro di
   OpenClaw si riscrive solo se porta il marcatore del connettore o se l'ha appena creato OpenClaw per questa
   pubblicazione: un file riscritto a mano resta com'è, e la pubblicazione lo dice.
3. **Un agente che lo strumento aveva già resta suo.** Portato in AI Company OS è collegato (`owned = false`): non viene
   mai riscritto, e scollegarlo non rimuove niente nello strumento.
4. **Lo stato si calcola, non si ricorda.** `agent_publications` (V44) tiene l'identificatore e l'impronta di ciò che è
   stato scritto; un agente è «da aggiornare» quando ciò che si scriverebbe adesso ha un'altra impronta. L'impronta è
   del connettore: un alias LiteLLM, che porta solo il modello, non risulta indietro quando cambia il prompt.
5. **Nessun segreto passa dal control plane.** Il file di OpenClaw contiene il token del gateway: il connettore non lo
   apre mai, usa i comandi di OpenClaw. La chiave di Open WebUI è dell'operatore (`AICOS_OPENWEBUI_API_KEY` in
   `local.env`), va solo all'indirizzo configurato e non alla sonda `/health`; senza chiave non si chiede altro.
6. **Il file di un altro non si rovina.** Del file di LiteLLM cambia solo il blocco fra due righe marcatore, in fondo a
   `model_list`; prima del primo blocco il file è copiato accanto a sé; prima di ogni scrittura il risultato è letto come
   YAML e rifiutato se non contiene tutti i modelli che c'erano più gli alias voluti. Togliere l'ultimo alias restituisce
   il file originale, carattere per carattere.
7. **Un fallimento lascia vero lo stato.** La riga si registra dopo che lo strumento ha accettato l'agente, e nessuna
   transazione resta aperta mentre una CLI lavora.

## 4. OpenClaw nel flusso di lavoro

- **Software Hub**: `openclaw` (CLI, `openclaw tui`), `openclaw-gateway` (servizio locale, `/healthz`), e con essi
  `litellm`, perché l'ecosistema si veda intero (`lm-studio` c'era fino ad ADR-045).
- **Target di esecuzione `openclaw`** (`CLI_PROMPT`, nessun provider: il modello è quello di OpenClaw, e il pacchetto
  non nomina quello dell'agente). La consegna apre Windows Terminal con
  `openclaw tui --session agent:<id>:aicos-task-<n> --message "<prompt>"`: una sessione per task, indirizzata
  all'agente di OpenClaw che corrisponde a questo quando c'è, a quello predefinito quando non c'è (la verifica prima
  della consegna lo dice, come avviso).
- **L'agente di OpenClaw non lavora nella cartella**: gira nel gateway, nel suo spazio di lavoro. Il prompt compatto
  nomina perciò la cartella del progetto per intero («Lavora nella cartella … : leggi lì il file …»), e il programma
  operativo di un agente pubblicato spiega che cosa fare quando arriva una task di AI Company OS.
- **Knowledge Hub**: OpenClaw legge `~/.agents/skills` (la cartella condivisa che il Hub già scrive) e ha la sua
  `~/.openclaw/skills`. Alla pubblicazione le skill dell'agente che non hanno una copia lì vengono copiate nella
  cartella condivisa, mai sopra una cartella che il Hub non ha scritto.
- **La lista di skill dell'agente non diventa un filtro** (`agents.entries.*.skills`): in OpenClaw un filtro sostituisce
  tutto, e toglierebbe all'agente le skill proprie di OpenClaw. Le skill sono nominate nel programma operativo.
- **Il modello resta di OpenClaw.** Tradurre `ollama:…` in un modello di OpenClaw richiede un provider configurato lì:
  non si indovina.

## 5. Open WebUI

`POST /api/v1/models/create` e `/model/update` con `id`, `base_model_id` (il nome del modello in Ollama), `name`,
`meta.description`, `params.system`. Un modello già presente si aggiorna tenendo ciò che l'operatore gli ha aggiunto lì
(immagine, knowledge, parametri, condivisione, lo stato acceso/spento). Un agente senza modello locale di Ollama non è
pubblicabile, e lo stato lo dice («non adatto», con il motivo).

## 6. LiteLLM

Il gateway di questa macchina gira senza database: i suoi modelli sono solo quelli del file, e il registro degli agenti
e i prompt gestiti di LiteLLM 1.97 non sono disponibili. Un alias porta quindi **solo** il modello
(`ollama_chat/<nome>`, con l'`api_base` che le voci del gateway già usano). Il gateway legge il file all'avvio: dopo una
sincronizzazione va riavviato, e l'esito lo dice. Il file si trova da `AICOS_LITELLM_CONFIG`, altrimenti è quello che
3D Omniverse tiene per il gateway che avvia.

## 7. LM Studio — non scritto (TD-100), poi tolto (ADR-045)

> **Superato il 2026-11-12**: LM Studio non è sulla macchina dell'operatore ed è uscito dall'ecosistema (ADR-045 §2);
> TD-100 è chiuso senza lavoro. Il testo che segue resta come motivazione, se tornasse.

La documentazione di LM Studio dice dove stanno i preset (`~/.lmstudio/config-presets`) e non il formato del file; LM
Studio non è installato su questa macchina per salvarne uno e leggerlo. Un file in un formato ricostruito a memoria,
messo nella cartella di un'altra applicazione, non si consegna: il connettore c'è, dice come stanno le cose e non
scrive. **TD-100**: quando LM Studio è installato, leggere un preset salvato dall'app e scrivere i preset degli agenti.

## 8. Creare un agente e dargli le skill giuste

`GET /api/agents/{id}/resource-suggestions`: le skill e le knowledge della libreria pertinenti a ciò che l'agente è
(ruolo, specializzazione, descrizione, capability, dominio, responsabilità, istruzioni), con la stessa regola che
sceglie le voci pertinenti a una task (ADR-041 §7). Proposte nella scheda «Skill e strumenti», aggiunte con un clic,
mai da sole.

## 9. Che cosa ha trovato lo smoke dal vivo

Smoke isolato (backend 8097, console 5183, database `aicompany_p37`, home e cartelle nello scratchpad; la CLI vera di
OpenClaw 2026.11.2 su una cartella di stato propria, un Open WebUI 0.11.0 usa e getta sulla 3997, un file di gateway
scritto per la prova): **42 controlli su 42** dopo tre correzioni che nessun test con risposte simulate poteva chiedere.

| Trovato | Causa | Correzione |
|---|---|---|
| Open WebUI rispondeva `422` a ogni creazione | il client HTTP di Java propone l'upgrade a HTTP/2 su una connessione in chiaro, e uvicorn legge allora la richiesta senza il corpo | `HttpSyncHttp` parla HTTP/1.1 e non propone niente; `HttpSyncHttpTest` lo prova su un server locale (rosso senza la riga) |
| `agents delete` di OpenClaw usciva con successo lasciando la rimozione a metà | l'uscita è 0 anche quando una cartella non va nel cestino; e dopo il JSON stampa una riga di avviso, che il lettore rifiutava | il connettore legge `failed` e lo dice; il lettore prende il valore JSON e lascia il resto |
| La pagina Ecosistema impiegava 37–52 s | con il gateway di OpenClaw spento ogni comando `openclaw` lo aspetta circa mezzo minuto (33 s per `agents list`, misurato anche sull'installazione dell'operatore) | prima di ogni comando il connettore chiede `/healthz` al gateway: spento, lo dice in mezzo secondo, con il comando per avviarlo (`aicos.sync.openclaw.gateway-url`); l'elenco degli agenti è tenuto 10 s e aggiornato dalle scritture del connettore, così una sincronizzazione di più agenti lo chiede una volta |

**Non verificato dal vivo:**

- **La tua installazione di OpenClaw.** Dalla shell isolata dell'agente i comandi `openclaw config` falliscono anche in
  sola lettura (la cartella di cache di OpenClaw, `%LOCALAPPDATA%\openclaw`, non è utilizzabile da lì; con
  `XDG_CACHE_HOME` su una cartella propria la CLI fa ciò che il connettore si aspetta). La prima sincronizzazione vera la
  fa l'operatore dalla console, con il gateway acceso.
- **L'apertura di `openclaw tui`** con il launcher acceso: aprirebbe una sessione vera sul tuo abbonamento.
- **Il riavvio del gateway LiteLLM** e la scrittura nel suo file reale.
- **L'aspetto delle pagine**: il pannello del browser dell'agente era nascosto e non ha prodotto immagini; struttura,
  testi, stati e larghezze (1440 e 375 px, nessuno sforamento) sono stati letti dal DOM.

## 10. Contratto e schema

Additivo. `GET /api/agent-sync`, `POST /api/agent-sync/publish`, `POST /api/agent-sync/import`,
`DELETE /api/agent-sync/{tool}/agents/{agentId}`, `GET /api/agents/{id}/resource-suggestions`; problemi
`sync-tool-not-found`, `sync-tool-unavailable`, `sync-refused`. V44 `agent_publications`.

## 11. Test

`LiteLlmConnectorTest` (11), `OpenClawConnectorTest` (11), `OpenWebUiConnectorTest` (9), `HttpSyncHttpTest` (4),
`AgentSyncApiTest` (10), `OpenClawHandoffApiTest` (3), più un caso in `ExecutionTargetCatalogTest` e in
`HarnessApiTest`; nella console `AgentEcosystem.test.tsx` (5) e due casi in `Agents.test.tsx`.

Mutazioni viste rosse, una per protezione (12): il controllo dei modelli prima di scrivere il file di LiteLLM; la copia
prima del primo blocco; la scrittura dei file di OpenClaw solo se suoi; la rimozione solo con il prefisso (OpenClaw e
Open WebUI); un agente portato dallo strumento mai riscritto; `POST`/`DELETE` solo per l'amministratore; il prompt con
la cartella per l'agente che lavora altrove; l'impronta dell'alias fatta di ciò che l'alias contiene; la sessione
indirizzata all'agente di OpenClaw; HTTP/1.1 senza proposta di upgrade; la sonda del gateway prima della CLI.

Suite finali (2026-11-11): Java **914** (0 falliti, 2 saltati: i link simbolici), console **192 su 192** con typecheck
pulito. AI Engine non toccato.
