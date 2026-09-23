# TASK-014 — Artefatto

TD-11 **chiuso**; R5 e R7 **chiusi**. Suite **238 → 244**. Nessuna migrazione.

## Verifica per mutazione
| # | Mutazione | Esito |
|---|---|---|
| M1 | rimosso `.cors(withDefaults())` | **verde — mutante equivalente**: Spring Security 7 applica CORS da sé quando esiste un bean `CorsConfigurationSource`. La riga resta per leggibilità, e non è ciò che protegge |
| M1b | sorgente CORS vuota | **rosso** (4) |
| M2 | `ETag` non esposto | **rosso** |
| M3 | controllo del wildcard rimosso | **verde → sopravvissuta**: `*` fallisce comunque il controllo di origine, e il test guardava solo il valore nel messaggio. Test stretto sulla *ragione*; rieseguita: **rosso** |
| M4 | `allowCredentials(true)` | **rosso** |

## Smoke test reale (2026-09-23)
Jar avviato in `dev` su **`aicompany_smoke`, clone del database reale** (`CREATE DATABASE … TEMPLATE
aicompany`): `V1→V8` applicate al clone; `/actuator/health` → `UP`; anonimo → `401`,
`WWW-Authenticate: Bearer`, `application/problem+json`; token di default `dev` → `200`; token da
**variabile d'ambiente** `AICOS_SECURITY_APITOKENS_SMOKE` → `200` (il binding della mappa
dall'ambiente funziona, senza trattino); preflight da `http://localhost:5173` → `200` con `ETag,
Location` esposti; **listen su `127.0.0.1:8080`** soltanto. Database reale **ancora a `V1,V2,V3`**.

## LOW
- L-1 L'health espone `groups: [liveness, readiness]`. Innocuo; nessun componente né dettaglio.
