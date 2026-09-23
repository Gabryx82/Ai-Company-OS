# TASK-013 — Autenticazione dell'API

**Fase**: PHASE 3 — Security baseline. **Debito**: TD-04. **ADR**: ADR-013.

## Problema
Nessuna rotta richiede credenziali. Chiunque raggiunga la porta del backend scrive.

## Invarianti
- **I-1** Nessuna rotta `/api/**` risponde a un chiamante senza credenziale valida con qualcosa
  di diverso da `401`. *Falsificabile*: una rotta nuova fuori dalla chain.
- **I-2** Il `401` è un `ProblemDetail` di ADR-007 (`type` stabile, `problem+json`) con
  `WWW-Authenticate: Bearer`.
- **I-3** Token assente e token sbagliato sono indistinguibili dall'esterno.
- **I-4** L'autenticazione precede esistenza (`404`) e precondizione (`428`).
- **I-5** L'applicazione non parte senza almeno un token valido (fail closed).
- **I-6** L'unica rotta pubblica è l'health, e non rivela nulla oltre `UP`/`DOWN`.

## Fuori scope
Utenti, password, RBAC, OAuth, scadenza/rotazione dei token (ADR-013 §6).
