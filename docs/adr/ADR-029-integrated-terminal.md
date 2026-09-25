# ADR-029 — Terminale integrato nella console

- **Stato**: Accettata e implementata (PHASE 22)
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
- chiudere la scheda o il socket termina il processo.

## 3. Protocollo

Dal browser, frame di testo JSON: `auth` (ticket, colonne, righe), `input` (tasti), `resize`. Dal
server: l'output come frame binari (byte grezzi, decodificati da xterm.js), `ready` con la cartella,
`exit` con il codice di uscita, `error`.

## 4. Test

`TerminalSocketTest` (server su porta casuale, PTY reale con un comando di test innocuo abilitato solo
da una proprietà dei test: output che torna, codice di uscita, ticket monouso, niente prima del
ticket, operatore senza ticket).
