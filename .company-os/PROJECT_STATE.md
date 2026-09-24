# AI Company OS — Project State

> **Fonte primaria dello stato.** Una sessione nuova, senza cronologia di chat, deve poter
> leggere questo file più `AUTONOMOUS_CHARTER.md` e `AUTONOMOUS_LOOP.md` e continuare il loop.

## Project
AI Company OS

## Modalità di lavoro
**Autonomous Project Mode con Human Final Review**, attiva dal 2026-09-14.
Autorità e hard stop: `.company-os/AUTONOMOUS_CHARTER.md`. Procedura: `.company-os/AUTONOMOUS_LOOP.md`.
L'agente definisce, implementa, revisiona e chiude le task senza approvazione intermedia.
`master` è il gate umano finale e non si tocca.

## Status
**PHASE 1 e PHASE 2: ACCETTATE DALLA REVIEW UMANA e INTEGRATE IN `master` il 2026-09-19.**

Il gate del charter §8 è stato attraversato: un umano ha accettato il lavoro e ha autorizzato il
merge. `master` è passato da **`d5ff121`** a **`6dc5989`** con **due fast-forward consecutivi** —
prima `autonomous/phase-1-foundations` (`0a35ac0`), poi `autonomous/phase-2-assignment`
(`6dc5989`) — **41 commit, nessun merge commit, nessuna riscrittura di storia**.

| Fase | Integration branch | Merge in `master` | Suite dopo il merge |
|---|---|---|---|
| **PHASE 1 — Foundations** | `autonomous/phase-1-foundations` (`0a35ac0`) | ✅ fast-forward | **158/158 verdi** |
| **PHASE 2 — Assignment** | `autonomous/phase-2-assignment` (`6dc5989`) | ✅ fast-forward | **219/219 verdi** |

Handoff di review: `docs/handoff/FINAL_HANDOFF_PHASE_1.md` (PHASE 1, conservato e non sostituito)
e `FINAL_HANDOFF.md` (PHASE 2).

Stato corrente **dopo TASK-012**: suite **223 test verdi**, **stream di migrazione a `V8`** (per
la versione del database locale vedi «Stato del sistema»), nessun failure aperto. **`origin` è
configurato dal 2026-09-20, ma nulla è ancora stato pushato** (TD-14). I numeri nella tabella qui
sopra sono quelli **al momento dei merge** e restano com'erano.

**I due integration branch restano dove sono**, fermi ai rispettivi tip: sono i marcatori
storici di che cosa conteneva ciascuna fase, e farli avanzare li renderebbe falsi.

L'obiettivo della fase — *il Company OS sa dire chi lavora su che cosa, e due client non possono
sovrascriversi in silenzio mentre lo dicono* — è **raggiunto in entrambe le metà**, e il criterio
di chiusura di `PHASE_2_PLAN.md` §4 è adesso soddisfatto in **tutte e tre** le condizioni: la
terza chiedeva un `FINAL_HANDOFF` aggiornato, e TASK-011 lo ha scritto.

**Il charter §8 è stato rispettato**: l'agente si è fermato e ha preparato l'handoff; il merge è
stato autorizzato esplicitamente da un umano e solo allora eseguito.

## ▶▶ Riallineamento V2 — PHASE 8 → PHASE 14 — COMPLETATO il 2026-09-24, in attesa di Human Final Review

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

Direttiva umana del 2026-09-24: PHASE 3–7 verificate e funzionanti dall'operatore, **ma non da
mergiare**; riallineare il prodotto alla visione completa dell'ecosistema, preservando ciò che
funziona, e proseguire autonomamente. Gap analysis: `docs/realignment/GAP_ANALYSIS_V2.md`. Roadmap:
**`.company-os/ROADMAP_V2.md`** (PHASE 8–21, MU-OS a fine PHASE 11).

| Fase | Contenuto | ADR | Stato |
|---|---|---|---|
| **8** Ecosystem catalogs | Software Hub (rilevamento reale, launcher ad allowlist), provider/modelli, consumi e quote, OpenRouter, porta 8081, nuova shell | 018, 019 | ✅ |
| **9** Project workspace | Profilo progetto, cartella governata, documenti sicuri | 020 | ✅ |
| **10** Planning | `plan.json`, generazione a stadi vincolata da JSON Schema, import da agente esterno, gate HITL | 021, 022 | ✅ |
| **11** Execution | Decisione motivata, handoff a Claude Code/Codex/Antigravity/OpenCode, review, contesto nelle run | 021, 022 | ✅ **MU-OS** |
| **12** Agent ecosystem | Prompt engineering, sottoagenti, harness, Knowledge Hub, template di agenti, Mockup Hub | 023 | ✅ |
| **13** Daily Work | Oggi/domani, riferimenti a task senza copie | — | ✅ |
| **14** Second Brain | Grafo animato dell'ecosistema | — | ✅ |
| 15–21 | Terminale PTY, graph engineering, template hub, integrazioni esterne, costi, 3D, release | — | da fare |

- **Branch**: `autonomous/phase-8-ecosystem-realignment`, da `autonomous/phase-7-operator-console`
  (che resta intatto). **Pushato**; CI su ogni push. **`master` intoccato** (`d166870`).
- **Suite**: **417 Java** (416 verdi + 1 saltato: link simbolici su Windows senza privilegi) + **56 engine** +
  **18 console**. Stream **`V19`** (V12–V19 tutte additive).
- **Live dev DB `aicompany`**: **`V11`** (lo ha migrato l'operatore durante la sua review di PHASE
  3–7 — la riga «V3» più sotto è superata). Al prossimo avvio si applicheranno V12–V19, additive.
  Tutte le verifiche dal vivo di questo blocco sono girate sul clone `aicompany_p8`.
- **Smoke reale MU-OS** (2026-09-24, clone): progetto → workspace → master prompt → piano generato
  da `qwen3.5:9b` in 12,8 min (5 fasi, 17 task) → gate 409 prima dell'approvazione → approvazione →
  decisione → run `qwen3.5:4b` in 226 s con contesto dai file e istruzioni GUIDED rispettate →
  review → DONE.
- **Mutazioni**: 5 sulle guardie critiche; 3 rosse subito, **2 sopravvissute** (test che non
  provavano ciò che dichiaravano) → test aggiunti, entrambe rosse.
- **Debiti nuovi**: TD-41…TD-52 (`docs/DEBT_REGISTRY.md`); prossimo libero **TD-53**.
- **Prossimo passo autonomo**: fermo per la Human Final Review (`FINAL_HANDOFF.md`). Dopo:
  PHASE 15 (terminale PTY integrato), poi l'ordine di `ROADMAP_V2.md`.

## ▶ Blocco autonomo PHASE 3 → PHASE 7 — COMPLETATO il 2026-09-23, in attesa di Human Final Review

> **Leggere prima questa sezione.** Il resto del file descrive lo stato fino a TASK-012 ed è
> conservato; dove questa sezione e il resto divergono, **vale questa**.

Autorizzazione umana esplicita del 2026-09-23: completare almeno cinque fasi consecutive senza
fermarsi a fine task o fase, **push su `origin` e uso della CI autorizzati**, una sola Human Final
Review alla fine. `master` resta il checkpoint umano e **non viene toccato**. Sequenza e
motivazione: **`.company-os/ROADMAP_PHASES_3_7.md`**.

| Fase | Integration branch | Task | Stato |
|---|---|---|---|
| **PHASE 3 — Security baseline** | `autonomous/phase-3-security` (da `master` `d166870`) | TASK-013, TASK-014 | ✅ **completata** |
| **PHASE 4 — Task lifecycle** | `autonomous/phase-4-task-lifecycle` (da phase-3) | TASK-015, TASK-016 | ✅ **completata** |
| **PHASE 5 — AI Engine** | `autonomous/phase-5-ai-engine` (da phase-4) | TASK-017, TASK-018 | ✅ **completata** |
| **PHASE 6 — Execution** | `autonomous/phase-6-execution` (da phase-5) | TASK-019, TASK-020 | ✅ **completata** |
| **PHASE 7 — Operator console** | `autonomous/phase-7-operator-console` (da phase-6) | TASK-021, TASK-022 | ✅ **completata** |

**Il blocco è chiuso. L'agente è fermo per la Human Final Review: `FINAL_HANDOFF.md`.** L'ultimo
integration branch, `autonomous/phase-7-operator-console`, contiene tutte e cinque le fasi in linea
retta sopra `master` (`d166870`); un solo fast-forward le integrerebbe tutte. **`master` non è stato
toccato.**

**Suite a fine blocco: 394 test verdi** — **329** Java (`cd backend && ./mvnw -B clean test`), **51**
AI Engine (`cd ai-engine && .venv/Scripts/python -m pytest`), **14** console (`cd frontend && npm test`),
tre job CI. **Stream: `V11`** (`V9` priority, `V10` `task_runs`, `V11` `agents.model`).
**Prossimo debito libero: `TD-41`** (`docs/DEBT_REGISTRY.md`). Avvio dell'intero stack:
`.\scripts\start-dev.ps1`.
**Live dev DB: `V3`**, verificato il 2026-09-23 e **non toccato**: gli smoke test girano su un
clone (`aicompany_smoke`, `CREATE DATABASE … TEMPLATE aicompany`).

