# Running AI Company OS locally

Stato attuale (2026-09-24): **control plane** Spring Boot + PostgreSQL, autenticato con bearer
token (ADR-013), porta **8081**; **AI Engine** Python (ADR-015, §2b); **console dell'ecosistema**
React (ADR-017, §2c); Software Hub, workspace dei progetti, Master Orchestrator, agenti con harness,
Daily Work e Second Brain (ADR-018…023, `.company-os/ROADMAP_V2.md`).

## 0. Tutto insieme, in un comando

```powershell
.\scripts\start-dev.ps1                         # database 'aicompany'
.\scripts\start-dev.ps1 -Database aicompany_try # un clone, per non toccare i dati
```

Avvia PostgreSQL, l'AI Engine, il backend (`dev`) e la console, ciascuno nella propria finestra, poi
apre gli URL: console `http://localhost:5173`, backend `http://localhost:8081`, engine
`http://127.0.0.1:8090`. ⚠️ Su un database dietro la testa dello stream il backend applica le
migrazioni mancanti all'avvio (compresa `V8`, l'unica distruttiva, autorizzata il 2026-09-19):
per provare senza toccare i dati, clonare prima (vedi l'intestazione dello script).

## 0b. Il percorso completo, dalla console

1. **Progetti → Nuovo progetto**: tipologia, dettagli, stack proposto, cartella e livello di
   Human-in-the-Loop. La cartella viene preparata con `MASTER_PROMPT.md`, `AGENTS.md`, `CLAUDE.md`,
   `docs/`, `tasks/`, `references/`, `.aicos/` (nessun file esistente viene sovrascritto).
2. **Master Prompt**: brainstorming con ChatGPT Classic (pulsante nella scheda), incolla il MASTER
   PROMPT, salva.
3. **Piano**: «Genera piano» con un modello locale (misurato: ~13 min col 9B, a stadi, con
   l'avanzamento visibile) oppure «Pianifica con Claude Code» e poi «Importa .aicos/plan.json».
4. **Approva** la fase (o il piano intero): prima dell'approvazione nessuna task parte (`409
   phase-not-approved`).
5. Apri una task: il pannello **Master Orchestrator** mostra agente, modello, software, contesto e
   prompt compatto, con le ragioni. Eseguila con l'AI Engine oppure consegnala a Claude Code /
   OpenCode (Windows Terminal nella cartella del progetto) o a Codex / Antigravity (app aperta,
   prompt negli appunti).
6. **Review**: «Accetta e completa» o «Richiedi modifiche», con nota.
7. **Agenti → Installa ecosistema base** per gli agenti con sottoagenti e harness; **Second Brain**
   per vedere tutto collegato; **Daily Work** per oggi e domani; **Consumi** per quote e reset.

Open WebUI (desktop) usa la porta 8080 ed è incorporato in **Integrazioni** quando è acceso.

## Prerequisiti
- JDK 21
- Docker Desktop in esecuzione (il daemon deve essere attivo, non solo installato)
- Nessuna installazione di Maven: si usa il wrapper `./mvnw`

## 1. Avviare il database

Dalla radice del repository:

```bash
docker compose up -d
```

Questo avvia PostgreSQL 17 sulla porta `5432`, **vincolata a `127.0.0.1`**: il backend non ha ancora autenticazione, quindi il database non deve essere raggiungibile dalla rete.

I dati vivono nel volume Docker `aicompany_postgres_data` e sopravvivono alla ricreazione del container.

Configurazione opzionale: copiare `.env.example` in `.env` e modificarlo. Senza `.env` valgono i default locali del `docker-compose.yml`. **Il file `.env` non va committato.**

Verifica dello stato:

```bash
docker compose ps
```

## 2. Avviare il backend

```bash
cd backend
./mvnw spring-boot:run
```

⚠️ **`BUILD SUCCESS` di `spring-boot:run` non significa che l'applicazione sia partita.** Con
`spring-boot-devtools` sul classpath un fallimento di avvio avviene sul thread
`restartedMain` e Maven esce comunque con codice `0`. Per uno smoke test affidabile,
disattivare il restart nella JVM — il flag nel file properties non basta:

```bash
cd backend
./mvnw -B spring-boot:run "-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false"
```

Così un errore di avvio produce `BUILD FAILURE` ed exit code `1`. Non usare l'exit code di
`spring-boot:run` come prova di readiness in nessuno script.

Il profilo di default è `dev`. All'avvio Flyway applica le migrazioni e, solo in `dev`, il seed dimostrativo di tre agent.

L'applicazione risponde su `http://localhost:8081`.

### Prima di tutto: accesso e credenziali (PHASE 15, ADR-024)

**Ogni rotta `/api/**` risponde `401` senza un bearer token valido.** Le persone entrano con
utente e password; le macchine (script, automazioni) possono usare un token di servizio.

**L'account Admin.** Al primo avvio su un database senza admin, il control plane crea l'utente
`admin`:

| Situazione | Password iniziale |
|---|---|
| `AICOS_ADMIN_PASSWORD` impostata (ambiente o file locale dei segreti) | quella |
| nessuna password configurata | generata a caso e scritta **solo** in `%USERPROFILE%\.aicos\admin-initial-password.txt`; al primo accesso la console chiede di cambiarla |

Il file locale dei segreti è `%USERPROFILE%\.aicos\local.env` (fuori dal repository). Lo crea
`scripts\init-local-secrets.ps1` — lo chiama `start-dev.ps1` — con una password admin casuale e un
token casuale condiviso fra control plane e AI Engine. Il backend lo importa
(`spring.config.import` in `application-dev.properties`), l'engine lo legge da sé; le variabili
d'ambiente vincono sempre sul file.

```powershell
.\scripts\init-local-secrets.ps1 -Show    # mostra utente e password admin iniziali
```

- **Cambiare la password**: console → menu utente → *Account e sicurezza*. Le altre sessioni si
  chiudono. Regole: almeno 12 caratteri, al massimo 72 byte (limite di BCrypt), niente nome utente.
- **Password dimenticata**: metti la nuova password in `AICOS_ADMIN_PASSWORD` (nel file locale o
  nell'ambiente), avvia una volta il backend con `AICOS_ADMIN_RESET=true`, poi togli la variabile.
- **Altri utenti**: un admin li crea da *Impostazioni → Utenti* (ruolo `OPERATOR` o `ADMIN`).

**Dall'API**:

```bash
TOKEN=$(curl -s http://localhost:8081/api/auth/login -H 'Content-Type: application/json'   -d '{"username":"admin","password":"<la tua password>"}' | jq -r .token)
curl -i http://localhost:8081/api/projects -H "Authorization: Bearer $TOKEN"
curl -X POST http://localhost:8081/api/auth/logout -H "Authorization: Bearer $TOKEN"
```

| Aspetto | Comportamento |
|---|---|
| Password | solo hash BCrypt (costo 12) |
| Sessione | token casuale di 256 bit, conservato solo come SHA-256; scade dopo 8 h di inattività o 72 h in assoluto (`aicos.security.session.idle` / `.lifetime`); logout, cambio password e disattivazione la revocano |
| Tentativi | 5 password errate bloccano l'account per 15 minuti; al massimo 20 tentativi al minuto per indirizzo |
| Ruoli | `OPERATOR` lavora; `ADMIN` in più gestisce utenti, legge il registro di sicurezza, elimina progetti e task, usa il terminale |
| Registro | accessi, errori, blocchi, cambi di password, accessi negati, eliminazioni: tabella `security_events` e logger `aicos.security`; mai password né token |
| Risposte | stessa risposta per utente inesistente e password errata; `Cache-Control: no-store` sul login; `Content-Security-Policy: default-src 'none'`, `X-Content-Type-Options`, `Referrer-Policy: no-referrer` |

**Token di servizio** (opzionali, per script): `aicos.security.api-tokens.<nome>=<token>`, o
`AICOS_OPERATOR_TOKEN` per il nome `operator`. Nessun default: se non è impostato, nessuna macchina
può chiamare. Ruolo `OPERATOR`, salvo `aicos.security.api-token-roles.<nome>=ADMIN`. Un token più
corto di 16 caratteri, o lo stesso token sotto due nomi, impediscono l'avvio.

L'unica rotta pubblica oltre al login è `GET /actuator/health`, che risponde soltanto
`{"status":"UP"}`. Un token sbagliato riceve **esattamente** la stessa risposta di un token
assente. **CSRF**: il token viaggia in un header e mai in un cookie, quindi il browser non lo
allega da solo; non c'è niente che un sito terzo possa sfruttare.

Negli esempi qui sotto l'header `Authorization` è **sottinteso**.

### Rete e browser (TASK-014)

- In `dev` il backend ascolta **solo su `127.0.0.1`**, come il database. `SERVER_ADDRESS=0.0.0.0`
  lo apre deliberatamente.
- **CORS**: un browser può chiamare l'API solo dalle origini in `aicos.cors.allowed-origins`
  (`AICOS_CORS_ALLOWED_ORIGINS`). In `dev` il default è il dev server di Vite
  (`http://localhost:5173`, `http://127.0.0.1:5173`); in `prod` **nessuna**. `*` e valori che non
  sono origini (`scheme://host[:port]`) **impediscono l'avvio**.
- La policy espone `ETag` e `Location` e accetta `If-Match`: senza, una pagina potrebbe leggere ma
  mai scrivere. Nessuna richiesta *credentialed*: il token lo manda la pagina, il browser non
  allega niente da sé.

### Poi: ogni mutazione richiede `If-Match`

**Se una richiesta di scrittura su una risorsa esistente risponde `428`, non è un errore del
server: manca l'header.** È il protocollo P0–P4 di ADR-009, e vale per **tutte e tre** le
risorse.

Il ciclo è sempre lo stesso: si legge la risorsa singola, si prende l'`ETag` dalla risposta, lo si
rimanda in `If-Match`.

```bash
# 1. leggere, e tenere l'ETag
curl -i http://localhost:8081/api/tasks/1
# ... HTTP/1.1 200
# ... ETag: "0"

# 2. scrivere, citandolo
curl -i -X PUT http://localhost:8081/api/tasks/1/project \
  -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{"projectId":1}'
```

| Risposta | Significato |
|---|---|
| `428` | `If-Match` assente. Leggere la risorsa e riprovare |
| `412` | L'`ETag` inviato è **stantio**: qualcun altro ha scritto nel frattempo. Rileggere |
| `400` | L'`If-Match` è illeggibile, oppure è `*` |

I **listati non portano `ETag`** (è un limite dichiarato, TD-33): per scrivere si legge sempre la
risorsa singola. `POST` e le mutazioni riuscite restituiscono l'`ETag` nuovo, quindi una sequenza
di scritture non ha bisogno di rileggere ogni volta.

### Task

| Endpoint | Descrizione |
|---|---|
| `GET /api/tasks` | elenco. Filtro facoltativo `?status=OPEN\|IN_PROGRESS\|DONE`. **Senza `ETag`** |
| `GET /api/tasks/{id}` | una task, **con `ETag`** — il percorso canonico per ottenerlo |
| `POST /api/tasks` | creazione (`201` + `Location` + `ETag`) |
| `PUT /api/tasks/{id}` | sostituisce **titolo, descrizione, priorità**. Non lo stato né le associazioni. **`If-Match`** |
| `PUT /api/tasks/{id}/project` | assegna o sposta di progetto. **`If-Match`** |
| `PUT /api/tasks/{id}/agent` | assegna o cambia agente. **`If-Match`** |
| `POST /api/tasks/{id}/start` | `OPEN → IN_PROGRESS`. Richiede un agente **attivo**. **`If-Match`** |
| `POST /api/tasks/{id}/complete` | `IN_PROGRESS → DONE`. **`If-Match`** |
| `POST /api/tasks/{id}/stop` | `IN_PROGRESS → OPEN`. **`If-Match`** |
| `POST /api/tasks/{id}/reopen` | `DONE → OPEN`. **`If-Match`** |

Non esiste `DELETE /api/tasks/{id}` (risponde `405`, «non per questa via»). Il `PUT` sulla task
riguarda i soli dettagli: le due associazioni sono sotto-risorse, e lo stato si muove lungo archi.

```bash
curl -i -X POST http://localhost:8081/api/tasks -H 'Content-Type: application/json' \
  -d '{"title":"Primo task","description":"descrizione","status":"OPEN","priority":"HIGH"}'
```

`status` ha un **vocabolario chiuso**: `OPEN`, `IN_PROGRESS`, `DONE`, confrontati
**esattamente** — `"open"` è rifiutato quanto `"banana"`, con `400` e il campo nominato in
`errors.status` (ADR-011). Anche `priority` ha un **vocabolario chiuso** da TASK-016: `LOW`,
`MEDIUM`, `HIGH`, con le stesse regole.

**Lo `status` si cambia solo lungo i quattro archi qui sopra** (ADR-014, da TASK-015): ogni altra
coppia è `409 illegal-task-state-transition`, e non esiste un `PUT` su `status`. `start` senza
agente è `409 unassigned-task-cannot-start`; con un agente inattivo `409
inactive-agent-cannot-receive-tasks`. Un task in un progetto archiviato non si muove.

`projectId` e `agentId` sono facoltativi nel corpo della `POST`: se presenti e rifiutati, **la
task non viene creata affatto**.

### Progetti

| Endpoint | Descrizione |
|---|---|
| `GET /api/projects` | elenco. Filtro facoltativo `?status=ACTIVE\|ARCHIVED`; **senza filtro include gli archiviati** |
| `GET /api/projects/{id}` | un progetto, con `ETag` |
| `POST /api/projects` | creazione (`201` + `Location` + `ETag`) |
| `PUT /api/projects/{id}` | aggiorna i campi descrittivi. **`If-Match`** |
| `POST /api/projects/{id}/archive` | archivia. **`If-Match`** |
| `POST /api/projects/{id}/restore` | ripristina. **`If-Match`** |
| `GET /api/projects/{projectId}/tasks` | le task del progetto. Un progetto inesistente è `404`, non una lista vuota |

**Non si cancella, si archivia** (ADR-004): non esiste `DELETE`. Un progetto `ARCHIVED` non
riceve task nuove e le task che contiene **non si spostano** — `409` in entrambi i casi. Le
**letture** restano aperte: archiviare non rende illeggibile la storia.

### Agent

| Endpoint | Descrizione |
|---|---|
| `GET /api/agents` | elenco. Filtro facoltativo `?active=true\|false` |
| `GET /api/agents/{id}` | un agent, con `ETag` |
| `POST /api/agents` | creazione (`201` + `Location` + `ETag`) |
| `PUT /api/agents/{id}` | aggiorna. **`If-Match`** |
| `POST /api/agents/{id}/deactivate` | disattiva. **`If-Match`** |
| `POST /api/agents/{id}/activate` | riattiva. **`If-Match`** |

Da TASK-020 un agente ha un campo facoltativo **`model`** (id dell'engine, per esempio
`ollama:llama3.2:3b`): è il modello delle sue run. Il `PUT` lo sostituisce come ogni dettaglio.
| `GET /api/agents/{agentId}/tasks` | le task dell'agent |

Il nome è **unico senza distinzione di maiuscole**, imposto dal database. Un agent disattivato
non riceve lavoro nuovo (`409`), ma **le task che già tiene restano pienamente riassegnabili** —
è deliberato (ADR-010 D3): è proprio il momento in cui bisogna poterle dare a qualcun altro.

La risposta porta **due** campi di ciclo di vita, `active` (booleano) e `status`
(`ACTIVE`/`INACTIVE`), e continua a portarli entrambi. Da `V8` quello memorizzato è `status` e
`active` è derivato — prima era l'inverso — ma **il JSON è identico** e il filtro resta
`?active=true|false`. ADR-012 §4.

### Run: un agente esegue un task (PHASE 6, ADR-016)

Richiede l'**AI Engine** in esecuzione (§2b). Il backend in `dev` lo cerca su
`http://127.0.0.1:8090` con il token `dev-engine-token-change-me` (`AICOS_ENGINE_URL`,
`AICOS_ENGINE_TOKEN`; in `prod` obbligatori).

| Endpoint | Descrizione |
|---|---|
| `POST /api/tasks/{id}/runs` | lancia una run. **`If-Match` del task.** Corpo facoltativo `{"model":"ollama:llama3.2:3b"}`. `202` + `Location` |
| `GET /api/runs/{id}` | la run: `status` `QUEUED` → `RUNNING` → `SUCCEEDED`/`FAILED`, prompt inviati, output, token, latenza |
| `GET /api/tasks/{id}/runs` | le run del task, la più recente per prima |

- Il task deve avere un agente **attivo**, non essere `DONE` né in un progetto archiviato; una sola
  run non finita per volta (`409 task-run-in-progress`).
- Un task `OPEN` lanciato diventa `IN_PROGRESS`. **Una run riuscita non completa il task**:
  l'operatore legge l'output e usa `POST /api/tasks/{id}/complete`.
- Modello: quello chiesto al lancio, altrimenti quello dell'agente (`model` nell'agente), altrimenti
  il default dell'engine (`echo:default`, deterministico).
- Una run fallita dice perché in `failureType`: il problema dell'engine inoltrato
  (`urn:ai-company-os:engine:problem:*`) o uno del control plane
  (`urn:ai-company-os:run-failure:engine-unreachable|engine-timeout|engine-protocol|interrupted|rejected|internal`).
- All'avvio, le run rimaste `QUEUED`/`RUNNING` da un processo precedente sono fallite `interrupted`.

```bash
ETAG=$(curl -si http://localhost:8081/api/tasks/7 -H "Authorization: Bearer $TOKEN" | grep -i etag | awk '{print $2}' | tr -d '\r')
curl -s -X POST http://localhost:8081/api/tasks/7/runs -H "Authorization: Bearer $TOKEN" -H "If-Match: $ETAG"
curl -s http://localhost:8081/api/runs/1 -H "Authorization: Bearer $TOKEN"
```

### Routing: quale agente per quale lavoro (TD-08)

| Endpoint | Descrizione |
|---|---|
| `GET /api/tasks/{id}/agent-suggestions` | gli agenti **attivi** ordinati per affinità col task, con `score` e `matchedTerms` |
| `POST /api/routing/suggestions` | lo stesso per testo libero: `{"text":"..."}` |

Lessicale e deterministico: suggerisce, non assegna. `POST /api/orchestrator` (il placeholder) è
stato **rimosso** da TASK-020.

### Errori

Ogni risposta d'errore è un `ProblemDetail` (RFC 9457) con un `type` **stabile**
`urn:ai-company-os:problem:<slug>` — ADR-007. **Il `type` è il contratto; il `title` è prosa** e
può essere riscritto: un client deve ramificare sul primo.

```json
{
  "type": "urn:ai-company-os:problem:validation-failed",
  "title": "Invalid request payload",
  "status": 400,
  "detail": "The request body failed validation",
  "errors": { "status": "status must be one of OPEN, IN_PROGRESS, DONE" }
}
```

Un body invalido (per esempio `{}`) è rifiutato con `400` e **nulla viene scritto** sul database.

## 2b. Avviare l'AI Engine (PHASE 5, ADR-015)

Il servizio Python di ADR-001: riceve un prompt, restituisce un completamento. Senza stato, senza
database. Prerequisito: Python ≥ 3.11.

```bash
cd ai-engine
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements-dev.txt   # su Linux/macOS: .venv/bin/python
.venv/Scripts/python -m app
```

Ascolta su **`127.0.0.1:8090`**. Profilo `dev` per default, con token fra servizi
`dev-engine-token-change-me`; fuori da `dev` `AICOS_ENGINE_TOKEN` è obbligatoria.

| Rotta | Auth | |
|---|---|---|
| `GET /health` | no | `{"status":"UP"}` |
| `GET /v1/models` | `Bearer` | modelli e disponibilità |
| `POST /v1/completions` | `Bearer` | `{model?, system?, messages[], max_tokens?}` |

```bash
curl -s -X POST http://127.0.0.1:8090/v1/completions \
  -H "Authorization: Bearer dev-engine-token-change-me" -H "Content-Type: application/json" \
  -d '{"model":"ollama:llama3.2:3b","messages":[{"role":"user","content":"Ciao"}]}'
```

| Provider | Id | Costo | Come si abilita |
|---|---|---|---|
| `echo` | `echo:default` (default) | zero | sempre attivo; **deterministico, non è un modello** |
| `ollama` | `ollama:<nome>` | zero, locale | Ollama in esecuzione su `AICOS_ENGINE_OLLAMA_URL` (default `http://127.0.0.1:11434`) e un modello scaricato (`ollama pull llama3.2:3b`) |
| `anthropic` | `anthropic:claude-opus-5` | **a consumo** | **solo** impostando `ANTHROPIC_API_KEY`. Spento per default |

Altre variabili: `AICOS_ENGINE_DEFAULT_MODEL`, `AICOS_ENGINE_ANTHROPIC_MODELS` (lista separata da
virgole), `AICOS_ENGINE_HOST`, `AICOS_ENGINE_PORT`, `AICOS_ENGINE_PROFILE`.

Test dell'engine: `cd ai-engine && .venv/Scripts/python -m pytest` (nessuna rete, nessun costo).

## 2c. Avviare la console (PHASE 7, ADR-017)

Prerequisito: Node ≥ 22.

```bash
cd frontend
npm ci
npm run dev        # http://localhost:5173
```

Deve girare su `http://localhost:5173`: è l'origine che il profilo `dev` del backend ammette
(`aicos.cors.allowed-origins`). Login con l'URL del backend e il token dell'operatore.

| Comando | |
|---|---|
| `npm test` | 14 test (Vitest, `fetch` finto: nessun backend necessario) |
| `npm run typecheck` / `npm run build` | controllo dei tipi / build di produzione in `dist/` |
| `npm run api:types` | rigenera `src/api/schema.d.ts` da `docs/api/openapi.json` |

Il contratto: `docs/api/openapi.json`, prodotto dal codice e tenuto identico da
`OpenApiContractTest`. Dopo una modifica voluta dell'API:
`cd backend && ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.write=true`, poi
`cd frontend && npm run api:types`. La CI fallisce se uno dei due è indietro.

## 3. Eseguire i test

```bash
cd backend
./mvnw test
```

I test girano contro un **PostgreSQL reale** avviato da Testcontainers, non su database embedded. **Docker deve essere in esecuzione**, altrimenti i test falliscono all'avvio del container.

### La stessa suite in CI

`.github/workflows/ci.yml` esegue **esattamente questo comando** — `./mvnw -B clean test` in
`backend/` — su `ubuntu-latest` con JDK 21 (Temurin), a ogni push e a ogni pull request, e a
richiesta con *Run workflow*.

**Non c'è un blocco `services: postgres`, ed è deliberato.** È la cosa ovvia da aggiungere a un
workflow per un progetto i cui test usano PostgreSQL, e qui sarebbe sbagliata: nessuno si
collegherebbe a quel servizio. La suite non prende il database dall'ambiente, se lo **avvia da
sé** con Testcontainers. Quello che serve davvero è un **daemon Docker**, che i runner
`ubuntu-latest` hanno, e un passo del workflow lo verifica esplicitamente perché la sua assenza
altrimenti fallirebbe dentro Testcontainers con un messaggio che non nomina la causa.

I report di Surefire sono caricati come artefatto **anche quando il job fallisce** — soprattutto
allora, perché sono l'unico modo di vedere quale test è rosso senza rieseguire il job.

> ✅ **Attiva dal 2026-09-23.** Prima run verde: `35863517006` — 9 step su 9 `success` su
> `ubuntu-latest` in 1m26s, `./mvnw -B clean test` compreso. **TD-14 chiuso**:
> `tasks/TASK-012/EVIDENCE_TD14.md` §11.

## 4. Profili

| Profilo | Uso | Sorgente della configurazione |
|---|---|---|
| `dev` (default) | sviluppo locale | `application-dev.properties`, default sovrascrivibili da variabili d'ambiente |
| `test` | test automatici | datasource fornito da Testcontainers; solo lo stream di schema, nessun seed |
| `prod` | produzione | **solo** variabili d'ambiente (`POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`); nessun default, solo lo stream di schema |

Avvio con profilo esplicito:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

## 5. Schema del database

Lo schema è di proprietà di Flyway, non di Hibernate. Schema e seed sono **due stream
Flyway indipendenti**, con tabelle di storia distinte (ADR-003):

| Stream | Cartella | Tabella di storia | Applicato da |
|---|---|---|---|
| Schema | `backend/src/main/resources/db/migration` | `flyway_schema_history` | tutti i profili |
| Seed di sviluppo | `backend/src/main/resources/db/dev` | `flyway_dev_seed_history` | solo il profilo `dev` |

Perché separati: finché il seed viveva nello stream dello schema come `V1000`, il database
dev era alla versione 1000 e una successiva `V2` non era più applicabile
(`Detected resolved migration not applied to database: 2`). Con due stream lo schema resta
lineare — `V1`, `V2`, `V3`, … — sia che il database sia stato seminato sia che non lo sia.

### Due numeri diversi: lo stream e il tuo database

Non vanno confusi, ed è un equivoco che è già costato una diagnosi sbagliata:

| | Che cos'è | Come si legge |
|---|---|---|
| **Migration stream** | La migrazione più alta che **esiste nel repository**. Proprietà del codice | `ls backend/src/main/resources/db/migration` |
| **Versione del tuo database** | Ciò che è stato realmente **applicato** a quell'installazione. Proprietà del volume | `docker exec aicompany-postgres psql -U aicompany -d aicompany -c "SELECT version, script FROM flyway_schema_history WHERE success ORDER BY installed_rank;"` |

Al 2026-09-19: lo **stream è a `V8`**; il **database di sviluppo locale è a `V3`** e non ha mai
visto `V4`…`V8`. Non è un difetto — quel database non viene avviato da un po'. Al primo avvio in
profilo `dev` le cinque migrazioni si applicheranno in ordine. **Verificato su un clone di quel
database**: `V7` passa perché l'unica riga di `tasks` ha `status = 'OPEN'`, e `V8` converte i tre
agenti da `active = TRUE` a `status = 'ACTIVE'` senza perdere righe. **Non serve
`docker compose down -v`.**

Quando un documento dice «schema a `V8`» **senza qualificatore, intende lo stream**, mai un
database.

**La prossima migrazione di schema prende il numero successivo alla testa dello stream, e va in
`db/migration`.** Al 2026-09-23 la testa è **`V11`**, quindi la prossima è `V12__....sql` — ma va
letta col comando qui sotto, non da questa riga. I numeri del seed
sono indipendenti e non vanno considerati.

Per leggere la testa invece di fidarsi di questo paragrafo:

```bash
ls backend/src/main/resources/db/migration
```

> Questa riga diceva «la prossima si chiama `V2__...sql`» fino a TASK-011, quando lo stream era
> già a `V7`. Non era documentazione incompleta: **istruiva a sbagliare**, perché una `V2` nuova
> viene rifiutata da Flyway. Da qui il comando qui sopra.

Le migrazioni applicate finora:

| Versione | Che cosa introduce |
|---|---|
| `V1` | `agents`, `tasks` |
| `V2` | `projects`, con `projects_status_check` e unicità del nome |
| `V3` | `tasks.project_id` + FK + indice |
| `V4` | `agents.created_at`/`updated_at`, unicità del nome case-insensitive |
| `V5` | `version` su tutte e tre le tabelle (ADR-009) |
| `V6` | `tasks.agent_id` + FK + indice |
| `V7` | `tasks_status_check`: vocabolario chiuso di `Task.status` (ADR-011) |
| `V8` | `agents.status` + `agents_status_check`, e **`agents.active` eliminata** (ADR-012). **La prima migrazione distruttiva dello stream**: un database che la esegue non torna a `V7` eseguendo SQL al contrario |
| `V9` | `tasks_priority_check`: vocabolario chiuso di `Task.priority` (TASK-016). Si ferma, senza toccare la riga, su un valore fuori vocabolario |
| `V10` | `task_runs` (ADR-016): stato, prompt, esito, con vincoli che legano l'esito allo stato e al più una run non finita per task |
| `V11` | `agents.model`, nullable (TASK-020) |

Hibernate gira in `validate`: se le entità e le migrazioni divergono, **l'avvio fallisce** con `Schema validation: missing column ...`. È il comportamento voluto — la correzione è una nuova migrazione, mai una modifica automatica dello schema.

Limite da conoscere: `validate` verifica presenza e compatibilità di tipo delle colonne
mappate, **non** ogni proprietà dello schema. La rimozione manuale di un `NOT NULL` non fa
fallire l'avvio; una colonna mancante sì. I vincoli sono coperti dai test SQL.

### Database dev creato prima di TASK-001A

Un database dev che contiene ancora `V1000__dev_seed_agents.sql` in `flyway_schema_history`
si aggiorna **da solo al primo avvio in profilo `dev`**: la riga legacy viene rimossa e il
seed viene registrato nel proprio stream. Nel log compare

```
Removed the legacy V1000__dev_seed_agents.sql entry from flyway_schema_history
```

Nessun dato viene cancellato e **non serve `docker compose down -v`**. Il seed non viene
duplicato: lo script ha un `WHERE NOT EXISTS` sul nome dell'agent. Verifica manuale:

```bash
docker exec aicompany-postgres psql -U aicompany -d aicompany -c "SELECT version, script FROM flyway_schema_history ORDER BY installed_rank;"
```

Dopo la transizione **non deve contenere alcuna riga `1000`**: solo le versioni di schema
applicate, in ordine, a partire da `1 | V1__create_agents_and_tasks.sql`.

> Fino a TASK-011 questa riga diceva «deve contenere **solo** `1 | V1__...`», vero appena dopo
> TASK-001A e falso da `V2` in poi. Ciò che la verifica deve cercare è l'**assenza** della riga
> legacy, non la presenza di una sola riga.

Per ripartire da un database pulito:

```bash
docker compose down -v && docker compose up -d
```

⚠️ `-v` cancella il volume e tutti i dati locali.

## 6. Avvertenze

- **Non promuovere un database di sviluppo a produzione.** È una **policy operativa, non un controllo tecnico**: il profilo `prod` legge solo `flyway_schema_history` e ignora del tutto lo stream del seed, quindi si avvia senza errori sia su un database mai seminato sia su uno seminato in dev. Nulla impedisce a `prod` di puntare a un database di sviluppo — la separazione evita che i due stream interferiscano, non protegge da una configurazione sbagliata.
- **Un solo profilo operativo per avvio.** Combinazioni come `dev,prod` non sono un confine di sicurezza: attivano il bean del seed.
- **L'autenticazione esiste da TASK-013, ma è un token per operatore, non un sistema di utenti**:
  non esporre backend o database fuori da `localhost` senza TLS davanti. Un bearer token in chiaro
  su una rete è una password in chiaro.
- Il seed di sviluppo è **dato dimostrativo**, non dato di riferimento di produzione.
