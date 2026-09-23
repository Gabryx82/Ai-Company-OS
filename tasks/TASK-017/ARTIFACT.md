# TASK-017 — Artefatto

## Implementazione
| File | Ruolo |
|---|---|
| `ai-engine/app/config.py` | `Settings` dall'ambiente, profili, fail closed |
| `ai-engine/app/contract.py` | DTO v1 (pydantic, `extra="forbid"`) |
| `ai-engine/app/problems.py` | `EngineProblem`, rendering problem+json |
| `ai-engine/app/gateway.py` | Routing per id di modello |
| `ai-engine/app/main.py` | App FastAPI: middleware di correlazione, auth, handler d'errore, rotte |
| `ai-engine/app/providers/echo.py` | Provider deterministico |
| `ai-engine/app/__main__.py` | `python -m app`, bind `127.0.0.1:8090` |
| `.github/workflows/ci.yml` | Job `ai-engine` separato (Python 3.12) |

Test: `ai-engine/tests/test_contract.py` — **30**. Suite Java invariata (277).

**Rosso prima**: non applicabile nel senso letterale — sulla baseline il servizio non esisteva e
nessun test poteva essere eseguito. Il ruolo del rosso lo ha la verifica per mutazione.

## Verifica per mutazione (albero pulito prima e dopo)
| # | Mutazione | Esito |
|---|---|---|
| E1 | niente auth su `/v1/completions` | rosso (4) |
| E2 | qualunque token accettato | rosso |
| E3 | niente mappatura `422 → 400` | rosso (5) |
| E4 | correlation id non filtrato | rosso |
| E5 | niente troncamento a `max_tokens` | rosso |
| E6 | campi sconosciuti ignorati | rosso |

**Harness**: due giri senza verdetto prima di quello valido — interprete con percorso relativo, poi
`-q` doppio (`addopts`) che sopprimeva il riepilogo. In entrambi i casi l'harness ha **rifiutato**
di dare un verdetto invece di chiamarlo rosso: la correzione di TASK-013 ha funzionato.
