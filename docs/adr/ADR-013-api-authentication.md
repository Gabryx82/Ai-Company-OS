# ADR-013 — Autenticazione dell'API: bearer token per nome, stateless, deny by default

- **Stato**: Accettata e implementata (TASK-013)
- **Data**: 2026-09-23
- **Decisa da**: agente, in Autonomous Project Mode, entro il blocco PHASE 3–7 autorizzato
  esplicitamente il 2026-09-23
- **Chiude**: TD-04 (entrambi gli spazi: coincide)
- **Contesto**: `MIGRATION_MAP.md` M-7, `ROADMAP_PHASES_3_7.md` §3, `PHASE_3_PLAN.md`

## 1. Problema

Nessuna autenticazione: chiunque raggiunga la porta del backend crea, modifica, archivia. Le fasi
successive aggiungono esecuzione di modelli (costo) e un client browser. `MIGRATION_MAP.md` M-7:
«da anticipare se prima di M-7 vengono introdotte credenziali di provider».

## 2. Decisione

**Bearer token configurati per nome**: `aicos.security.api-tokens.<nome>=<token>`. Il nome è il
*principal*. Una richiesta porta `Authorization: Bearer <token>`.

| Alternativa | Perché no, adesso |
|---|---|
| Utenti persistiti con password (form login / Basic) | Serve un modo di creare il primo utente: un endpoint non autenticato o un passo SQL manuale. Password digitate da persone richiedono hashing lento, lockout, reset — prodotto che nessun requisito chiede. `PRODUCT_VISION.md`: *local-first*, un operatore al centro |
| Token in una tabella | Stesso problema del primo token; e un token in tabella va emesso da qualcuno già autenticato |
| JWT firmati | Risolvono la verifica senza stato *fra servizi diversi*; qui c'è un solo verificatore e uno stato già centrale. Aggiungerebbero una chiave di firma da custodire e la revoca come problema aperto |
| OAuth2 / OIDC | Richiede un identity provider: un sistema esterno (hard stop #4) e una scelta di prodotto |

La configurazione ha già un canale sicuro — l'ambiente — che l'operatore usa per la password del
database. Un meccanismo per nome **non preclude** utenti veri: il nome del token è già ciò che
diventerà l'identità di un utente, e «Team identity / RBAC» resta la sua fase
(`IMPLEMENTATION_PLAN.md` fase 5).

## 3. Forma

1. **Stateless**: nessuna sessione, nessun cookie. Per questo CSRF è disattivato: CSRF abusa di
   una credenziale che il browser allega da sé, e un bearer token non è mai allegato dal browser.
2. **Deny by default**: tutto richiede un principal, compresi i path inesistenti. Unica eccezione
   `/actuator/health`, che risponde solo `UP`/`DOWN` — niente componenti, dettagli o versione.
3. **Nessun form login, Basic, logout**: i default di Boot li aggiungerebbero, ed ognuno è una
   seconda porta che nessuno ha deciso.
4. **Un solo ruolo**, `ROLE_OPERATOR`. Nessuna RBAC: nessuna rotta oggi distingue fra chiamanti, e
   un `403` che non può scattare sarebbe contratto non verificabile.
5. **Fail closed**: nessun token, un token sotto i 16 caratteri, o lo stesso token sotto due nomi
   → l'applicazione **non parte**. In `prod` il token viene solo dall'ambiente, senza default.
6. **Confronto a tempo costante** su digest SHA-256, visitando sempre tutte le voci. In memoria
   restano solo i digest.

## 4. Contratto

- `401`, `type = urn:ai-company-os:problem:unauthenticated`, `WWW-Authenticate: Bearer`,
  `application/problem+json`. **Dentro ADR-007**: l'eccezione nasce nella filter chain e viene
  consegnata ai resolver MVC, quindi la rende `ApiExceptionHandler` — un solo punto definisce il
  contratto.
- **Token assente e token sbagliato ricevono risposte identiche byte per byte**: distinguerle dice
  a chi sonda che ha trovato l'header giusto.
- **Un token presentato e sbagliato è rifiutato anche dove non serve** (health): un client con un
  token stantio deve saperlo. Trovato per mutazione (TASK-013 `ARTIFACT.md` §4).
- **Ordine**: autenticazione **prima** di esistenza (`404`) e **prima** della precondizione
  (`428`). Un `428` a un anonimo gli insegnerebbe come scrivere.

## 5. Conseguenze

- **Rottura dichiarata**: ogni richiesta a `/api/**` senza token valido passa da `2xx/4xx` a `401`.
- Nessun test esistente è cambiato: il `MockMvc` condiviso porta il token dell'operatore come
  default, come farebbe un client reale.
- Lo schema non cambia. Nessuna migrazione.
- `dev` ha un token di default (`dev-operator-token-change-me`), con lo stesso status della
  password del database di sviluppo; si sovrascrive con `AICOS_OPERATOR_TOKEN`.

## 6. Fuori scope

Utenti, password, RBAC, OAuth/SSO, rotazione e scadenza dei token, rate limiting, audit log delle
richieste. Il nome del principal è disponibile a chiunque lo voglia registrare (PHASE 6 lo usa).
