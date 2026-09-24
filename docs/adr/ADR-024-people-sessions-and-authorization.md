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
