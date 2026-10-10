# ADR-024 — Persone, sessioni e autorizzazione

- **Stato**: Accettata e implementata (PHASE 15)
- **Data**: 2026-09-25
- **Decisa da**: agente, su direttiva umana del 2026-09-25 (§8 «Sicurezza minima necessaria»)
- **Estende**: ADR-013 (autenticazione a token), ADR-014 (CORS), ADR-007 (problem detail)

## 1. Decisione

Due tipi di chiamante, un solo meccanismo di trasporto:

| Chiamante | Credenziale | Come la ottiene |
|---|---|---|
| **Persona** | token di sessione `aicos_s_…` | `POST /api/auth/login` con utente e password |
| **Macchina** | token di servizio configurato | `aicos.security.api-tokens.<nome>` (opzionale) |

Entrambi viaggiano come `Authorization: Bearer`. Il filtro di ADR-013 resta: prima cerca una
sessione viva (il prefisso la distingue), poi un token di servizio. Il principal è un `Caller`
(nome, ruolo, tipo, utente, sessione); `getName()` resta ciò che il sistema registra come autore.

## 2. Password e sessioni

- **Password**: BCrypt, costo 12 (circa 0,25 s per verifica su questa classe di macchine). Regole:
  almeno 12 caratteri, al massimo 72 byte (oltre BCrypt ignorerebbe in silenzio), niente nome
  utente, niente password ovvie. Nessuna regola di composizione: è la lunghezza a fare la forza.
- **Sessione**: 256 bit casuali, consegnati una volta sola, conservati **solo come SHA-256**. Una
  copia della tabella non permette di entrare. Scadenza: 8 h di inattività o 72 h assolute
  (configurabili); revoca a logout, cambio password (tranne la sessione che l'ha cambiata),
  disattivazione dell'utente, reset della password da parte di un admin.
- **Tentativi**: 5 password errate bloccano l'account per 15 minuti (contatore persistito anche se
  la richiesta fallisce: `noRollbackFor`); al massimo 20 tentativi al minuto per indirizzo, in
  memoria. Utente inesistente, password errata, account bloccato o disattivato ricevono **la stessa
  risposta** (`401 invalid-credentials`) e costano lo stesso tempo (verifica BCrypt fittizia).

## 3. Ruoli

`OPERATOR` e `ADMIN`, niente di più. L'operatore lavora; l'admin in più:

- gestisce le persone (`/api/admin/users`), legge il registro (`/api/admin/security-events`);
- elimina davvero progetti e task (`DELETE /api/projects/*`, `DELETE /api/tasks/*`, PHASE 20);
- apre il terminale integrato (`/api/terminal/**`).

Le regole sono in **un solo posto**, `SecurityConfiguration` (`ADMIN_ONLY`, `ADMIN_ONLY_DELETES`).
Un rifiuto per ruolo è `403 access-denied` e viene registrato; un credenziale mancante resta `401`.
L'ultimo admin attivo non può essere disattivato né degradato (`409 last-admin`).

## 4. Il primo admin

Con nessun admin nel database, all'avvio viene creato `admin`:

1. con `aicos.security.admin.password` (ambiente `AICOS_ADMIN_PASSWORD` o file locale), se c'è;
2. altrimenti con una password casuale scritta **solo** in `<aicos.home>/admin-initial-password.txt`
   (fuori dal repository), marcata «da cambiare». Il log nomina il file, mai la password.

Recupero: `AICOS_ADMIN_RESET=true` con una password configurata la reimposta all'avvio.

## 5. Registro di sicurezza

`security_events` (append-only) e logger `aicos.security`: accessi riusciti e falliti, blocchi,
limitazioni, logout, cambi e reset di password, creazione dell'admin, gestione utenti, accessi
negati, eliminazioni di dati, processi avviati. In una transazione propria, così un rifiuto il cui
lavoro viene annullato resta registrato. I valori sono ripuliti dai caratteri di controllo (niente
righe di log contraffatte) e troncati. Mai password, mai token.

## 6. Segreti fuori dal repository

Il file locale `~/.aicos/local.env` (`KEY=VALUE`) contiene password admin iniziale e token
dell'engine, generati a caso da `scripts/init-local-secrets.ps1` e leggibili solo dall'account
Windows. Il control plane lo importa (`spring.config.import`, solo profilo `dev`), l'engine lo legge,
`start-dev.ps1` lo carica nelle finestre che apre. Le variabili d'ambiente vincono sempre. Il token
operatore di default `dev-operator-token-change-me` non esiste più: senza configurazione nessuna
macchina può chiamare.

## 7. CORS e CSRF

Invariati nella sostanza (ADR-013 §3, ADR-014): il token è in un header, mai in un cookie; il
browser non lo allega da solo, quindi non c'è superficie CSRF, e la protezione CSRF di Spring resta
spenta di proposito. CORS consente solo le origini dichiarate e nessuna richiesta con credenziali
del browser. Nuove intestazioni su ogni risposta: `Content-Security-Policy: default-src 'none';
frame-ancestors 'none'` (l'API non serve pagine), `Referrer-Policy: no-referrer`, oltre a
`X-Content-Type-Options`, `X-Frame-Options` e `Cache-Control` già messi da Spring Security.

## 7b. Niente utente di default

L'utente in memoria che Spring Boot crea quando non trova un `UserDetailsService` è escluso
(`spring.autoconfigure.exclude`): non serviva, e scriveva una password generata nel log — trovato nello
smoke dal vivo di PHASE 27, fissato da `AuthApiTest`. In produzione `/v3/api-docs` è spento.

## 7c. Il proprio account dalla console (review umana del 2026-09-25)

Il nome visualizzato è un campo **dell'account** (`PUT /api/auth/profile`, solo il proprio, registrato
come `USER_UPDATED`): la barra in alto, il saluto e l'elenco utenti leggono quello. Prima la console lo
salvava solo nel browser, e la barra in alto non cambiava mai. Il modulo della password controlla le
stesse regole del server mentre si scrive e dice cosa manca, invece di lasciare il pulsante grigio senza
spiegazione; i rifiuti del server arrivano in italiano.

