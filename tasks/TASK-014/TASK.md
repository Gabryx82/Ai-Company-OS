# TASK-014 — CORS e bind di rete

**Fase**: PHASE 3. **Debiti**: TD-11, rilievi R5 e R7 della review di TASK-001.

## Invarianti
- **I-1** Una preflight da un'origine dichiarata passa **senza credenziale**; da una non dichiarata
  è rifiutata e non riceve `Access-Control-Allow-Origin`.
- **I-2** `ETag` e `Location` sono leggibili da script; `If-Match` è accettato.
- **I-3** Un'origine dichiarata non è una credenziale: la richiesta reale richiede comunque il token.
- **I-4** Nessuna richiesta *credentialed*.
- **I-5** `*` o un valore che non è un'origine impediscono l'avvio.
- **I-6** In `dev` il backend ascolta solo su loopback.
- **I-7** `.env.*` è ignorato, `.env.example` no.
