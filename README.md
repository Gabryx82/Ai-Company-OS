# AI Company OS

A local-first control plane for running a software company with AI agents, with a human at the
centre: projects, agents, tasks, and agents executing tasks through local or cloud models — every
step recorded, every result reviewed by the operator before the work is closed.

## What exists today

| Part | Stack | Where |
|---|---|---|
| **Control plane** | Java 21, Spring Boot 4.1, PostgreSQL 17, Flyway | `backend/` |
| **AI Engine** (model gateway) | Python ≥ 3.11, FastAPI; providers `echo`, Ollama (local), Anthropic (cloud, off without a key) | `ai-engine/` |
| **Operator console** | React 19, TypeScript, Vite | `frontend/` |
| **API contract** | OpenAPI, generated from the code and held to it by a test | `docs/api/openapi.json` |

## Start it

Prerequisites: Docker Desktop, JDK 21, Python 3.11+, Node 22+. Optional: [Ollama](https://ollama.com)
with a model (`ollama pull llama3.2:3b`) for real local completions.

```powershell
.\scripts\start-dev.ps1
```

Then open <http://localhost:5173> and sign in with the operator token (`dev-operator-token-change-me`
in development unless `AICOS_OPERATOR_TOKEN` is set). Manual steps, every endpoint, profiles and the
database: [`docs/RUNNING.md`](docs/RUNNING.md).

## How it is built

Decisions are ADRs in [`docs/adr/`](docs/adr/); the state of the project is
[`.company-os/PROJECT_STATE.md`](.company-os/PROJECT_STATE.md). CI runs the three suites — control
plane, engine, console — on every push.
