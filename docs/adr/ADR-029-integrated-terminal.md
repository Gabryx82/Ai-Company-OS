# ADR-029 — Terminale integrato nella console

- **Stato**: Accettata e implementata (PHASE 22); rivista il 2026-10-07 (§5)
- **Data**: 2026-09-25
- **Decisa da**: agente (roadmap V2, ex PHASE 15; chiude TD-42)

## 1. Decisione

Un terminale vero dentro la pagina: uno **pseudo-terminale** sulla macchina dell'operatore (pty4j:
ConPTY su Windows), i cui byte passano in un **WebSocket** verso **xterm.js**. Shell disponibili, da
allowlist: **PowerShell**, **Claude Code**, **OpenCode** — gli eseguibili sono quelli che il Software
Hub rileva, mai un comando scritto dal browser. Cartella: quella del progetto scelto, o la home.

## 2. Sicurezza

Un terminale è esecuzione di comandi, quindi:

- **solo admin**: `POST /api/terminal/tickets` e `GET /api/terminal/shells` sono in `/api/terminal/**`,
  `ADMIN_ONLY` (ADR-024);
- **ticket monouso**: il WebSocket del browser non può mandare header, quindi l'handshake su
  `/api/terminal/ws` è pubblico, ma il socket si autentica col **primo messaggio**
  `{"type":"auth","ticket":…}`. Il ticket vale 30 secondi e una volta sola (conservato come SHA-256),
  non passa mai nell'URL; senza ticket valido entro 5 secondi il socket si chiude
  (`1008 policy violation`);
- comando e cartella sono decisi **quando il ticket è emesso**, dal catalogo e dal database: niente
  di ciò che il socket manda li cambia;
- **origini**: l'handshake accetta solo le origini dichiarate per la console (le stesse del CORS);
- al massimo 4 terminali aperti; ogni apertura è un evento `PROCESS_STARTED` nel registro;
- chiudere la scheda o il socket termina il processo (rivisto in §5: chiudere la scheda nella console
  lo termina; un socket che cade lascia la shell staccata, riagganciabile solo dal suo proprietario).

## 3. Protocollo

Dal browser, frame di testo JSON: `auth` (ticket, colonne, righe), `input` (tasti), `resize`, `close`
(§5: termina la shell). Dal server: l'output come frame binari (byte grezzi, decodificati da
xterm.js), `ready` con l'id della shell (`session`), la cartella, il tipo di pseudo-terminale (`pty`:
`conpty`, `winpty` o `unix`) e se è un riaggancio (`attached`), `exit` con il codice di uscita,
`error`. Chiusure: `1000` la shell è finita, `4001` agganciata da un altro socket, `4004` non c'è
(più) una shell propria con quell'id.

## 4. Test

`TerminalSocketTest` (server su porta casuale, PTY reale con un comando di test innocuo abilitato solo
da una proprietà dei test — `cmd /k` su Windows, `cat` altrove: output che torna, codice di uscita,
ticket monouso, niente prima del ticket, operatore senza ticket né elenco; su Windows, che il `ready`
dichiari `conpty`; §5: la shell sopravvive al suo socket e torna con ciò che ha stampato, un terzo
socket la prende al secondo, la chiusura dalla console la termina, una shell staccata oltre il tempo
massimo viene chiusa, il proprietario la chiude dall'API, un altro admin non la vede, non la prende e
non la chiude). `TerminalModesTest`, `OutputHistoryTest`, `ShellEnvironmentTest`; nella console
`Terminal.test.tsx`.

## 5. Revisione del 2026-10-07 — dopo l'uso dell'operatore

Due difetti segnalati: cambiando scheda della console il terminale si chiudeva, e la chat di Claude
Code non si poteva scorrere.