### PHASE 3 in sintesi
- **TASK-013 — TD-04 chiuso** (ADR-013). Ogni `/api/**` richiede `Authorization: Bearer <token>`;
  token configurati per nome (`aicos.security.api-tokens.<nome>`), il nome è il principal. `401`
  dentro ADR-007 (`urn:ai-company-os:problem:unauthenticated`), identico per token assente e
  sbagliato, **prima** di `404` e `428`. Fail closed all'avvio. Solo `/actuator/health` pubblico.
  Verifica **per riflessione su ogni rotta**. Nessun test esistente modificato: il `MockMvc`
  condiviso porta il token dell'operatore.
- **TASK-014 — TD-11, R5, R7 chiusi.** CORS da origini dichiarate (no `*`), `ETag`/`Location`
  esposti, `If-Match` ammesso, niente *credentialed*; backend `dev` su `127.0.0.1`; `.env.*`
  ignorato.
- **Lezione sull'harness di mutazione**: il primo giro ha dato cinque «rossi» falsi perché il
  wrapper Maven non partiva. L'harness ora rifiuta un verdetto senza output di Surefire.

### PHASE 4 in sintesi
- **TASK-015 — TD-37 chiuso** (ADR-014). `POST /api/tasks/{id}/start|complete|stop|reopen`,
  `If-Match`. `TaskStatus` è il vocabolario, `TaskTransition` la macchina (quattro archi). Regole
  sull'entità: congelato → arco → (solo `start`) agente presente e attivo. Uscire da `IN_PROGRESS`
  non dipende mai dall'agente (D3 regge). Invariante nuovo **`IN_PROGRESS` ⇒ agente**, protetto
  finché TD-35 resta aperto: chi lo chiude deve rifiutare i task `IN_PROGRESS`. Apre **TD-38**.
- **TASK-016 — TD-36 chiuso** (e con esso TD-12). `PUT /api/tasks/{id}` (dettagli soltanto),
  `priority ∈ {LOW, MEDIUM, HIGH}` con censimento (`tasks/TASK-016/CENSUS.md`: solo `HIGH`/`LOW`,
  live DB `LOW`) e **`V9`**, `GET /api/tasks?status=`.
- Lezione: un test di concorrenza a barriera **non** forza l'interleaving (M7 sopravvissuta);
  sostituito da transazione esterna + latch.

### PHASE 5 in sintesi
- **TASK-017** (ADR-015). `ai-engine/`: FastAPI, contratto **v1** (`POST /v1/completions`,
  `GET /v1/models`, `GET /health`), token fra servizi a tempo costante, problem details
  `urn:ai-company-os:engine:problem:*`, correlation id, campi sconosciuti rifiutati, provider
  `echo` deterministico. Bind `127.0.0.1:8090`. Job CI `ai-engine` (Python 3.12).
- **TASK-018**. Provider `ollama` (locale, attivo per default, «non disponibile» se spento) e
  `anthropic` (SDK ufficiale, `claude-opus-5`, `fallbacks: "default"`, **spento senza
  `ANTHROPIC_API_KEY`**). Nessun test raggiunge rete o servizi a pagamento.
- **Smoke reale**: quattro modelli Ollama installati su questa macchina; completion reale con
  `ollama:llama3.2:3b` (13 s). Nessuna chiamata cloud eseguita.
- Il runtime a grafo (LangGraph o alternative) **resta non deciso**, come ADR-001 lo ha rinviato.

### PHASE 7 in sintesi
- **TASK-021** (ADR-017 §2). springdoc 3.1.1; `docs/api/openapi.json` tenuto **byte per byte** al
  codice da `OpenApiContractTest`; `/v3/api-docs` autenticato; `GET /api/engine/models`.
- **TASK-022** (ADR-017). `frontend/`: React 19 + TS + Vite, tipi **generati** dal contratto (la CI
  rifiuta una deriva), board a tre colonne, drawer del task (archi, routing, assegnazione, run con
  scelta del modello, output e prompt), agenti con modello, progetti, modelli dell'engine. `412`
  mai ritentato. `scripts/start-dev.ps1`. README riscritto: **TD-17, TD-18 chiusi**.
- **Verifica dal vivo** nel browser integrato: ha trovato che `/actuator/health` non aveva CORS
  (la console avrebbe segnato «down»); corretto e pinnato. Il login autenticato nel browser **non**
  è stato eseguito dall'agente (nessun token digitato in un campo): è il primo passo della review.

### PHASE 6 in sintesi
- **TASK-019** (ADR-016, `V10`). `POST /api/tasks/{id}/runs` (If-Match del task, `202`),
  `GET /api/runs/{id}`, `GET /api/tasks/{id}/runs`. Lancio col protocollo del task; `OPEN` →
  `IN_PROGRESS` via `START`; una run non finita per task; esecuzione **dopo il commit**, fuori
  transazione, executor limitato; ogni run finisce con un tipo; recupero `interrupted` all'avvio.
  **Una run riuscita non completa il task** (human in the loop). Apre **TD-39**, **TD-40**.
- **TASK-020** (`V11`). **TD-08 chiuso**: `MasterOrchestrator` e `POST /api/orchestrator` rimossi;
  routing lessicale sul registro (`GET /api/tasks/{id}/agent-suggestions`,
  `POST /api/routing/suggestions`); `agents.model`.
- **Primo smoke end-to-end reale**, su clone del DB live: task → agente con
  `ollama:llama3.2:3b` → run `SUCCEEDED` in 24 s con output reale. Ha trovato un falso positivo del
  routing (`post` ↔ `postgresql`), corretto e pinnato.

## Current phase
**Nessuna in corso.** Il blocco PHASE 3–7 è completo (sezione ▶ qui sopra); piani in
`.company-os/PHASE_{3..7}_PLAN.md`, sequenza in `ROADMAP_PHASES_3_7.md`.

*Storico:* PHASE 2 — Assignment (`PHASE_2_PLAN.md`), accettata e in `master` dal 2026-09-19.

## Current task
**Nessuna.** L'ultima è TASK-022. Fermo per la Human Final Review del blocco (`FINAL_HANDOFF.md`).

*Storico, precedente al blocco:* **TASK-012** (2026-09-19) ha affrontato i due debiti che precedevano PHASE 3, su richiesta umana:

- **TD-31 — CHIUSO.** Il cambiamento distruttivo è stato autorizzato esplicitamente da un umano,
  delimitato allo scope documentato. `V8` elimina `agents.active`;
- **TD-14 — CHIUSO il 2026-09-23.** Il remote è configurato
  (`https://github.com/Gabryx82/Ai-Company-OS.git`), `master` è stato pushato senza force e senza
  riscrivere storia (`14f70b3`, `origin/master` allineato, divergenza `0 0`), il workflow `CI` è
  **registrato e attivo** su GitHub, e la run **`35863517006` si è conclusa `success`**: 9 step su
  9 verdi su `ubuntu-latest`, `./mvnw -B clean test` incluso, in 1m26s, con l'artefatto
  `surefire-reports` caricato. **La CI ha girato davvero** — che è la sola evidenza con cui questo
  debito poteva chiudersi. Percorso completo, compresi i quattro fallimenti di autenticazione che
  lo hanno preceduto, in `tasks/TASK-012/EVIDENCE_TD14.md` §7-11.

*(Stato al 2026-09-19. Da allora TD-04 è chiuso da TASK-013 e TD-37 da TASK-015.)* **TD-04** e
**TD-37** restavano aperte: erano i due candidati di PHASE 3.

- **TD-04 — autenticazione.** Già indicato come primo candidato di PHASE 3 da `PHASE_2_PLAN.md`
  §2. Oggi non c'è niente, e ogni endpoint aggiunto è superficie. Costo noto: cambierebbe ogni
  test di API;
- **TD-37 — le transizioni di `status`.** È ciò che sblocca **TD-08** e il Planner: un task sa
  dove sta, di chi è e in che stato è, ma **non può cambiare stato**, e un orchestratore che non
  muove il lavoro non ha niente da orchestrare.

Argomenti raccolti in `tasks/TASK-011/HANDOFF.md`.

## Last completed task

**TASK-012 — TD-31 chiuso, TD-14 bloccato su evidenza** (2026-09-19).

Due debiti indipendenti, due esiti diversi, commit separati. Suite **219 → 223**, schema
**`V7` → `V8`**. Nessun debito nuovo.

### TD-31 — chiuso

`agents.active BOOLEAN` diventa `agents.status VARCHAR(32)` con `agents_status_check`, e la
colonna booleana **viene eliminata**. È la **prima migrazione distruttiva** dello stream, ed era
hard stop #3 del charter: sciolto da una decisione umana esplicita il 2026-09-19, delimitata allo
scope documentato.

**Chiuso perché qualcuno ha deciso, non perché il costo fosse cresciuto.** ADR-008 §2 aveva
*sospeso* questo cambiamento, non lo aveva sbagliato, e ADR-012 lo dice invece di riscrivere la
storia.

