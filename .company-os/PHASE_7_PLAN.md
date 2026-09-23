# PHASE 7 — Operator console

> Piano della fase. Stato in `PROJECT_STATE.md`, sequenza in `ROADMAP_PHASES_3_7.md`.

- **Integration branch**: `autonomous/phase-7-operator-console`, da `autonomous/phase-6-execution`.
- **Baseline**: 321 test Java, 51 engine, stream `V11`.

## Obiettivo
> **Un operatore può fare, da un browser, tutto ciò che il control plane permette: creare lavoro,
> assegnarlo, farlo eseguire da un agente, leggerne l'esito e chiuderlo.**

M-6, e la metà OpenAPI di M-3 che M-6 presuppone («client tipizzato generato da OpenAPI»).

## Scope
| Task | Contenuto |
|---|---|
| **TASK-021** | springdoc; `docs/api/openapi.json` tenuto al codice; `GET /api/engine/models` |
| **TASK-022** | Console React/TS/Vite; tipi generati; job CI; `scripts/start-dev.ps1` |

Nota: TASK-021 e TASK-022 sono stati sviluppati sullo stesso branch di task,
`task-021-openapi-and-engine-models`, con commit separati. Il charter chiede un branch per task;
qui la seconda dipendeva in modo stretto dal file prodotto dalla prima, ed è registrato invece di
essere nascosto.

## Criterio di chiusura
1. i tipi del frontend derivano dal contratto, e una deriva rende rossa la CI;
2. il client rispetta i tre protocolli del server, verificato per mutazione;
3. la console è avviata dal vivo contro backend ed engine reali;
4. suite Java, engine e console verdi in locale e in CI.
