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

## LOW
- L-1 Navigazione per hash, senza URL profonde per un task.
- L-2 Nessun test end-to-end in browser nella CI.
- L-3 Le tabelle agenti/progetti leggono la risorsa prima di ogni azione (TD-33).