| Decisione | Contenuto |
|---|---|
| **Il backfill è una biiezione** | `TRUE`↔`ACTIVE`, `FALSE`↔`INACTIVE`. Totale e iniettiva in entrambe le direzioni; `active` è `NOT NULL` da `V1`, quindi **nessun terzo caso da decidere e nessun mapping da inventare**. Irreversibile è la *forma*, non il contenuto |
| **Il contratto pubblico non cambia di un byte** | `AgentResponse` porta `active` **e** `status` dalla TASK-007: `V8` inverte quale dei due è reale. JSON identico, `?active=` resta booleano |
| **`INACTIVE`, non `ARCHIVED`** | Invariato da ADR-008 §2. TD-31 unifica la **forma** — enum chiuso più `CHECK` — non il vocabolario |
| **Niente indice, niente terzo stato, `active` resta nella risposta** | Fuori scope, elencati in ADR-012 §7 |

**La conseguenza che il piano non aveva previsto**: il dev seed **nomina** la colonna che `V8`
elimina, quindi su un database nuovo sarebbe fallito — e modificarlo cambia il checksum, che
Flyway valida, quindi ogni database già seminato (**compreso quello di questa macchina**) avrebbe
rifiutato di avviarsi in `dev`. `V4` aveva evitato la stessa famiglia di collisione con un
`DEFAULT`, e qui quella via non esiste: un `DEFAULT` non aiuta un `INSERT` che nomina una colonna
scomparsa. Risolto con `repair()` in `DevSeedFlyway.apply` — solo metadati, e **da non copiare
sullo stream dello schema**, dove il fallimento rumoroso è voluto (ADR-012 §5).

**Verificato sui dati reali, non solo sui fixture.** Il database di sviluppo di questa macchina
(a `V3`) è stato **clonato** per non toccarlo, e sul clone è stata avviata l'applicazione: `V1→V8`
applicate, `active` assente, 3 agenti `t` → 3 `'ACTIVE'`, nessuna riga persa, checksum del seed
riparato senza ri-eseguirlo, avvio riuscito (quindi Hibernate `validate` passa), e
`GET /api/agents` che risponde `{"active":true,"status":"ACTIVE"}`. Clone eliminato; **il database
reale è ancora a `V3`**.

**Due mutazioni, entrambe rosse**, con albero verificato pulito prima e dopo ciascuna. La prima
conta: un backfill che scrive `'ACTIVE'` incondizionatamente rende rosso il test — che è il motivo
per cui quel test semina **entrambi** i valori, perché con soli agenti attivi sarebbe passato e la
biiezione sarebbe stata affermata senza essere verificata. La seconda riproduce esattamente il
fallimento che il database reale avrebbe avuto senza `repair()`.

### TD-14 — aperto, e il blocco è un dato, non una decisione

Quattro fonti verificate, tutte negative: **nessun remote** configurato né mai esistito (nessun
`refs/remotes`, nessun `FETCH_HEAD`, `.git/config` senza sezione `[remote]`); **nessun URL, owner
o nome di repository** in alcun file tracciato o non tracciato; **`gh` non installato**; **nessuna
CI** presente in alcuna forma.

Le sole occorrenze di «origin» nel repository sono la parola italiana **«origine»** nella prosa su
CORS e sui lock, più `originalTag` in `Precondition.java`.

**Manca esattamente l'URL del remote**, e non è deducibile. Non è stato scritto nemmeno un
workflow CI: scegliere GitHub Actions presuppone GitHub, e la piattaforma è parte della
destinazione mancante.

Artefatti: `tasks/TASK-012/*`, `docs/adr/ADR-012-agent-lifecycle-unification.md`.

## Task precedenti

**TASK-011 — Registro del debito e documentazione operativa** (2026-09-19).

Livello 6, ultima task di PHASE 2. Suite **216 → 219**. **Nessuna modifica al codice
applicativo, nessuna migrazione**, e nessun debito nuovo: prossimo id libero **`TD-38`**.

**La collisione di identificatori era reale e ha una forma precisa.** Cinque `TD-NN` significano
cose diverse nei due registri — **`TD-14`, `TD-19`, `TD-20`, `TD-21`, `TD-22`** — mentre
`TD-01`…`TD-13` e `TD-15`…`TD-18` coincidono. È questo a renderla insidiosa: chi ne verifica due o
tre conclude che il problema non esista. «`TD-20` è chiuso» era vero in uno spazio e falso
nell'altro, e niente diceva quale si stesse usando.

**Risolta in modo additivo, non rinumerando.** Rinumerare i vivi romperebbe ogni riferimento in
ADR, artefatti e commit; rinumerare l'audit riscriverebbe ciò che TASK-000 **osservò**, e uno
snapshot che si aggiorna non è più uno snapshot. `docs/DEBT_REGISTRY.md` mappa i due spazi per
intero e dichiara autoritativo il vivo; `docs/audit/TECHNICAL_DEBT.md` lo dice sulla prima
schermata.

**La decisione più difficile è stata non dichiarare chiusi otto debiti.** `TD-01`, `TD-02`,
`TD-03`, `TD-05`, `TD-06`, `TD-09`, `TD-10`, `TD-16` esistono solo nell'audit e diversi sono con
ogni evidenza risolti — H2 rimosso, Flyway padrone dello schema, DTO, 219 test dove l'audit
contava zero. **Non marcati chiusi**: «con ogni evidenza risolto» *è* «il codice sembra diverso»,
che il charter vieta come criterio. Il registro dice «non rivalutato qui», che è vero, e la
rivalutazione è registrata come task successiva.

**`docs/RUNNING.md` conteneva due istruzioni false, non due lacune**, ed è la differenza che
conta — una documentazione incompleta fa perdere tempo, una falsa fa sbagliare:

1. «*La prossima migrazione di schema si chiama `V2__...sql`*», con lo stream a **`V7`**: chi la
   seguiva creava una migrazione che **Flyway rifiuta**. Sostituita dalla regola, dal valore di
   oggi e dal **comando per leggerlo**, perché è proprio un valore scritto a mano che è andato
   stantio;
2. «*deve contenere **solo** `1 | V1__create_agents_and_tasks.sql`*», vero appena dopo TASK-001A e
   falso da `V2`. Ciò che si verifica è l'**assenza** della riga legacy `1000`.

Entrambe portano una nota che dice cosa affermavano prima: qualcuno può averci agito.

Documentati **18 endpoint invece di 3**, e messo **per primo** ciò che mancava del tutto: **ogni
mutazione richiede `If-Match`**, quindi un `428` è un header mancante e non un server rotto.

**Un test per un problema di documentazione**, perché il charter chiede una verifica che fallisca
se il difetto torna: `DebtRegistryConsistencyTest` rende rossa la suite se un identificatore
dell'audit smette di comparire nel registro. È l'unico test che legge file fuori dal proprio
modulo, e **fallisce invece di saltare** quando non li trova — una guardia sulla documentazione
che passa perché non ha trovato la documentazione è peggio di nessuna guardia.

Artefatti: `tasks/TASK-011/*`, `docs/DEBT_REGISTRY.md`.

## Task precedenti di PHASE 2

**TASK-010 — Vocabolario chiuso di `Task.status`** (2026-09-19).

Chiude **la metà `status` di TD-12**, apre **TD-36** e **TD-37**. Suite **201 → 216**, schema
**`V7`**. Da oggi un task sa **in che stato è** in un modo che qualcosa può verificare.

**Il censimento ha cambiato la natura della task, e viene prima di tutto il resto.** TASK-009
aveva avvertito che stringere `status` non è additivo se i dati contengono valori fuori
vocabolario, e che in quel caso la domanda tocca gli hard stop #2 e #3. Contare prima è ciò che ha
reso la task eseguibile in autonomia. Cinque fonti — schema, dev seed, fixture, database locale,
storia git — **un solo valore (`OPEN`), zero righe da trasformare, nessun hard stop**.
Riproducibile con i comandi in `tasks/TASK-010/CENSUS.md`.

Tre fatti che il censimento ha trovato e che nessun documento diceva:

1. **il database di sviluppo reale è a `V3`**, non alla testa dello stream: non ha mai visto `V4`,
   `V5` né `V6`. Lo stato lo diceva in modo impreciso, ed è corretto più sotto;
2. **`Task.status` non ha alcun percorso di mutazione** — un solo scrittore, la creazione, e
   nessun ramo del codice che legga il valore per decidere. Questo ha ristretto lo scope: la task
   chiude il **vocabolario**, non il ciclo di vita, e lo dichiara (**TD-37**);
3. **`MigrationStreamTest` era già l'upgrade test di `V6 → V7`**, scritto da TASK-006 per
   migrazioni che allora non esistevano. È un vincolo reale sulla scelta: un vocabolario che
   avesse escluso `OPEN` lo avrebbe reso rosso a ogni coppia.

