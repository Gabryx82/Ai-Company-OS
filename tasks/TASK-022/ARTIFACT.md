# TASK-022 — Artefatto

Test console **14** (Vitest), build di produzione 251 kB JS (76 kB gzip). Suite Java **326 → 327**
(il test di CORS sull'health).

## Verifica per mutazione (albero pulito prima e dopo)
| # | Mutazione | Esito |
|---|---|---|
| C1 | niente CORS sull'health | rosso (Java) |
| C2 | il client non manda `If-Match` | rosso (4) |
| C3 | un `412` non rilegge il task | rosso |
| C4 | offerti tutti gli archi | rosso |
| C5 | lettura senza `ETag` accettata | rosso |

**Harness**: la prima mutazione del frontend non ha dato verdetto — l'output di `npm` non era
decodificabile nella codepage di Windows. Forzato UTF-8; poi i verdetti sopra.

## Verifica dal vivo (2026-09-23)
Stack avviato: AI Engine, backend (`dev`, su **clone** `aicompany_smoke` del DB reale), Vite su
`localhost:5173`, aperto nel browser integrato. La pagina di login si rende (tema scuro di sistema).
Dall'origine della console, senza credenziali: `401` leggibile da script con `type` stabile;
preflight di un `PUT` con `If-Match` accettata.

**Ha trovato un difetto che nessun test vedeva**: `GET /actuator/health` dall'origine della console
falliva (`Failed to fetch`), perché la policy CORS copriva solo `/api/**` — l'indicatore di stato
avrebbe detto «down» a un backend acceso. Corretto (`CorsPolicy` registra l'health in sola lettura),
pinnato (`theLivenessProbeIsReadableByTheConsole`), riverificato nel browser: `200 UP`.

**Non verificato dal vivo, e detto**: il percorso autenticato nel browser. Inserire un token in un
campo del browser è fuori da ciò che l'agente fa, anche per un token di sviluppo; è coperto dai test
di componente e dal flusso API end-to-end di TASK-020, e va provato a mano (FINAL_HANDOFF §7).

## Trovato dalla review umana (2026-09-23) — e che l'agente avrebbe dovuto prevenire

Il primo avvio reale di `scripts/start-dev.ps1` ha lasciato la console su **«Control plane
unreachable»**. Non era il token: **il backend non era mai partito.** `mvnw.cmd` invoca `powershell`
per nome, e su questa macchina la cartella di Windows PowerShell non è nel `PATH`; la finestra del
backend moriva con «Cannot start maven from wrapper», e lo script stampava comunque gli URL.

È lo stesso difetto che l'harness di mutazione aveva incontrato in TASK-013 — e che lì era stato
**aggirato** (`bash ./mvnw`) invece di essere registrato come rischio per chi usa `mvnw.cmd`. Lezione:
un aggiramento nel proprio strumento è un difetto trovato nel prodotto, e va scritto.

Correzioni allo script (nessuna modifica al PATH di sistema):
- le finestre partono col percorso completo di `powershell.exe` e con `$PSHOME` anteposto al **loro**
  `PATH`;
- un servizio già in ascolto non viene riavviato, così lo script si può rilanciare;
- se il control plane non arriva a `UP`, lo script lo dice in rosso ed esce con `1`, invece di
  stampare gli URL;
- lo stderr di `docker` non è più un errore bloccante sotto Windows PowerShell 5.1;
- l'attesa interroga `127.0.0.1`. Con `localhost` il tentativo IPv6 rifiutato costa ~2 s, pari al
  timeout di 2 s di ogni sondaggio: **ogni** sondaggio scadeva, e la versione intermedia dello script
  ha dichiarato «did not come up» con il backend `UP`. Rilanciato dopo la correzione: `exit 0`.

Riverificato: backend avviato dallo script su `aicompany_try` (migrato `V1→V11`), token dev `200`,
dal browser su `http://localhost:5173` health `200` e API anonima `401` leggibile.

## LOW
- L-1 Navigazione per hash, senza URL profonde per un task.
- L-2 Nessun test end-to-end in browser nella CI.
- L-3 Le tabelle agenti/progetti leggono la risorsa prima di ogni azione (TD-33).
