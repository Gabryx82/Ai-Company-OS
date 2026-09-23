# TASK-014 — Implementazione

| File | Ruolo |
|---|---|
| `security/CorsPolicy` | Valida le origini (no wildcard, solo `scheme://host[:port]`), costruisce la `CorsConfiguration` su `/api/**` |
| `security/SecurityConfiguration` | Bean `CorsPolicy` e `CorsConfigurationSource`; `.cors(withDefaults())` |
| `application-dev.properties` | `server.address=${SERVER_ADDRESS:127.0.0.1}`; origini del dev server Vite |
| `application-prod.properties` | Nessuna origine di default |
| `.gitignore` | `.env.*` con eccezione `!.env.example` |

Test: `CorsPolicyTest` (6). Rosso prima: il commit di test non compilava (`CorsPolicy` assente).