| Decisione | Contenuto |
|---|---|
| **Il vocabolario è `OPEN`, `IN_PROGRESS`, `DONE`** | `OPEN` preservato **verbatim**. Esclusi e dichiarati `BLOCKED`, `CANCELLED`, `IN_REVIEW`, `DRAFT`, `PAUSED` — plausibili, nessuno richiesto: è testualmente l'argomento di ADR-004 §2 |
| **Un vocabolario non è una macchina a stati** | Nessuna transizione. `DONE` è legale alla creazione, e un test lo pinna, così una task futura non può aggiungere «il lavoro comincia aperto» credendo di fare pulizia |
| **Il campo della request resta `String`** | Tipizzarlo come enum farebbe fallire Jackson → `malformed-request`, «*the request body could not be read*», che è **falso**, e senza `errors`. Contratto di TASK-005 **invariato**: nessun `type` nuovo, `ApiExceptionHandler` intatto |
| **Case-sensitive** | `"open"` è fuori quanto `"banana"`. Scelta **opposta** a ADR-008 sui nomi degli agenti: un nome lo digita una persona, uno stato lo manda un programma |
| **Tre guardie, non due** | ADR-004 §2 ne voleva due; fra client e dominio c'è un livello che i progetti non avevano |
| **`V7`** | Non additiva **per costruzione** — applica un vincolo a dati esistenti — ma additiva **su questi dati**: zero righe lette, scritte o riscritte |

**Quattro mutazioni, tutte rosse, e due hanno insegnato qualcosa.** Albero verificato pulito prima
di ogni mutazione e dopo ogni revert, per la lezione di TASK-009.

- **M2** (tolto il `CHECK`) ha **riprodotto empiricamente** la previsione di ADR-004 §2: un `'open'`
  scritto in SQL grezzo entra in tabella e fa poi fallire **la lettura di ogni task** —
  `No enum constant ... TaskStatus.open` — dentro un `@BeforeEach`, cioè il più lontano possibile
  dalla causa;
- **M4** (`ORDINAL` invece di `STRING`) è più forte di quanto il javadoc dichiarasse: il contesto
  Spring **non parte**, perché `validate` rifiuta un mapping ordinale su colonna `varchar`.

**La review ha trovato un test che prometteva più di quanto verificasse**, e la mutazione è ciò
che lo ha smascherato: `theCheckConstraintDeclaresTheSameSetAsTheEnum` confrontava il vincolo con
una costante, non con l'enum, ed è rimasto **verde** mentre l'enum veniva allargato. Corretto il
**nome**, non il disegno — confrontare ogni guardia con un insieme scritto indipendentemente è più
forte che confrontarle fra loro, perché il confronto diretto passerebbe il giorno in cui qualcuno
cambia entrambe e non decide nessuna delle due.

**Rotture dichiarate**: `status` fuori vocabolario passa da `201` a `400`; `"open"` idem; un
`INSERT`/`UPDATE` SQL fuori vocabolario è rifiutato dal database; `V7` **fallisce** su un database
che contenesse valori fuori vocabolario — comportamento voluto, precedente esatto di `V4` con
`agents_name_unique_idx`, e il test verifica anche che la riga resti **intatta**. **JSON
invariato.**

Artefatti: `tasks/TASK-010/*`, `docs/adr/ADR-011-task-status-closed-vocabulary.md`.

**TASK-009 — Assegnazione `Task` → `Agent`** (2026-09-17).

