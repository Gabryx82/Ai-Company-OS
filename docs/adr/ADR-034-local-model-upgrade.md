# ADR-034 — Upgrade dei modelli locali: misurati, per ruolo, sulla macchina reale

- **Stato**: Accettata e implementata (PHASE 29), in attesa di Human Final Review
- **Data**: 2026-09-30
- **Decisa da**: agente, su direttiva dell'operatore («aggiorna in autonomia tutti i modelli Ollama
  dei due ecosistemi, massimizzando l'intelligenza complessiva sulla macchina attuale»)
- **Supera in parte**: il ruolo CODER di ADR-018 §2 (TD-52)

## 1. Contesto: il vincolo è la RAM libera, non il disco

| Risorsa | Misurato |
|---|---|
| CPU | Intel Core Ultra 7 258V, 8 core (4P + 4LPE) |
| RAM | 31,5 GB LPDDR5X-8533 unificata; **9–13 GB liberi** con il desktop abituale (browser, app ChatGPT/Claude, WebView) |
| GPU | Intel Arc 140V integrata (memoria condivisa); Ollama la scarta per default (`dropping integrated GPU`) |
| Disco | 141–180 GB liberi |
| Ollama | 0.33.3, inferenza su CPU, contesto di default **4096** token (nessuna VRAM rilevata) |

Un modello utile qui deve stare, con la sua finestra di contesto, in ~7–8 GB, lasciando spazio al
resto del desktop. I modelli di nuova generazione da 27–35B (Qwen 3.6/3.8, MoE A3B da 18–24 GB)
sono fuori portata; lo è anche Qwen3.8-27B quantizzato a 10 GB (§3.5).

## 2. Metodo

