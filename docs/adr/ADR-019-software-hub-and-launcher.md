# ADR-019 — Software Hub: catalogo, rilevamento, launcher ad allowlist, embedding onesto

- **Stato**: Accettata e implementata (PHASE 8)
- **Data**: 2026-09-24
- **Decisa da**: agente, su direttiva umana del 2026-09-24 (§3–5, §23)
- **Dipende da**: ADR-013 (autenticazione), ADR-018 (concetti)

## 1. Decisione

Il Software Hub è un **catalogo** (`software`) di applicazioni, CLI, siti e servizi locali, con
capability interrogabili dall'orchestratore, uno **stato rilevato** sulla macchina e un
**launcher** che apre esclusivamente voci del catalogo.

## 2. Tipi di apertura

| `launch_kind` | Esempi | Come si apre |
|---|---|---|
| `DESKTOP` | IntelliJ, Postman, Claude, Codex, ChatGPT Classic | `explorer.exe shell:AppsFolder\<AppID>` — un solo meccanismo per app Win32 e Store |
| `DESKTOP` + `open_folder_executable` | VS Code, IntelliJ, PyCharm, WebStorm, Antigravity IDE, NetBeans | l'eseguibile del catalogo con **una** cartella come argomento: la cartella di lavoro di un progetto registrato |
| `CLI` | Claude Code, OpenCode, PowerShell | Windows Terminal (`wt.exe -d <cartella> <comando>`) |
| `WEB` | GitHub, GitLab, Supabase, Vercel, Gmail, Drive, ClickUp, Gemini | la console apre l'URL in una finestra nominata (nessun passaggio dal backend) |
| `LOCAL_SERVICE` | Open WebUI, 3D Omniverse | iframe nella console se `embeddable`, altrimenti finestra |

## 3. Rilevamento

Una sola chiamata a `Get-StartApps` (PowerShell per percorso assoluto, perché `powershell` non è nel
`PATH` di tutti i processi — la lezione di `cf97c0d`), più l'esistenza su disco degli eseguibili e
una sonda HTTP per i servizi locali. Risultato in cache per 60 s; `POST /api/software/refresh` la
invalida. Stati: `INSTALLED`, `NOT_INSTALLED`, `WEB` (sempre disponibile), `RUNNING` / `STOPPED`
(servizi), `INCOMPATIBLE_HARDWARE`, `UNKNOWN` (rilevamento non possibile su questo sistema operativo).
Nei test il rilevatore è un fake: nessun test lancia PowerShell.

## 4. Sicurezza del launcher

Aprire programmi da un'API HTTP è la superficie più pericolosa introdotta finora. Invarianti:

- **I1 — Allowlist**: si lancia solo ciò che il catalogo nomina. La richiesta porta una **chiave**
  di catalogo, mai un percorso, un comando o argomenti.
- **I2 — Argomenti chiusi**: l'unico argomento variabile è la cartella di lavoro, e viene dal
  **database** (il progetto indicato per id), non dalla richiesta; deve esistere ed essere una
  directory.
- **I3 — Nessuna shell**: `ProcessBuilder` con argomenti separati, mai `cmd /c` con una stringa
  concatenata; il prompt per le CLI passa da un **file** nella cartella del progetto, non dalla
  riga di comando.
- **I4 — Solo loopback, solo operatore**: la rotta sta sotto `/api/**` (ADR-013), il backend è
  legato a `127.0.0.1` (TASK-014).
- **I5 — `WEB` non passa dal backend**: aprire un URL è compito del browser; il backend rifiuta di
  «lanciare» una voce `WEB` con `409`.
- **I6 — Profilo**: il launcher reale è attivo solo su Windows e fuori dal profilo `test`;
  altrove è un launcher che risponde «non supportato» (`503`, problem `launcher-unavailable`).

## 5. Porta del backend: 8080 → 8081

La configurazione reale di Open WebUI sulla macchina dell'operatore usa `127.0.0.1:8080`, la porta
di default del backend. Due membri dello stesso ecosistema non possono contendersi la porta, e il
lato che controlliamo è il nostro: il backend passa a **8081** (`server.port=${SERVER_PORT:8081}`),
la console, lo script di avvio e la documentazione seguono. Open WebUI resta su 8080 e la console lo
incorpora come `http://localhost:8080`: stesso *site* della console (`localhost`), quindi i suoi
cookie funzionano dentro l'iframe.

## 6. Embedding onesto

`embeddable` è un dato **misurato** (header `X-Frame-Options` / `frame-ancestors` del
2026-09-24, `GAP_ANALYSIS_V2.md` §4), non un desiderio. La console non tenta mai un iframe su una
voce non incorporabile: mostra «si apre in una finestra dedicata» e perché. La via per incorporare
servizi di terze parti è una shell desktop con WebView, rinviata a PHASE 18 dietro decisione umana.

## 7. Alternative scartate

- **Lanciare dal browser con un protocollo personalizzato**: richiede di registrare un handler nel
  sistema operativo (modifica di configurazione di sistema) e non dà rilevamento.
- **Un servizio «desktop bridge» separato**: un processo in più senza un requisito che lo giustifichi
  oggi; il control plane sa già lanciare processi. Da riconsiderare se il terminale integrato
  (PHASE 15) lo richiede.
- **Salvare «installato» nel DB**: diventa falso (ADR-018 §5).