Risolve **TD-13** (numerazione dell'audit), apre **TD-34** e **TD-35**. Suite **179 → 201**, schema
**`V6`**. Da oggi il sistema sa dire **chi lavora su che cosa**.

**Le tre domande di dominio, e dove il dominio ha divergito.** Nessuna risposta copiata da
`Task` → `Project`:

| # | Domanda | Risposta |
|---|---|---|
| **D1** | Assegnare a un agente disattivato? | **`409`** — stesso esito, **argomento diverso**: non contenimento ma responsabilità, un'obbligazione che nessuno può assolvere |
| **D2** | Cambiare agente a un task in un progetto archiviato? | **`409`**. Prima verifica della frase generale di ADR-006 §2, e regge — ma «per costruzione» **non è automatico**: vale perché la guardia è stata estratta in `Task.requireNotFrozen()` e il percorso nuovo la chiama |
| **D3** | Disattivare un agente fa qualcosa ai suoi task? | **Nessuna scrittura sui figli, e la regola derivata è l'OPPOSTA di quella dei progetti.** Un task il cui agente è inattivo **non è congelato**: congelarlo lo intrappolerebbe con chi non può eseguirlo, proprio quando serve riassegnarlo |

**Il lock graph è stato ridimostrato, non esteso.** **L5′**: `tasks` → `projects` → `agents`. Tre
archi, ordinamento topologico, e i tre archi **assenti** verificati uno per uno — perché è quella
la metà che può essere sbagliata. Risultato:

> L'aciclicità è **una conseguenza** di ADR-006 §1 e di **D3**, non una proprietà indipendente.

L'alternativa scartata (D3bis: far fallire `deactivate` finché ha lavoro) avrebbe introdotto
`agents → tasks` e **chiuso un ciclo**. Il dominio e la concorrenza rispondono la stessa cosa.

**Una sola versione.** `agent_id` è una colonna di `tasks`: assegnare consuma l'ETag del task, e
chi cambia il progetto invalida il tag di chi sta per cambiare l'agente — `200` e `412`, non due
`200`. Nessun versionamento separato dell'associazione. `P3` nell'altra direzione: assegnare **non**
muove `agents.version`.

**Quattro mutazioni**, tutte rosse — ma **due hanno dovuto essere riparate prima di provare
qualcosa**, ed è la parte che vale la pena leggere (`tasks/TASK-009/ARTIFACT.md` §5 e §7):

- quella su P4 fermava la build invece di raggiungere il proprio test. Una mutazione che non
  compila non è una mutazione;
- quella sul lock del progetto **non sapeva revertirsi** — sostituiva un blocco con la stringa
  vuota — e ha lasciato l'albero mutato. Ne sono seguite **due diagnosi sicure e sbagliate** di
  un'implementazione che era corretta, la seconda costruita su un log SQL vero ma raccolto da un
  albero corrotto.

La lezione è registrata perché tornerà: quando un test fallisce su codice che si crede pulito, la
prima ipotesi da verificare è **che l'albero sia pulito davvero** — non che il test sia instabile,
non che il framework si comporti in modo esotico. L'harness adesso si rifiuta di indovinare.

Corretto lungo la strada e tenuto: `awaitOrFail` distinto da `awaitAtMost` nei test di concorrenza
(un'attesa limitata dove serve un requisito produce sia rossi falsi sia **verdi falsi**), anche in
`PreconditionConcurrencyTest`, che aveva ereditato il difetto da TASK-008.

Nessuna rottura di contratto: tutto additivo.

Artefatti: `tasks/TASK-009/*`, `docs/adr/ADR-010-task-agent-assignment.md`.

**TASK-008 — Optimistic concurrency nel contratto HTTP** (2026-09-16).

Chiude **TD-28** e **TD-30**, apre **TD-32** e **TD-33**. Suite **158 → 179**, schema **`V5`**.

I lock di ADR-006 serializzano ma non rilevano. Adesso il sistema fa entrambe le cose, con due
meccanismi che **non si sostituiscono**: L0–L7 è consistenza interna, `ETag`/`If-Match` è intento
stantio del client. Il lock rende **atomico** il confronto, il confronto rende **visibile** la
staleness.

| Decisione | Contenuto |
|---|---|
| **`@Version` è il contatore, non il rilevatore** | Dopo l'attesa su `PESSIMISTIC_WRITE` l'entità è caricata **già alla versione nuova**, quindi `OptimisticLockException` non arriva mai. **Nessun handler per essa**, e non va aggiunto: sarebbe un `412` che non scatta |
| Protocollo **P0–P4** | `If-Match` obbligatorio su ogni mutazione di risorsa esistente; confronto **dentro la transazione, dopo il lock, prima delle regole**; la versione conta la propria riga; clausola di chiusura ereditaria |
| `428` / `412` / `400` | Assente / stantio / illeggibile o `*`. Tre `type` nuovi, dentro ADR-007 senza eccezioni |
| Percorso canonico | **`GET /api/tasks/{id}`**, introdotto perché non esisteva. `ETag` anche su creazioni e mutazioni. Mai sui listati (TD-33), mai nei corpi JSON |
| `V5` additiva | `version BIGINT NOT NULL DEFAULT 0` su tutte e tre le tabelle. Il `DEFAULT` è la lezione di `V4` |
| Tutte e tre le risorse | Non solo le due rotte del debito: una precondizione con buchi non è una precondizione (L7 è il precedente) |
| **P4 eseguibile** | `PreconditionCoverageTest` pinna l'insieme dei write path: aggiungerne uno obbliga a decidere sulla precondizione |

**Rotture dichiarate.** Ogni mutazione senza `If-Match` passa da `200` a `428` — la rottura più
grande della fase, e deliberata: con `If-Match` facoltativo il debito non si chiude, si rende
*evitabile*. Il `PUT` idempotente con tag stantio passa da `200` a `412`. `DELETE /api/tasks/{id}`
da `404` a `405`.

**Una rottura che il piano non aveva previsto**, trovata dai test di concorrenza di TASK-004 e
TASK-007: il **perdente di due transizioni concorrenti vede `412` dove vedeva `409`**. La
transizione illegale resta ciò che riceve un chiamante **aggiornato**, esercitata sequenzialmente.
ADR-009 §8.

**La verifica per mutazione ha smentito due affermazioni dei documenti**, e sono state corrette, non
difese:

1. spostare il confronto **sotto** le regole **non** rende rosso il caso idempotente —
   `Task.assignTo` esce presto da **sé**, non dal service. Il test di I-3 non discriminava niente.
   Ciò che la posizione decide è **quale rifiuto** riceve un chiamante stantio, e c'è un test nuovo
   che lo asserisce;
2. spostarlo **sopra** il lock non perde la riga: Hibernate solleva `StaleObjectStateException`.
   Perde la **risposta** — `500` invece di `412`. Registrato in ADR-009 §2.3 come rete, non come
   meccanismo.

Artefatti: `tasks/TASK-008/*`, `docs/adr/ADR-009-optimistic-concurrency-http-contract.md`.

## Task di PHASE 1 (dettaglio)

**TASK-007 — Agent Registry** (2026-09-14).

`Agent` era la tabella più vecchia e la meno curata: cinque colonne, nessun timestamp, nessuna
unicità, nessuna validazione, un solo `GET`. Adesso è un registro allo stesso standard di
`Project`: `POST`, `GET [?active=]`, `GET /{id}`, `PUT /{id}`, `activate`, `deactivate`, nessun
`DELETE`.

Le decisioni di ADR-004 sono state **applicate, non ridiscusse**. Una sola divergenza, e
dichiarata: il ciclo di vita resta su `boolean active` invece dell'enum chiuso di `Project`,
perché unificarlo richiederebbe di eliminare una colonna — migrazione irreversibile che il
charter mette dietro una decisione umana quando un'alternativa ragionevole esiste, e esiste.
**TD-31.** La divergenza è nascosta al client e non al registro: `AgentResponse` espone
`status` **derivato**, e un test asserisce che non sia una colonna.

`V4` è additiva, e ha richiesto una cosa che il piano non aveva previsto: un `DEFAULT now()` sui
timestamp, perché il seed di sviluppo è una migrazione già applicata che inserisce senza
fornirli, e modificarla ne cambierebbe il checksum. **Trovato dalla suite, non dal progetto.**

Il protocollo di lock si applica perché **L7 dice che si applica**: due `deactivate` concorrenti
sono un `200` e un `409`, cioè il difetto di TD-19 che arriva già chiuso sulla terza entità.

Artefatti: `tasks/TASK-007/*`, `docs/adr/ADR-008-agent-registry.md`.

**TASK-006 — Migration Test Coverage** (2026-09-14).

Chiude **TD-22** e **TD-23**. Un solo file di test toccato, nessun cambiamento
all'applicazione.

TD-22 chiedeva il test incrementale `V1 → V2`. È stato scritto invece un test su **ogni coppia
consecutiva** che Flyway risolve: porta un database a `N`, ci scrive righe, migra a `N+1` e
verifica le tre cose che una migrazione può distruggere senza fallire — una riga sparisce, una
tabella sparisce, viene applicato più del previsto. Il test specifico avrebbe chiuso il buco di
ieri riaprendo quello di domani; leggere le coppie dallo stream copre `V4` il giorno in cui
esiste, senza che nessuno se ne ricordi.

TD-23 mantiene la decisione — l'elenco delle tabelle è dichiarato a mano, perché una migrazione
che crea una tabella è un fatto che qualcuno deve affermare — e perde la frase che descriveva il
test come capace di adattarsi da solo. Il difetto era la spiegazione, non l'elenco.

Verifica per mutazione: una `V3` che cancella righe, e una che elimina `agents`, rendono rosso
il test.

Artefatti: `tasks/TASK-006/*`.

**TASK-005 — Uniform Error Contract** (2026-09-14).

Chiude **TD-07**, e con lui **TD-20**, **TD-21**, **TD-27** e **TD-29**. L'API parlava tre
dialetti di errore; adesso ne parla uno.

| Decisione | Contenuto |
|---|---|
| Un advice globale | `ApiExceptionHandler`, che estende `ResponseEntityExceptionHandler`: le eccezioni che Spring solleva prima del nostro codice entrano nel contratto senza essere rincorse a ogni upgrade. Erano esattamente TD-20 e TD-27 |
| **`type` stabile** | `urn:ai-company-os:problem:<slug>`, enumerato in `ApiProblem`. **Il `type` è il contratto, il `title` è la prosa**: prima l'unico modo di distinguere due `409` era confrontarne la frase inglese |
| Validazione | `errors` con l'elenco dei campi, **ovunque** |
| Catch-all | `500` con `detail` fisso e stack loggato per intero: nessun nome di tabella, SQL o percorso raggiunge il chiamante |
| Locking | Prende quella forma. **Nessuna policy**: niente timeout, retry o `503`. ADR-006 §7 resta valida, e TD-29 era la forma, non la policy |
| I due advice di modulo | Rimossi: un contratto con due punti di definizione dipende da una precedenza che nessuno ha scelto |

**Rottura dichiarata**: cambiano i corpi di errore di `/api/agents` e delle risposte
preesistenti di `/api/tasks`. **Nessuno stato HTTP cambia.**

Artefatti: `tasks/TASK-005/*`, `docs/adr/ADR-007-uniform-error-contract.md`.

**TASK-004 — Archival Consistency & Project Lock Protocol** (2026-09-14).

Chiude l'incoerenza che ADR-005 §4 aveva lasciato dichiarata: l'archiviazione di un progetto
rifiutava lavoro nuovo ma non proteggeva il lavoro che conteneva — un task usciva da un
progetto archiviato con un `200`.

| Decisione | Contenuto |
|---|---|
| Consistenza **derivata** | `archive`/`restore` scrivono **una sola riga**, la propria. Zero `UPDATE` su `tasks`. Cambia la regola, non il dato. `restore` è l'inverso esatto **per costruzione**: non c'è niente da ricordare |
| Congelamento | Un task il cui progetto è `ARCHIVED` non si sposta → `409`. Letture invariate. `PUT` verso lo **stesso** progetto archiviato → `200` no-op |
| Protocollo **L0–L7** | La riga del task esclusiva **prima** di leggerne l'associazione (L0); le righe dei progetti da cui la decisione dipende, condivise (L2–L4); ordine globale `tasks` → `projects` per id crescente (L5); le letture non bloccano (L6); il protocollo è universale (L7) |
| Nessuna migrazione | Schema fermo a **`V3`**. Nessuna colonna, nessun vincolo, nessun `@Version` |
| Contratto invariato | `TaskResponse` identico a TASK-003 |

**Unico cambio di contratto osservabile**: `PUT /api/tasks/{id}/project` passa da `200` a `409`
quando **sposta** un task fuori da un progetto archiviato.

Un test ha riprodotto, prima che il codice esistesse, un bypass della regola per **staleness**
che i soli lock sui progetti non intercettano — lo scrittore stantio tiene `{A, B}` e l'archive
tocca `C`, insiemi disgiunti. Da lì **L0**, approvato il 2026-09-14.

Artefatti: `tasks/TASK-004/*`, `docs/adr/ADR-006-archival-consistency-and-project-serialization.md`.

- **TASK-003 — Task → Project Association Foundation** (merged in `master`, 2026-09-12).
  Prima relazione persistente: `Task` → `Project`, chiave esterna da `V3`, mapping JPA
  unidirezionale, API di assegnazione e listato per progetto. ADR-005.
- **TASK-002 — Core Domain Model & Project Registry Foundation** (merged in `master`,
  2026-09-11). `Project`, tabella da `V2`, ciclo di vita esplicito, archiviazione al posto
  della cancellazione. ADR-004.
- **TASK-001 / TASK-001A — Reproducible Persistence Foundation** (merged in `master`).
  PostgreSQL, schema di proprietà di Flyway, seed dev in stream separato. ADR-001, ADR-002,
  ADR-003.

## Stato del sistema

- Database: **PostgreSQL 17** via `docker-compose.yml`, volume `aicompany_postgres_data`.
- Schema: di proprietà di **Flyway**, e **due numeri diversi che non vanno confusi**:

  | | Valore | Che cos'è | Come si verifica |
  |---|---|---|---|
  | **Migration stream** (il codice) | **`V8`** | La migrazione più alta che esiste in `backend/src/main/resources/db/migration`. La prossima da scrivere è `V9` | `ls backend/src/main/resources/db/migration` |
  | **Live dev DB** (il volume locale) | **`V3`** | Le migrazioni realmente applicate al database di sviluppo `aicompany_postgres_data`. **Non ha mai visto `V4`, `V5`, `V6`, `V7`** | `docker exec aicompany-postgres psql -U aicompany -d aicompany -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank;"` |

  Sono **indipendenti per costruzione**: il primo è una proprietà del repository, il secondo di
  un'installazione. Ogni installazione ha il proprio, e «schema a `V8`» **senza qualificatore
  significa lo stream**, mai un database. Scoperto dal censimento di TASK-010, che trovò lo stato
  che dichiarava `V6` per un database che era — ed è tuttora — a `V3`. Riverificato da TASK-012 il
  2026-09-19: stream `V8`, dev DB `V1,V2,V3`.

  Al primo avvio in profilo `dev` le **cinque** migrazioni mancanti si applicheranno in ordine.
  **Verificato su un clone del database reale** da TASK-012, compresa `V8`, che è distruttiva.
  `V7` passerà, perché l'unica riga di `tasks` ha `status = 'OPEN'`. **Non serve
  `docker compose down -v`.** Hibernate in `validate`.
- Tabelle: `agents`, `tasks`, `projects`. `tasks.project_id` nullable con FK senza `ON DELETE`.
  Da `V4`: `agents.created_at` / `updated_at` (`TIMESTAMPTZ`, `NOT NULL`, `DEFAULT now()`) e
  indice unico `agents_name_unique_idx` su `lower(name)`.
  Da `V5`: `version BIGINT NOT NULL DEFAULT 0` su **tutte e tre** le tabelle.
  Da `V6`: `tasks.agent_id` nullable con FK senza `ON DELETE`, e `tasks_agent_id_idx`.
  Da `V8`: `agents.status VARCHAR(32) NOT NULL` con `agents_status_check`, e **`agents.active`
  eliminata** — la **prima migrazione distruttiva** dello stream, autorizzata da una decisione
  umana (ADR-012). Backfill biiettivo, zero righe perse.
  Da `V7`: `tasks_status_check CHECK (status IN ('OPEN','IN_PROGRESS','DONE'))` — la prima
  migrazione dello stream che **non è additiva per costruzione**, perché applica un vincolo a dati
  esistenti. Additiva su questi dati: zero righe lette, scritte o riscritte (`tasks/TASK-010/CENSUS.md`).
- **Due registri di dominio** con ciclo di vita esplicito, e **dalla stessa forma** da `V8`:
  `Project` (enum `ACTIVE`/`ARCHIVED`) e `Agent` (enum `ACTIVE`/`INACTIVE`), entrambi con un
  `CHECK` nel database. La divergenza booleano/enum era **TD-31**, chiusa da TASK-012.
  `AgentResponse` continua a esporre **sia** `active` **sia** `status`, e `?active=` resta un
  booleano: è contratto pubblico, e `V8` ha invertito quale dei due è derivato, non la risposta.
- **`Task.status` ha un vocabolario chiuso**: `OPEN`, `IN_PROGRESS`, `DONE` (`TaskStatus`), imposto
  in **tre** punti — vincolo Jakarta sulla request, `@Enumerated(STRING)` sull'entità,
  `tasks_status_check` nel database. **È un vocabolario, non una macchina a stati**: nessuna regola
  di transizione esiste, e `DONE` è legale alla creazione (ADR-011 §3). **Non esiste alcun percorso
  che muti lo `status` di un task esistente** → TD-37. `Task.priority` resta una stringa libera →
  TD-36.
- Seed di sviluppo: stream Flyway separato (`db/dev/V1`), profilo `dev` (ADR-003).
- Profili: `dev` (default), `test` (Testcontainers), `prod`.
- **Concorrenza**: protocollo di lock L0–L7 su `tasks` e `projects` (ADR-006 §4). Lock
  bloccanti ordinari: nessun `lock_timeout`, nessuna policy di retry, nessun `503`.
- Contratto di errore: **uno solo** (ADR-007). Ogni risposta di errore è un `ProblemDetail`
  con `type` stabile `urn:ai-company-os:problem:<slug>`, enumerato in `ApiProblem`. Un advice
  globale, `ApiExceptionHandler`. Nessuna policy di timeout o retry.
- **Due relazioni**: `Task` → `Project` e `Task` → `Agent`, entrambe unidirezionali, entrambe
  nullable, entrambe con FK senza `ON DELETE`. Un task sa dove sta e di chi è.
- **Concorrenza**: protocollo di lock **L0–L7** con ordine globale **L5′** su tre classi di righe,
  `tasks` → `projects` → `agents` (ADR-006 §4, ADR-010 §3). L'aciclicità è **dimostrata** e
  **condizionata**: regge finché `archive`/`restore` non scrivono task e finché il ciclo di vita
  di un agente non dipende dai suoi task.
- **Concorrenza ottimistica nel contratto HTTP**: protocollo **P0–P4** (ADR-009). `If-Match`
  obbligatorio su ogni mutazione di risorsa esistente, confrontato **dentro la transazione, dopo
  il lock esclusivo, prima delle regole**. `@Version` è il contatore, non il rilevatore: nessun
  handler per `OptimisticLockException`, e non va aggiunto.
- **A fine blocco PHASE 3–7: 329 test Java, 51 engine, 14 console.** Le righe di questa sezione
  descrivono il sistema fino a TASK-012; per ciò che il blocco ha aggiunto — sicurezza, ciclo di
  vita, engine, run, console — vale la sezione ▶ in cima e gli ADR-013…017.
- Test (fino a TASK-012): **223** (erano 109 in `master`, 158 a fine PHASE 1, 201 dopo TASK-009, 216 dopo
  TASK-010, 219 dopo TASK-011). `./mvnw -B clean test` → BUILD SUCCESS, **in locale e in CI**.
- H2 rimosso.

## Stato Git (verificato il 2026-09-20)

- **Remote**: `origin` → `https://github.com/Gabryx82/Ai-Company-OS.git`, configurato il
  2026-09-20 su autorizzazione umana esplicita, **pushato il 2026-09-23**. Repository pubblico,
  di proprietà di `Gabryx82`, default branch `master`.
- **CI attiva**: GitHub Actions, `.github/workflows/ci.yml`, esegue `./mvnw -B clean test` a ogni
  push e pull request. Prima run verde: `35863517006`.

- **`master`**: **`6dc5989`**. Era `d5ff121`; ci sono arrivati **41 commit** con due fast-forward
  consecutivi, autorizzati dalla review umana del 2026-09-19.
  - `d5ff121` → `0a35ac0` (PHASE 1), poi `0a35ac0` → `6dc5989` (PHASE 2);
  - **nessun merge commit prodotto**: `git log --merges d5ff121..master` è vuoto. L'unico merge
    commit della storia, `e8d0286`, è di TASK-001 (2026-09-11) ed era già in `master`;
  - `d5ff121` resta raggiungibile: **nessuna storia riscritta**.
- **Integration branch di PHASE 2**: `autonomous/phase-2-assignment`, creato da
  `autonomous/phase-1-foundations` (`0a35ac0`). Contiene il piano di fase, TASK-008, TASK-009 e
  TASK-010 e TASK-011. **HEAD: l'ultimo commit di questo branch** — deliberatamente non scritto come hash,
  perché il commit che aggiorna questa sezione sposta l'HEAD che la sezione dichiara, e TASK-004
  dovette correggerlo una volta (`1ddeee3`). L'ultimo commit di *lavoro* di TASK-010 è `81d350d`;
  quelli successivi su questo branch sono aggiornamenti di stato.
- **Integration branch di PHASE 1**: `autonomous/phase-1-foundations`, HEAD **`0a35ac0`**, fermo.
- Branch di lavoro integrati in fast-forward: `task-004-archival-consistency`,
  `task-005-uniform-error-contract`, `task-006-migration-test-coverage`,
  `task-007-agent-registry`, `task-008-optimistic-concurrency`,
  `task-009-task-agent-assignment`, `task-010-task-status-vocabulary`,
  `task-011-debt-registry-and-docs`.
- **`master` è pushato su `origin`** dal 2026-09-23 (`14f70b3`), senza force e senza riscrivere
  storia. `origin/master` allineato a HEAD, upstream impostato, ed è il **default branch** del
  repository remoto. Gli integration branch e i branch di task **non** sono stati pushati: restano
  marcatori locali.
- Storia lineare, mai riscritta.

Commit di TASK-010, ora nell'integration branch:

| Hash | Contenuto |
|---|---|
| `472b6dd` | `docs(task-010)` — il censimento, **prima** di qualunque decisione |
| `33e3160` | `test(task)` — la stringa libera riprodotta, **6 deliberatamente rossi** |
| `4b275ce` | `feat(task)` — enum, vincolo, `V7`; le tre guardie |
| `05f8b1d` | `test(task)` — i due rilievi MEDIUM della review, corretti |
| `81d350d` | `docs(task-010)` — chiusura, e tre correzioni a ciò che il repository diceva di sé |

Commit di TASK-004, ora nell'integration branch:

| Hash | Contenuto |
|---|---|
| `f3730af` | `docs(task-004)` — scope approvato |
| `add5cd9` | `test(project)` — le due race, deliberatamente rosse sulla baseline |
| `430b001` | `test(project)` — il bypass per staleness, riprodotto |
| `934a954` | `docs(governance)` — Autonomous Project Mode |
| `6d0f23d` | `docs(task-004)` — L0, ordine globale, TD-30 ristretto |
| `736a22d` | `feat(project,task)` — implementazione |
| `77c2071` | `test(project,task)` — protocollo, regola, ragionamento |
| `b1274d1` | `docs(task-004)` — chiusura, artefatti, stato per una sessione fredda |
| `1ddeee3` | `docs(project-state)` — correzione dell'integration HEAD |

Commit di TASK-005:

| Hash | Contenuto |
|---|---|
| `40c3578` | `test(api)` — i tre dialetti, 9 test deliberatamente rossi |
| `2f77f47` | `feat(api)` — il contratto unico |
| `d3faeae` | `docs(task-005)` — artefatti e review del proprio diff |

Branch conservati: `task-000-audit`, `task-001-persistence-foundation`,
`task-002-project-registry-foundation`, `task-003-task-project-association`,
`task-004-archival-consistency`.

## Decisioni architetturali

- **ADR-001** — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato. *Accettata*.
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway. *Accettata, parzialmente superata da ADR-003*.
- **ADR-003** — Il seed di sviluppo è uno stream Flyway separato. *Accettata*.
- **ADR-004** — Project Registry: stati chiusi imposti due volte, si archivia invece di cancellare, transizione illegale → `409`, unicità del nome nel database, un archiviato non è modificabile (§8). *Accettata*.
- **ADR-005** — Relazione `Task` → `Project`: `project_id` nullable, i task preesistenti non si migrano, associare a un `ARCHIVED` è `409`, relazione unidirezionale, FK senza `ON DELETE`. *Accettata*.
- **ADR-008** — Agent Registry: le decisioni di ADR-004 si applicano identiche; il ciclo di vita resta su `boolean active` invece dell'enum, perché unificarlo richiederebbe una migrazione irreversibile che il charter mette dietro una decisione umana (TD-31); `status` è **derivato** nel contratto, così il client vede un vocabolario solo e il database un solo stato; `V4` additiva; nessuna relazione con `Project` né con `Task`; il protocollo di lock si applica per L7. *Accettata e implementata*.
- **ADR-007** — Un solo contratto di errore per tutta l'API: advice globale che estende `ResponseEntityExceptionHandler`, `type` stabile e enumerato (`urn:ai-company-os:problem:<slug>`) come parte machine-readable del contratto, `errors` ovunque, catch-all con `detail` fisso e stack loggato, nessuna policy di timeout o retry. **Supera ADR-004 §6 e ADR-005 §8**. *Accettata e implementata*.
- **ADR-010** — Assegnazione `Task` → `Agent`: le tre domande di dominio risolte senza copiarle dalla relazione col progetto (D1 stesso esito e argomento diverso, D2 coincide, **D3 diverge** — un agente disattivato non congela i suoi task); il lock graph **ridimostrato** su tre classi con **L5′** `tasks` → `projects` → `agents` e i tre archi assenti verificati; l'aciclicità come **conseguenza** di ADR-006 §1 e D3; una sola versione, quella del task; `V6` additiva. *Accettata e implementata*.
- **ADR-009** — Concorrenza ottimistica nel contratto HTTP: due meccanismi distinti e complementari (lock = consistenza interna, `ETag`/`If-Match` = intento stantio); `@Version` è un contatore persistente e **non** il rilevatore, perché dopo l'attesa su `PESSIMISTIC_WRITE` l'entità è già alla versione nuova; protocollo **P0–P4**; `If-Match` obbligatorio su tutte e tre le risorse; `428`/`412`/`400`; `GET /api/tasks/{id}` introdotto come percorso canonico dell'ETag; `V5` additiva. **Completa ADR-006 §8.** *Accettata e implementata*.
- **ADR-011** — Vocabolario chiuso di `Task.status`: il censimento **prima** della decisione (cinque fonti, un solo valore, zero righe da trasformare); `OPEN`/`IN_PROGRESS`/`DONE`, con gli esclusi dichiarati per l'argomento di ADR-004 §2; **un vocabolario non è una macchina a stati** e nessuna transizione viene introdotta; il campo della request resta `String` perché tipizzarlo come enum produrrebbe un `malformed-request` che afferma il falso e perde il nome del campo; confronto **case-sensitive**, scelta opposta a ADR-008 sui nomi e per un criterio dichiarato; **tre guardie** invece delle due di ADR-004 §2; `V7` additiva sui dati ma non per costruzione, con il fallimento su valori fuori vocabolario come rischio **dichiarato ed eseguito**. **Non tocca ADR-007.** *Accettata e implementata*.
- **ADR-012** — Unificazione del ciclo di vita di `Agent`: **supera ADR-008 §2**, che aveva *sospeso* il cambiamento in attesa di una decisione umana, arrivata il 2026-09-19; backfill **biiettivo** (`TRUE`↔`ACTIVE`, `FALSE`↔`INACTIVE`, nessun terzo caso perché `active` è `NOT NULL` da `V1`), quindi irreversibile è la forma e non il contenuto; **il contratto pubblico non cambia di un byte** — `AgentResponse` portava già entrambi i campi e `V8` inverte quale è derivato; `INACTIVE` e non `ARCHIVED`, perché TD-31 unifica la *forma* e non il vocabolario; `repair()` sul solo stream del seed, con le tre condizioni che lo rendono accettabile lì e in nessun altro posto; fuori scope dichiarati: togliere `active` dalla risposta, rinominare `?active=`, indice, terzo stato. *Accettata e implementata*.
- **ADR-013** — Autenticazione: bearer token per nome, stateless, deny by default, `401` dentro ADR-007, fail closed. *Accettata e implementata* (TASK-013; CORS in TASK-014).
- **ADR-014** — Ciclo di vita del task: quattro archi (`start`, `complete`, `stop`, `reopen`) in `TaskTransition`, regole sull'entità, solo `start` guarda l'agente. *Accettata e implementata* (TASK-015).
- **ADR-015** — AI Engine: model gateway Python senza stato, contratto v1, token fra servizi, provider echo/Ollama/Anthropic (spento senza chiave). Il runtime a grafo resta rinviato. *Accettata e implementata* (TASK-017/018).
- **ADR-016** — Run: lancio col protocollo del task, esecuzione dopo il commit e fuori transazione, ogni run finisce con un tipo, recupero all'avvio, una run non completa il task. *Accettata e implementata* (TASK-019/020).
- **ADR-017** — Console dell'operatore: client del contratto, tipi generati e tenuti al codice due volte, `412` mai ritentato. *Accettata e implementata* (TASK-021/022).
- **ADR-006** — Coerenza archiviazione → task **derivata** (nessuna scrittura sui figli, `restore` inverso per costruzione), congelamento in scrittura con letture aperte, `PUT` idempotente `200` no-op, protocollo di lock **L0–L7** con ordine globale `tasks` → `projects`, nessuna migrazione, contratto invariato, nessun `503`. *Accettata e **implementata**.*

## Prossimo passo autonomo

**Nessuno fino alla Human Final Review del blocco PHASE 3–7** (`FINAL_HANDOFF.md`). Dopo, se
accettato: il prossimo blocco parte dal livello 3 di `AUTONOMOUS_LOOP.md` §4 — **TD-40** (contabilità
e limiti di costo delle run a consumo) va chiuso prima di abilitare `ANTHROPIC_API_KEY` in modo non
presidiato; poi le candidate in `FINAL_HANDOFF.md` §8.

*Storico (2026-09-19):* PHASE 2 chiusa, charter §8 rispettato, `FINAL_HANDOFF.md` di allora
conservato in `docs/handoff/FINAL_HANDOFF_PHASE_2.md`.

Quando PHASE 3 comincerà, la sua prima decisione è quale dei due candidati aprire — **TD-04**
(autenticazione) o **TD-37** (le transizioni di `status`) — e non è una decisione da prendere
dentro la fase precedente.

**TD-08**, la sostituzione di `MasterOrchestrator`, resta sbloccato ma non ancora eseguibile: un
task adesso sa dove sta, di chi è e in che stato è — ma non può **cambiare** stato (TD-37), e un
orchestratore che non muove il lavoro attraverso gli stati non ha niente da orchestrare. TD-37 è
il prerequisito rimasto.

Il piano completo della fase sta in **`.company-os/PHASE_2_PLAN.md`**. In sintesi:

| # | Task | Livello | Stato |
|---|---|---|---|
| **TASK-008** | Optimistic concurrency (`ETag`/`If-Match`). Chiude TD-28, TD-30 | 3 | **completata** 2026-09-16 |
| **TASK-009** | Relazione `Task` → `Agent`. Risolve TD-13 | 4 | **completata** 2026-09-17 |
| **TASK-010** | Vocabolario chiuso di `Task.status`. Chiude la metà `status` di TD-12 | 5 | **completata** 2026-09-19 |
| **TASK-011** | Registro del debito disambiguato; `docs/RUNNING.md` reso di nuovo vero | 6 | **completata** 2026-09-19 |

Fuori da PHASE 2 e dichiarato tale: **TD-14** (CI: richiede un remote, hard stop #4), **TD-31**
(elimina una colonna, hard stop #3), **TD-04** (autenticazione: primo candidato di PHASE 3).

Il merge di PHASE 1 in `master` resta il gesto con cui un umano accetta il lavoro
(`AUTONOMOUS_CHARTER.md` §8), e nessun agente lo esegue.

## Debito aperto rilevante

> ⚠️ **Due spazi di identificatori.** `docs/audit/TECHNICAL_DEBT.md` (TASK-000) e la numerazione
> qui sotto usano lo stesso formato `TD-NN` per debiti diversi: **`TD-14`, `TD-19`, `TD-20`,
> `TD-21`, `TD-22` collidono**. Questa numerazione è **autoritativa**, il prossimo id libero è
> **`TD-38`**, e la mappa completa è in **`docs/DEBT_REGISTRY.md`** — da leggere prima di chiudere
> o citare un `TD-NN`.

**Chiusi da TASK-004**: TD-19 (componente ciclo di vita), **TD-24**, **TD-25**.
**Chiusi da TASK-005**: **TD-07**, **TD-20**, **TD-21**, **TD-27**, **TD-29**.
**Chiusi da TASK-006**: **TD-22**, **TD-23**.
**TASK-007** non chiude nulla e **apre TD-31**.
**Chiusi da TASK-008**: **TD-28**, **TD-30**. **TASK-008 apre TD-32 e TD-33.**
**Risolto da TASK-009**: **TD-13** dell'audit (`Agent` e `Task` non si conoscevano).
**TASK-009 apre TD-34 e TD-35.**
**Chiuso a metà da TASK-010**: **TD-12** — la metà `status`. **TASK-010 apre TD-36 e TD-37.**
**TASK-011 non chiude e non apre nulla**: disambigua i due spazi (`docs/DEBT_REGISTRY.md`) e
corregge `docs/RUNNING.md`. Registrata e **non fatta**: la rivalutazione degli otto debiti che
esistono solo nell'audit.
**Chiuso da TASK-012**: **TD-31**. **TASK-012 non apre nulla**, e lascia **TD-14 aperto e
bloccato** su un dato mancante (`tasks/TASK-012/EVIDENCE_TD14.md`).

### Alto valore

| ID | Contenuto |
|---|---|
| **TD-31** | **CHIUSO** il 2026-09-19 da TASK-012. `V8` elimina `agents.active`; decisione umana esplicita, backfill biiettivo, contratto pubblico invariato. ADR-012 |
| **TD-14** | **CHIUSO 2026-09-23.** CI su GitHub Actions, verde alla prima run (`35863517006`). Con 223 test e invarianti di concorrenza, era l'unico debito il cui costo cresceva a ogni task |

### Nuovi (TASK-010)

| ID | Contenuto |
|---|---|
| **TD-37** | *(nuovo)* Nessun percorso muta lo `status` di un task esistente: creato `OPEN`, resta `OPEN`. Il vocabolario è chiuso, **il ciclo di vita non è percorribile**. Si chiude con la task delle transizioni, dove le regole di transizione sono la domanda centrale e non un effetto collaterale — ed è precisamente la domanda che ADR-011 §3 ha evitato di rispondere per inerzia |
| **TD-36** | *(nuovo, MINOR)* `Task.priority` resta una stringa libera senza vincolo DB. Stesso difetto di TD-12 e stessa forma di soluzione; **non combinato di proposito**, perché è una normalizzazione adiacente e non la stessa. Se verrà chiuso, il censimento va **rifatto su quel campo**: non si eredita quello di `status` |

### Nuovi, minori (TASK-008, TASK-009)

| ID | Contenuto |
|---|---|
| **TD-34** | Un task può puntare a un agente inattivo — stato **legale e necessario** per ADR-010 D3 — e non c'è modo di chiedere «i task fermi su agenti inattivi» senza incrociare due liste lato client. Si chiude con un filtro su `GET /api/tasks`, il giorno in cui un client lo pone |
| **TD-35** | Nessun `DELETE` dell'associazione con l'agente: un task assegnato non torna «non assegnato», si riassegna soltanto. Gemello di quello che ADR-005 §5 lasciò aperto sul progetto, con più pressione |
| **TD-32** | L'entity-tag è forte ma deriva dalla versione della riga, non dai byte della rappresentazione: un cambio di forma della risposta senza cambio di stato darebbe lo stesso ETag a due rappresentazioni diverse. Nessun effetto su `If-Match`; effetto sulla cache HTTP, che il progetto non usa |
| **TD-33** | I listati non portano ETag, quindi mutare N risorse costa N letture singole. Non motivato finché non esiste un client che muta in blocco |

### Qualità dei test e migrazioni

| ID | Contenuto |
|---|---|
| **TD-26** | Il path lazy non è esercitato fuori transazione. **Parzialmente coperto** da TASK-004: un test pinna che leggere `projectId` non inizializzi il proxy |

### Preesistente

TD-04 sicurezza, TD-08 `MasterOrchestrator`, TD-12/TD-13 dominio, TD-15 Lombok inutilizzato,
TD-17/TD-18 `README.md`.

**TD-11 (CORS) — superficie cambiata.** Il `@CrossOrigin` senza origine su `AgentController` è
stato **rimosso** da TASK-007: era già TD-11, ma quel controller adesso non legge soltanto, e
riportarlo avrebbe aperto creazione, modifica e transizioni a qualunque origine come effetto
collaterale. Non chiude TD-11 — la policy CORS resta una decisione da prendere — ma la superficie
oggi è più stretta di quanto il debito descrivesse.

Rilievi della review TASK-001 ancora aperti: **R3** (`.env` non configura il processo Maven),
**R5** (`server.address` non vincolato a loopback), **R6** (tag immagine mobile), **R7**
(`.gitignore` non copre `.env.*`). Correzioni documentali ai file `docs/audit/*` di TASK-000:
da applicare. `docs/RUNNING.md` non documenta `/api/projects` né gli endpoint di TASK-003.

## Failure aperti

**Nessuno.** 329/329 Java, 51/51 engine, 14/14 console (2026-09-23), e CI verde sui branch pushati.

## Domande di contratto aperte

Da decidere insieme, quando esisterà un client reale che le pone:

- nessun modo di sapere in anticipo che un task è congelato: lo si scopre dal `409`;
- nessun `DELETE /api/tasks/{id}/project`: `NULL → non NULL` resta a senso unico;
- nessun filtro «task senza progetto» su `GET /api/tasks`;
- `archive` non idempotente; `GET /api/projects` senza filtro include gli archiviati;
- nessun `DELETE /api/tasks/{id}/agent`, e nessun filtro «task su agenti inattivi» (TD-34, TD-35);
- assegnare in anticipo a un agente temporaneamente spento: rifiutato oggi, richiederebbe semantica
  di coda (ADR-010 D1);
- nessun filtro «task per stato» su `GET /api/tasks`, mentre `GET /api/projects?status=` esiste;
- nessun modo di **cambiare** lo stato di un task: si sceglie alla creazione e resta (TD-37);
- nessuna paginazione; nessuno slug pubblico stabile.

## Working principles
- **Autonomous execution con Human Final Review** (`AUTONOMOUS_CHARTER.md`).
- Persistent project artifacts instead of long chat histories.
- Minimal context loading.
- Invarianti prima del codice; rosso prima di verde; mutazione su ciò che è portante.
- Never rewrite or discard existing working code without evidence and justification.
- Un debito non si chiude perché il codice sembra diverso.

## Target architecture
- Project Registry ✅ *fondazione (TASK-002), relazione con i task (TASK-003), coerenza di archiviazione e concorrenza (TASK-004)*
- Agent Registry ✅ *fondazione (TASK-007), relazione con i task (TASK-009)*
- Task lifecycle ✅ *vocabolario chiuso (TASK-010), transizioni (TASK-015), dettagli e priorità (TASK-016)*
- Security baseline ✅ *bearer token (TASK-013), CORS (TASK-014); utenti/RBAC non ancora*
- Execution ✅ *run di un agente su un task attraverso l'AI Engine (TASK-019), routing sul registro (TASK-020)*
- Operator console ✅ *React/TS, tipi generati dal contratto (TASK-021, TASK-022)*
- Skills / Rules / Subagents / Tools / MCP Registry
- Model Gateway and local/cloud routing 🟡 *AI Engine con echo, Ollama, Anthropic (TASK-017/018); routing di modello per agente (TASK-020); quote e costi → TD-40*
- Context / Prompt / Harness / Loop / Graph Engineering
- Visual Code Architecture Graph, Knowledge Vault, Memory Graph
- Planner
- GitHub / GitLab, ClickUp, Google Drive / Gmail, software adapters
- Template Hub, Framework Explorer, External AI workspaces
- Voice Interaction Layer, Payments / quota monitoring, 3D Omniverse integration

## Immediate goal
**Blocco PHASE 3–7 completato, da accettare.** Il Company OS è utilizzabile dal vivo: un operatore
autenticato crea un task, lo assegna all'agente suggerito, lo fa eseguire da un modello locale
attraverso l'AI Engine, ne legge l'esito e lo chiude — da API o dalla console.

*Storico:* PHASE 1 e PHASE 2 in `master`, TASK-012: 223 test verdi, schema `V8`.

PHASE 3 **non è iniziata** e la sua prima decisione non è stata presa. `master` è adesso la linea
principale del progetto: da qui in avanti il gate del charter §8 si applica alla fase successiva,
non a quella conclusa.