## 8. Che cosa non fa, di proposito

Niente OAuth/OIDC, niente MFA, niente password reset via email: un prodotto locale con un
operatore non ne ha bisogno oggi, e ognuno aggiunge un servizio esterno. Il token di sessione vive
nel `sessionStorage` della scheda: un XSS nella console potrebbe leggerlo — la mitigazione è che la
console non inserisce HTML non fidato (React) e che la sessione è revocabile e a scadenza. Debito
dichiarato: TD-53.

## 9. Test

`AuthApiTest` (login, stessa risposta per utente ignoto e password errata, blocco dopo 5 errori,
logout, cambio password con revoca delle altre sessioni, ruoli, ultimo admin, disattivazione che
chiude le sessioni, token contraffatto, intestazioni), `LoginThrottleTest`,
`ApiAuthenticationContractTest` (ogni rotta rifiuta l'anonimo, tranne il login),
`ApiTokenRegistryTest`, e le coperture `ApiProblemCoverageTest` e `PreconditionCoverageTest`.

## Revisione PHASE 32 (2026-10-10) — il token della console in un cookie `HttpOnly` (TD-53)

Su decisione dell'operatore (2026-10-10) il §8 cambia: il token di sessione della console non vive più nel
`sessionStorage`, dove uno script iniettato poteva leggerlo, ma in un cookie che nessuno script vede.

- **Accesso dal browser**: `POST /api/auth/session` (pubblico, stessi controlli di `/api/auth/login`: blocco, limite
  per indirizzo, registro) imposta il cookie `aicos_session_<porta>` — `HttpOnly`, `SameSite=Strict`, `Path=/api`,
  `Secure` se la richiesta è HTTPS, senza `Max-Age` (finisce con il browser; inattività e durata massima del §2
  restano) — e risponde **chi è entrato e il token anti-CSRF**, mai il token di sessione. La porta nel nome tiene
  separati due control plane sullo stesso host: il browser conserva i cookie per host, non per porta.
- **CSRF**: il cookie il browser lo allega da sé, quindi ogni scrittura (tutto tranne GET, HEAD, OPTIONS)
  autenticata dal cookie porta `X-AICOS-CSRF` con il token anti-CSRF della sessione (SHA-256 di
  `"aicos-csrf:" + token`: non rivela il token, ed è diverso dal digest salvato). Senza, o sbagliato:
  `403 csrf-rejected` e una voce `ACCESS_DENIED` nel registro. Un'altra origine non può aggiungere quell'header
  senza un preflight CORS che il control plane rifiuta, e non conosce il valore.
- **CORS** (cambia ADR-013 §3 e il §7 qui sopra): richieste con credenziali **ammesse solo dalle origini
  dichiarate**, `X-AICOS-CSRF` tra gli header permessi. Il `*` resta rifiutato all'avvio.
- **Il cookie** non è mai un token di servizio; uno che non corrisponde a una sessione viva (sessione finita,
  altro control plane) è **ignorato**, non rifiutato: le rotte pubbliche rispondono, le altre danno il `401` di
  sempre. Sulle rotte di accesso il cookie non si legge: una scheda può rientrare anche se il browser ne tiene
  uno valido.
- **`GET /api/auth/session`**: con il cookie restituisce utente e token anti-CSRF. Serve a una scheda nuova
  (entra da sola se il browser ha già la sessione) e a una scheda il cui token anti-CSRF non è più quello del
  cookie perché un'altra scheda è entrata: la console lo chiede una volta e ripete la richiesta.
  Con un token `Bearer` risponde `409 not-a-browser-session`.
- **Logout**: revoca la sessione e scade il cookie (`Max-Age=0`).
- **`Bearer` resta** per script, servizi e test: `POST /api/auth/login` restituisce ancora il token, e i token di
  servizio non cambiano. È la console che non lo vede più.

**Diverso dalla proposta**: non serve servire la console dallo stesso origin del backend. Console
(`localhost:5173`) e control plane (`localhost:8081`) sono *same-site* (i porti non contano), quindi il cookie
`Strict` viaggia con `credentials: "include"` e CORS con credenziali. Basta che i due abbiano **lo stesso nome
host**: la console ora propone il control plane sull'host da cui è aperta, e se il browser non rimanda il cookie
(`localhost` con `127.0.0.1`) lo dice all'accesso invece di uscire al primo clic.

La console tiene nel `sessionStorage` solo indirizzo, utente e token anti-CSRF, e scarta una sessione salvata da
una versione precedente che conteneva un token.

Test: `BrowserSessionApiTest` (cookie `HttpOnly`/`Strict`/`Path=/api` e nessun token nel corpo; letture con il
cookie, scritture rifiutate senza o con un token anti-CSRF sbagliato e registrate; `GET /api/auth/session`;
logout che scade il cookie; cookie scaduto ignorato, mai un token di servizio, mai quello di un'altra porta;
nuovo accesso con un cookie valido), `CorsPolicyTest` (credenziali solo per l'origine dichiarata),
`ApiAuthenticationContractTest`, `ApiProblemCoverageTest`; console: `client.test` (cookie e token anti-CSRF solo
sulle scritture, mai `Authorization`; riallineamento dopo un'altra scheda, una volta sola; sonda di salute senza
cookie), `Login.test`, `session.test`, `App.test` (una scheda nuova entra con il cookie). Ogni protezione vista
rossa per mutazione.