Stesso banco di prova per ogni modello, con **verifica automatica**, mai a occhio (script in
`%TEMP%` della sessione, riportati nell'handoff):

| Prova | Come si valuta |
|---|---|
| codice | funzione Python (ordinamento topologico con vincoli), 6 test eseguiti |
| ragionamento | 4 problemi con risposta intera unica, in italiano |
| output strutturato | piano JSON con schema (`format`), 5 controlli di coerenza |
| strumenti | 2 tool, 4 controlli su nomi e argomenti delle `tool_calls` |
| visione | render Blender noto (3 cubi rossi, 1 sfera blu, 2 coni verdi), 4 controlli |
| bpy / USD | script Blender 5.0 eseguito in Blender headless e scena ri-letta con `pxr`: 8 controlli; più un secondo giro con il traceback reale, come il pulsante «Correggi l'errore» di 3D Omniverse |
| USD puro | stage `pxr` (up axis, defaultPrim, sfere, trasformazioni, materiale, attributo), 8 controlli, eseguito col Python di Blender |

Qualità a temperatura 0 e `think: false` (come chiama 3D Omniverse); ruolo `script` anche a
temperatura 0,3 con 3 seed (le impostazioni reali dell'app); velocità e memoria a macchina scarica.
**Rumore dichiarato**: dopo ore al 100% di CPU il portatile scalda; le misure di velocità vicine
nel tempo sono confrontabili, quelle lontane hanno ±15–30%.

## 3. Risultati

### 3.1 Qualità (`think: false`, temperatura 0)

| Modello | codice | ragion. | JSON | strumenti | visione | bpy (1° → riparato) |
|---|---|---|---|---|---|---|
| qwen3.5:9b | 6/6 | 4/4 | 5/5 | 4/4 | 4/4 | 0 → 0 |
| qwen3.5:4b | 6/6 | 4/4 | 5/5 | 4/4 | 4/4 | 0 → 0 |
| gemma4:e4b (nuovo pacchetto) | 6/6 | 4/4 | 5/5 | 4/4 | 4/4 | 2 → 2 |
| gemma4:e4b (pacchetto precedente) | 6/6 | 4/4 | 5/5 | 4/4 | 3/4 | 1 → 7 |
| lfm2.5:8b | 6/6 | 4/4 | 5/5 | 4/4 | — | 0 → 0 |
| deepseek-coder-v2:16b | 4/6 | 0/4 | 3/5 | — | — | 2 → 2 |
| llama3.2:3b | 4/6 | 1/4 | 4/5 | 4/4 | — | 1 → 1 |
| ornith:9b (candidato) | 6/6 | 1/4 | 5/5 | 4/4 | — | 0 → 0 (ripetizioni) |

Ruolo `script` di 3D Omniverse, temperatura 0,3, 3 seed (primo colpo → dopo riparazione):

| Modello | bpy | USD puro |
|---|---|---|
| deepseek-coder-v2:16b | 5→5, 5→5, 6→6 | 0, 0, 0 |
| gemma4:e4b | 2→2, 2→1, 1→2 | 0, 0, 0 |
| qwen3.5:9b | 0→1, 0→0, 0→0 | 0, 0, 0 |

Nessun modello locale scrive USD puro corretto: tutti inventano API plausibili
(`Stage.SetUpAxis` invece di `UsdGeom.SetStageUpAxis`, `bpy.ops.export_scene.usd` invece di
`bpy.ops.wm.usd_export`); il giro di riparazione non basta, perché manca la conoscenza dell'API.

### 3.2 Velocità e memoria (CPU, contesto 4096)

| Modello | generazione tok/s | caricamento a freddo | residente (Ollama) |
|---|---|---|---|
| qwen3.5:9b | 5,9 | 8,3 s | 5,65 GB |
| qwen3.5:4b | 9,6 | 6,3 s | 2,92 GB |
| gemma4:e4b (nuovo) | 8,7 prosa · 12,7–19,1 su codice e JSON | 12,8 s | vedi §3.3 |
| gemma4:e4b (precedente) | 8,1 prosa | 8,4 s | 8,78 GB |
| lfm2.5:8b | **22,2** | 7,2 s | 4,90 GB |
| deepseek-coder-v2:16b | 11,8 | 12,1 s | 9,43 GB |
| llama3.2:3b | 14,6 | 3,6 s | 2,39 GB |
| ornith:9b | 4,2 | 8,6 s | 5,50 GB |
| Qwen3.8-27B GSQ IQ3_XXS | 0,9 → 0,3 sotto pressione di memoria | 13,3 s | 10,6 GB |

Elaborazione del prompt (primo giro, senza cache): qwen3.5:9b 23 tok/s, qwen3.5:4b 41, gemma4:e4b 35,
lfm2.5:8b 72 — un prompt di 1.300 token costa circa un minuto al 9B prima del primo token.

### 3.3 Memoria a regime, 4K e 16K

Working set del runner (`llama-server.exe`) con un solo modello caricato e il precedente già
uscito — `/api/ps` di Ollama sottostima alcuni pacchetti (gemma4:e4b nuovo: 1,7 GB dichiarati,
6,9 reali):

| Modello | 4096 | 16384 | differenza |
|---|---|---|---|
| qwen3.5:9b | 6,46 GB | 6,86 GB | +0,40 |
| qwen3.5:4b | 3,51 | 3,90 | +0,39 |
| gemma4:e4b (nuovo) | 6,94 | 7,16 | +0,22 |
| gemma4:e4b (precedente) | 4,43 | 4,64 | +0,21 |
| lfm2.5:8b | 4,91 | 5,06 | +0,15 |
| deepseek-coder-v2:16b | 9,40 | **12,56** | **+3,16** |
| llama3.2:3b | 2,38 | 3,70 | +1,32 |

I modelli di nuova generazione (attenzione ibrida o a finestra scorrevole) pagano poco la finestra
grande; i due ad attenzione piena molto. Da qui la finestra per modello: 16384 per default, 4096 per
deepseek-coder-v2 e llama3.2 (`AICOS_ENGINE_OLLAMA_NUM_CTX_BY_MODEL`; in 3D Omniverse `num_ctx` sullo
agente `script`). Il pacchetto nuovo di gemma4:e4b pesa meno su disco ma **~2,5 GB in più in RAM**
del precedente (§3.4 bis).

### 3.4 iGPU Intel Arc 140V (Vulkan, server Ollama temporaneo)

| Modello | prompt CPU → iGPU | generazione CPU → iGPU | caricamento |
|---|---|---|---|
| qwen3.5:9b | 23 → 86 tok/s (3,7×) | 5,9 → 6,0 | 8 → 17 s |
| qwen3.5:4b | 41 → 138 (3,4×) | 9,6 → 9,3 | 6 → 8 s |
| gemma4:e4b | 35 → 71 (2×) | 8,7 → 7,1 | 13 → 21 s |
| lfm2.5:8b | 72 → 111 (1,5×) | 22,2 → **10,8** | 7 → 9 s |
| Qwen3.8-27B GSQ | 7,8 → 11,9 | 0,9 → 0,9 | 13 → 35 s |

Qualità invariata su iGPU (qwen3.5:9b codice 6/6, JSON 5/5, visione 4/4; gemma4:e4b JSON 5/5,
visione 4/4). Guadagno netto dipendente dal modello e attivazione solo globale: **lasciata spenta**
(TD-67).

### 3.4 bis I due pacchetti di gemma4:e4b, a confronto appaiato

Stesso modello, due pacchetti Ollama: il precedente (9,6 GB su disco) e quello pubblicato il
2026-09-30 (6,6 GB, bozza MTP per la decodifica speculativa). Due giri alternati, stesse condizioni:

| | precedente | nuovo |
|---|---|---|
| codice | 8,1–8,2 tok/s, 110 s | 9,2–9,4 tok/s, 76 s |
| JSON | 6,8–8,1 tok/s | 10,0–10,1 tok/s |
| chiamate a strumenti | 10,2–10,8 tok/s | 17,0–18,3 tok/s |
| visione (sonda nota) | 3/4 (conta 1 cubo invece di 3, due volte su due) | 4/4 |
| RAM a regime | **4,43 GB** | **6,94 GB** |

Scelto il nuovo (più veloce e più preciso); il costo è RAM. Il precedente resta installato come
`gemma4:e4b-legacy` e `rollback-local-models.ps1 -RestoreGemmaPackage` lo rimette sul tag.

### 3.5 Qwen3.8-27B («gira in 16 GB»)

Open weights (`Qwen/Qwen3.8-27B`, Apache-2.0, 2026-08-05). Il tag ufficiale Ollama pesa 18 GB; la
quantizzazione GSQ-RCO di ISTA-DASLab `IQ3_XXS` pesa 10,1 GB (dichiarata ~100% di recupero sui
benchmark) ed è stata importata con renderer e parser del tag ufficiale (`qwen3.8-gsq:27b-iq3xxs`,
SHA-256 verificato). Misurato: codice 6/6 con risposta concisa, ma **0,9 token/s** a macchina
scarica, **0,3 token/s** quando la RAM libera scende a 1,3 GB (swap); nessun beneficio dall'iGPU.
Un piano da 2.000 token richiederebbe 40 minuti–2 ore. Non è un modello per agenti su questa
macchina; resta installato come `CANDIDATE` per lavori lunghi non interattivi, a desktop chiuso.

### 3.6 Il ragionamento («thinking») su questa CPU

Le run testuali di AI Company OS non dicevano nulla a Ollama sul ragionamento, quindi Qwen 3.5
ragionava (default del modello), dentro la finestra di default di 4096 token. Stessi 4 problemi
della prova di ragionamento, campionamento del modello (temperatura 1, top_k 20, presence penalty
1,5 — quello che l'engine usa davvero):

| Modello | `think: false` | ragionamento, finestra 4096 | ragionamento, finestra 16384 |
|---|---|---|---|
| qwen3.5:9b | **4/4** in 742 token, ~4 min | 0/4: 3.873 token (finestra piena), nessuna risposta, 12,6 min | ancora nessuna risposta dopo 4.500 token (15 min, prova interrotta) |
| qwen3.5:4b | **4/4** in 786 token, ~2,7 min | 0/4: 3.873 token, nessuna risposta, 7,5 min | non misurato (interrotto: la decisione era già presa) |

Nell'output grezzo il modello arriva alle risposte giuste nel ragionamento e poi continua a
riverificarle («Wait…» 30 volte) finché finisce lo spazio. A 5–10 token/s il ragionamento non è un
investimento in qualità ma un costo che impedisce di rispondere. Decisione: l'engine chiede
`think: false` anche per le risposte testuali (`AICOS_ENGINE_OLLAMA_THINK`, default `false`;
`true` o `model` per tornare indietro), come già per i piani JSON e come fa 3D Omniverse; la finestra
sale a 16384 (`AICOS_ENGINE_OLLAMA_NUM_CTX`) per i prompt con molti file di contesto. `think: false`
verificato anche su modelli senza ragionamento (llama3.2, deepseek): accettato.

### 3.7 Più agenti insieme

Configurazione finale, finestra 16K, tre modelli diversi chiamati insieme (planner qwen3.5:9b con
JSON, gemma4:e4b, due worker lfm2.5:8b): tutte le risposte arrivano (176 s in tutto), ma Ollama tiene
in memoria tutti e tre i modelli (14,5 GB di runner) e la RAM libera scende a **0,1 GB**, con la
generazione rallentata di 2–3 volte. Fan-out di quattro richieste sullo stesso lfm2.5: 51 s in tutto,
servite in sequenza (13/19/27/49 s), RAM libera sopra i 12 GB. Da qui `OLLAMA_MAX_LOADED_MODELS=2`,
impostata come variabile utente su richiesta dell'operatore il 2026-10-01 (Ollama riavviato; verificato:
al terzo modello caricato Ollama scarica il meno recente e ne tiene due). Si toglie con
`[Environment]::SetEnvironmentVariable('OLLAMA_MAX_LOADED_MODELS', $null, 'User')` e un riavvio di Ollama.

## 4. Decisione

### AI Company OS

| Agente | Ruolo | Prima | Dopo | Fallback se manca il modello |
|---|---|---|---|---|
| Master Orchestrator, Software Engineer, Backend/Frontend/Database Engineer, Graph Engineer, Learning Coach, Code Architect (seed) | planner, coding, reasoning, structured output | qwen3.5:9b | **qwen3.5:9b** (invariato) | qwen3.5:4b |
| Frontend Developer, Database Specialist (seed) | coding, SQL | deepseek-coder-v2:16b | **qwen3.5:9b** (seed V3) | qwen3.5:4b |
| Test Engineer (template) | fast worker, tool use, fan-out | qwen3.5:4b | **lfm2.5:8b** (V27) | qwen3.5:4b |
| Tech Writer (template) | fast worker, prosa italiana | qwen3.5:4b | **qwen3.5:4b** (invariato) | — |
| UI/UX Designer (template) | visione | gemma4:e4b | **gemma4:e4b**, pacchetto nuovo | gemma4:e4b-legacy |
| Instradamento del lavoro di codice senza agente | CODER | deepseek-coder-v2:16b | **qwen3.5:9b** (ripiego su PLANNER, TD-66) | — |

AI Engine: `think: false` anche per le risposte testuali (§3.6, `AICOS_ENGINE_OLLAMA_THINK`),
`num_ctx` 16384 su ogni richiesta a Ollama (`AICOS_ENGINE_OLLAMA_NUM_CTX`) e catena di fallback
per i modelli non installati (`AICOS_ENGINE_OLLAMA_FALLBACKS`); il modello che ha davvero risposto
è sempre riportato.

### 3D Omniverse

| Agente | Ruolo | Prima | Dopo | Fallback |
|---|---|---|---|---|
| Orchestrator (Technical Director) | regia, routing, visione | gemma4:e4b | **gemma4:e4b**, pacchetto nuovo | gemma4:e4b-legacy |
| Script & Automation | Python/bpy/USD | deepseek-coder-v2:16b | **deepseek-coder-v2:16b** (riverificato: resta il migliore in locale) | gemma4:e4b |
| Visual QA | visione, PASS/FAIL | qwen3.5:9b | **qwen3.5:9b** (invariato) | gemma4:e4b (già in memoria) |
| ruolo `light` (catalogo, alias LiteLLM `light`) | risposte rapide | llama3.2:3b | **qwen3.5:4b** | llama3.2:3b |

Finestra di contesto 16384 anche qui (`OLLAMA_NUM_CTX` nel backend), 4096 per l'agente `script`
(`num_ctx` in `agents.json`, §3.3): un modello condiviso dai due ecosistemi viene chiesto con la
stessa finestra e non viene ricaricato a ogni cambio. Nessuna impostazione globale di Ollama è stata
toccata: Open WebUI e LiteLLM restano sul default di 4096.

## 5. Che cosa non si è fatto, e perché

- **Modelli 27–35B** (Qwen 3.6/3.8, Laguna XS, Nemotron 3.5 Lightning, Muse Glimmer, Gemma 4 26B/31B):
  18–25 GB, oltre la RAM libera. Qwen3.8-27B a 10 GB misurato: inutilizzabile come agente (§3.5).
- **ornith:9b** (fine-tune agentico di Qwen 3.5 9B, benchmark dichiarati: SWE-bench Verified 69,4
  contro 53,2): con `think: false` degenera in ripetizioni; con il ragionamento attivo mette tutto
  nel campo `thinking` e restituisce contenuto vuoto. Scartato e rimosso.
- **ornith-1.5:9b**: il pacchetto Ollama non dichiara né strumenti né ragionamento; scartato su carta.
  Un `ollama pull` di una coda interrotta ha continuato da solo e l'ha scaricato comunque: mai
  testato né referenziato, rimosso.
- **gemma4:12b**: sotto qwen3.5:9b sui benchmark di coding agentico e più lento; già rimosso ad agosto
  per la stessa ragione. Non scaricato.
- **deepseek-coder-v2:16b fuori da 3D Omniverse**: i numeri dicono il contrario (§3.1).
- **iGPU**: vedi §3.4.
- **Rimozione dei modelli precedenti**: restano tutti come fallback (deepseek, llama3.2:3b,
  gemma4:e4b-legacy). È stato rimosso solo il candidato scartato mai referenziato (ornith:9b).

## 6. Rollback

`scripts\rollback-local-models.ps1` (prova a secco di default): modelli Ollama precedenti, file di
3D Omniverse dal backup `backup\pre-model-upgrade-2026-09-30`, righe di agenti e catalogo ancora
come le ha lasciate questa fase. Il codice torna indietro col branch
(`autonomous/phase-28-project-blueprint`).

Verificato: su una copia di 3D Omniverse, indietro (9 file ripristinati, suite originale 50/50) e
di nuovo avanti (i 9 file tornano identici byte per byte a quelli in uso, suite 55/55); la parte
Ollama a secco (modelli precedenti tutti presenti); la parte database su un clone del database di
sviluppo (`aicompany_p29`), indietro e di nuovo avanti, con la console che in entrambi gli stati non
mostra campi «modificati» (la baseline segue il modello).

## 7. Verifiche

| Verifica | Esito |
|---|---|
| AI Engine, suite | 68/68 (12 test nuovi: fallback, finestra, finestra per modello, thinking, ciascuno visto rosso) |
| AI Engine dal vivo (istanza di prova, Ollama reale) | 14/14: tutti i modelli rispondono alle run testuali, piano JSON conforme allo schema, fallback reale che dichiara il modello servito, modello mancante senza fallback ancora `unknown-model` |
| Backend, suite | **518 test, 0 falliti**, 1 saltato (link simbolici su Windows, preesistente); `LocalModelUpgradeMigrationTest` 4/4, viste rosse le sue tre guardie con tre mutazioni delle migrazioni |
| Control plane dal vivo (clone del database reale, V25 → V26 → V27 + seed V3 all'avvio) | 17/17: catalogo (DeepSeek `DEPRECATED` → qwen3.5:9b, gemma 6,6 GB, lfm2.5 e Qwen3.8 inseriti), ogni agente su un modello installato, seed su qwen3.5:9b senza campi modificati, Test Engineer da template su lfm2.5:8b, una run reale per modello (lfm2.5 30 s, qwen3.5:4b 38 s, gemma4 59 s, qwen3.5:9b 87 s, tutte `stop`), lavoro di codice instradato su qwen3.5:9b (tramite l'agente scelto, non tramite il ripiego per ruolo), tre run in parallelo riuscite in 88 s |
| 3D Omniverse, suite | 55/55 (5 test nuovi; quello di coerenza agenti/catalogo/gateway visto rosso con una mutazione) |
| 3D Omniverse dal vivo (istanza su cartelle temporanee) | 3 agenti locali pronti; Orchestrator risponde in 63 s instradando in modo sensato; Script risponde ma lo script USD non passa (0/8, coerente con §3.1); Visual QA conta giusto il render noto (3/1/2) e dà FAIL per la sfera sfaccettata, come il suo mandato chiede, in 203 s |
| Concorrenza | §3.7 |

## 8. Upgrade hardware che sposterebbero la fascia

- **RAM**: è il vincolo. Con 64 GB (su questa piattaforma la memoria è saldata: significa un'altra
  macchina) entrerebbero i MoE da 30–35B con 3B attivi (Qwen 3.6 35B-A3B, Laguna XS 2.1, Nemotron 3.5
  Lightning), che con pochi parametri attivi girerebbero a una velocità simile a un 4B denso, e
  Qwen3.8-27B in Q4_K_M senza swap. È il salto di fascia più importante.
- **GPU dedicata con 16–24 GB di VRAM** (CUDA): Qwen3.8-27B e Gemma 4 31B a 20–40 token/s, e i
  modelli attuali 5–10 volte più veloci; il ragionamento («thinking») tornerebbe conveniente.
- **iGPU attuale**: già misurata (§3.4); utile solo con una scelta per modello (TD-67).

## 9. Conseguenze

Debiti: TD-65 (run con budget esaurito prima di rispondere), TD-66 (ruolo CODER senza modello
`ACTIVE`), TD-67 (iGPU per modello). TD-52 chiuso; TD-64 (verifiche bloccate da Docker) chiuso il
2026-10-01 dopo il riavvio di Docker. Il database di sviluppo `aicompany` è alla V25: al primo avvio
del backend su questo branch riceverà V26 (Blueprint, PHASE 28), V27 e il seed V3.

## 10. Aggiornamento del 2026-10-07: orchestratore e coach su Gemma 4

Su richiesta dell'operatore (PHASE 30), il **Master Orchestrator** e il **Learning Coach** passano da
`qwen3.5:9b` a **`gemma4:e4b`**: nei template (`catalog/agent-templates.json`) e, con **V29**, negli
agenti installati prima che sono ancora sul modello del loro template — con la baseline, come V27 per il
Test Engineer; un agente che l'operatore ha rilegato resta com'è. Le misure di §3 reggono la scelta:
`gemma4:e4b` fa come `qwen3.5:9b` su codice (6/6), ragionamento (4/4), output strutturato (5/5) e
strumenti (4/4), è più veloce su codice e JSON e legge le immagini; è già l'orchestratore di 3D
Omniverse. Costo da sapere: in RAM sta a circa 6,9 GB (§3.3), e con `OLLAMA_MAX_LOADED_MODELS=2`, quando
orchestratore o coach lavorano insieme a un agente su Qwen, Ollama tiene due modelli diversi in memoria.
La generazione del piano usa il modello scelto nella scheda *Piano* (default `qwen3.5:9b`), non quello
dell'agente: questa scelta cambia le run delle task assegnate ai due agenti.

## Revisione PHASE 32 (2026-10-08) — un modello serve anche altri ruoli (TD-66)

Dopo §4 nessun modello `ACTIVE` aveva il ruolo CODER: il lavoro di codice senza agente arrivava a
`qwen3.5:9b` come «ripiego su PLANNER», pur essendo il modello misurato migliore sul codice (6/6). Un
modello ha ancora **un** ruolo, il suo, e ora può **servirne anche altri** (`llm_models.also_serves`, V35;
`alsoServes` nel catalogo, nella risposta e nella riclassificazione, dove assente lascia com'è). V35 lo
imposta per il solo caso misurato — `qwen3.5:9b` serve anche CODER — e solo dove la riga è ancora quella di
V27. L'orchestratore cerca prima un modello del ruolo, poi uno che lo serve anche, e lo dice: «Modello
attivo che serve anche il ruolo CODER richiesto dalla task (ruolo proprio: PLANNER)»; il ripiego su PLANNER
resta solo quando nessuno serve il ruolo. La pagina Modelli mostra «serve anche: Codice».
`ModelRoleRoutingApiTest`, `Models.test.tsx`.

## Revisione PHASE 32 (2026-10-08) — la GPU integrata, modello per modello (TD-67)

Ollama accende l'iGPU Intel Arc 140V (Vulkan, `OLLAMA_IGPU_ENABLE=1`) per tutti i modelli insieme. Misurato:
Qwen 3.5 e Gemma 4 elaborano il prompt 2–3,7× più in fretta, LFM2.5 genera a metà velocità. L'engine ora
sceglie per modello quanti layer possono andare sulla GPU — l'opzione `num_gpu` di ogni richiesta, da
`AICOS_ENGINE_OLLAMA_NUM_GPU_BY_MODEL` — e di default tiene `lfm2.5:8b` sulla CPU (`0`); gli altri modelli
sono lasciati a Ollama. Con l'iGPU spenta, come oggi, non cambia nulla: `0` è ciò che succede comunque.
Accendere l'iGPU è una configurazione del servizio Ollama dell'operatore, non di AI Company OS: resta una sua
decisione (`docs/PHASE32_DECISIONS.md`). Test: `test_providers.py` (rosso se `num_gpu` non parte).