- **ConPTY davvero.** §1 diceva ConPTY, ma il codice non chiamava `setUseWinConPty(true)` e pty4j usa
  **winpty** per default. winpty legge la console nascosta e la ridisegna sul posto: ciò che esce in
  alto non arriva nella scrollback di xterm.js e le richieste del programma sugli eventi del mouse
  (`?1000h`…`?1006h`) non passano. Claude Code 2.1 lavora a schermo intero (`?1049h`) con uno scroll
  suo, guidato dalla rotella: senza quelle richieste xterm.js trasformava la rotella in frecce ↑/↓, che
  in Claude Code sfogliano i prompt precedenti. Ora il terminale usa il ConPTY che pty4j porta con sé
  (`conpty.dll` + `OpenConsole.exe` di Windows Terminal): colori, modalità e mouse passano, la rotella
  arriva a Claude Code come evento SGR e scorre la chat; nelle shell normali scorre la scrollback.
  Verificato dal vivo: byte del socket prima e dopo, evento rotella misurato fino al programma (Node e
  Claude Code), chat di Claude Code ripresa da una sessione sintetica e scorsa dalla console. Il `ready`
  dice il tipo di PTY e la console imposta `windowsPty` di xterm.js per ConPTY. Se ConPTY non si avvia,
  pty4j ripiega su winpty e il `ready` lo dice.
- **La vista resta montata.** La vista Terminale, una volta aperta, resta montata e si nasconde quando
  si va altrove: le shell restano mostrate mentre l'operatore usa le altre schede. La voce «Terminale»
  della barra laterale mostra quante shell mostra questa scheda del browser.
- **La shell vive sul server, non nel socket** (sostituisce l'ultima riga di §2; chiude **TD-91**). Il
  processo appartiene a una *shell* con un id casuale e un proprietario (chi ha chiesto il ticket); il
  socket la mostra soltanto. Un socket che se ne va senza `{"type":"close"}` — pagina ricaricata,
  scheda del browser chiusa, connessione caduta, ricaricamento a caldo della console in sviluppo —
  lascia la shell **staccata**, con gli ultimi 512 KiB di output e lo stato delle modalità del
  terminale (schermo alternativo, mouse, bracketed paste, focus, tasti cursore, cursore visibile).
  - **Riagganciare**: `POST /api/terminal/tickets {"attach": id}` dà un ticket monouso, solo per una
    shell propria (altrimenti `404 terminal-not-found`); il socket lo ricontrolla. Il `ready` dice
    `attached`; poi arrivano l'output perso (non per un programma a schermo intero, che si ridisegna:
    il suo vecchio contenuto finirebbe nello schermo normale), le modalità e una richiesta di
    ridisegno (una riga in meno, poi la misura vera: ConPTY e il programma ridisegnano lo schermo).
    Un secondo socket che aggancia la stessa shell la prende (l'altro si chiude con `4001`).
  - **La console**: ogni scheda del browser ricorda in `sessionStorage` (come l'indirizzo del control plane) le shell che
    mostra e dopo un ricaricamento le riaggancia da sé, anche se l'operatore è su un'altra pagina; le
    altre shell dell'operatore (di un'altra scheda, o di una scheda chiusa) sono elencate «In
    background» con *Riprendi qui* e *Chiudi* (`GET /api/terminal/sessions`,
    `DELETE /api/terminal/sessions/{id}`). Un socket caduto viene riagganciato (fino a 5 tentativi).
  - **Quando finisce**: quando il programma esce; quando l'operatore chiude la scheda nella console o
    esce dall'account (la console chiude le shell di quella scheda); quando è rimasta staccata più di
    `aicos.terminal.detached-ttl` (24 ore per default); quando il control plane si ferma. Nessun
    processo resta orfano: il limite di 4 shell conta anche quelle staccate.
- **L'ambiente delle shell è quello dell'operatore** (chiude **TD-92**). Il backend può essere avviato
  da qualunque cosa — anche da una sessione di Claude Code, che passa ai processi figli i suoi
  marcatori (`CLAUDECODE`, `CLAUDE_CODE_CHILD_SESSION`, un token di messaggistica…): un Claude Code
  aperto sotto quei marcatori si crede annidato e non salva la trascrizione. Su Windows l'ambiente
  delle shell è costruito come per un terminale aperto dal menu Start (`CreateEnvironmentBlock`:
  variabili di sistema e dell'utente dal registro, profilo); altrove, o se fallisce, è quello del
  backend. In entrambi i casi senza i marcatori di una sessione genitore; la configurazione
  (`CLAUDE_CODE_USE_BEDROCK`…) resta.
