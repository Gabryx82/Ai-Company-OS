# PHASE 3 — Security baseline

> Piano della fase. Stato in `PROJECT_STATE.md`, sequenza del blocco in `ROADMAP_PHASES_3_7.md`.

- **Integration branch**: `autonomous/phase-3-security`, da `master` (`d166870`).
- **Baseline verificata il 2026-09-23**: `./mvnw -B clean test` → **223 test, 0 failure**, stream
  `V8`, live dev DB `V3`, CI verde sulla run `35863517006`.

## 1. Obiettivo

> **Nessuna richiesta all'API cambia o legge stato senza che il control plane sappia chi la fa, e
> un browser può parlarci solo dalle origini che qualcuno ha dichiarato.**

Oggi chiunque raggiunga la porta 8080 può creare, modificare e archiviare. Finché il database era
l'unico asset era un rischio contenuto dal bind su loopback del *database*; il backend invece
ascolta su tutte le interfacce (rilievo R5 di TASK-001). Le fasi successive aggiungono esecuzione
di modelli — cioè costo — e un client browser.

## 2. Scope

| Task | Contenuto | Debiti |
|---|---|---|
| **TASK-013** | Spring Security stateless; bearer token configurati per nome; contratto `401` dentro ADR-007; health pubblico; copertura verificata per riflessione su **ogni** rotta | TD-04 |
| **TASK-014** | Policy CORS esplicita e configurabile (con `ETag`/`If-Match` esposti, senza i quali la concorrenza ottimistica è inutilizzabile da un browser); bind del backend su loopback in `dev`; `.gitignore` per `.env.*` | TD-11, R5, R7 |

**Fuori scope, dichiarato.** Utenti persistenti, password, ruoli multipli, RBAC, OAuth/SSO: è la
«Team identity / RBAC» di `IMPLEMENTATION_PLAN.md` fase 5. Il prodotto è *local-first* con un
operatore al centro (`PRODUCT_VISION.md`); un meccanismo a token per nome non preclude utenti veri
più avanti — il nome del token è già il *principal*.

## 3. Criterio di chiusura

1. una richiesta senza credenziali valide a qualunque rotta `/api/**` riceve `401` con
   `type = urn:ai-company-os:problem:unauthenticated` — verificato su **tutte** le rotte registrate,
   non su un campione, e per mutazione;
2. l'applicazione **non parte** senza almeno un token configurato (fail closed);
3. una preflight CORS da un'origine dichiarata passa e da una non dichiarata no; `ETag` è leggibile
   da JavaScript;
4. suite verde in locale e in CI.
