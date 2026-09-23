# TASK-017 — Implementazione

Vedi `ARTIFACT.md` §Implementazione per i file. Scelte locali:

- `from_environment` legge l'ambiente **una volta**, all'avvio; nessuna richiesta rilegge la config.
- `RequestValidationError` è rimappata a `400 validation-failed` con `errors` per campo, per
  allinearsi al control plane (ADR-007); FastAPI risponderebbe `422` in un formato proprio.
- L'auth è una dependency FastAPI, quindi scatta **prima** del parsing del corpo: un anonimo non
  impara lo schema dai `400`.
- `docs_url`/`openapi_url` disattivati: nessuna mappa del contratto a chi non ha il token.
