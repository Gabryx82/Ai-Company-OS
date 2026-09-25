# AI Company OS

A local-first operating environment for building software with AI, with a human at the centre.
The **Master Orchestrator** turns a project's master prompt into an implementation plan of phases
and tasks, chooses agent, model, software and context for each task, and hands the work to the
local AI Engine or to the operator's own tools — Claude Code, Codex, Antigravity, OpenCode — while
the operator approves plans and phases and reviews every outcome.

## What exists today

| Part | Stack | Where |
|---|---|---|
| **Control plane** | Java 21, Spring Boot 4.1, PostgreSQL 17, Flyway | `backend/` |
| **AI Engine** (model gateway) | Python ≥ 3.11, FastAPI; providers `echo`, Ollama (local), Anthropic and OpenRouter (cloud, off without a key) | `ai-engine/` |
| **Operator console** | React 19, TypeScript, Vite, d3-force | `frontend/` |
| **API contract** | OpenAPI, generated from the code and held to it by a test | `docs/api/openapi.json` |

The console covers: dashboard, projects (type, stack, workspace folder, master prompt, plan,
phase approvals, documents, code graph, visual references), tasks with the orchestrator's decision,
handoffs and reviews, agents and sub-agents with their Agent → Model → Provider → Execution Target
binding, prompt engineering and harness, Daily Work, the Second Brain graph, the Software Hub
(detection and launch of the programs installed on this machine), an integrated terminal
(PowerShell, Claude Code, OpenCode), integrations (Open WebUI and 3D Omniverse embedded, started
with the system), a Knowledge Hub of skills kept as editable Markdown files, a Mockup Hub, models,
usage and costs with budgets, and people with roles and a security log.

Claude Code, Codex, Antigravity, OpenCode and the agentic IDEs are used through their own apps and
CLIs, never through the OpenAI or Anthropic APIs: the Master Orchestrator prepares the folder, the
context, the role and a compact prompt, then opens the tool.

## Start it

Prerequisites: Docker Desktop, JDK 21, Python 3.11+, Node 22+. Optional: [Ollama](https://ollama.com)
with a model (`ollama pull qwen3.5:4b`) for real local completions.

```powershell
.\scripts\start-dev.ps1
```

Then open <http://localhost:5173> and sign in as `admin`. On first use the script creates the local
secrets file `%USERPROFILE%\.aicos\local.env` — outside the repository — with a random admin
password, and prints it; `.\scripts\init-local-secrets.ps1 -Show` prints it again. Change it from
the console (*Account e sicurezza*). The control plane listens on port **8081**
(8080 belongs to Open WebUI). Manual steps, every endpoint, profiles and the database:
[`docs/RUNNING.md`](docs/RUNNING.md).

## How it is built

Decisions are ADRs in [`docs/adr/`](docs/adr/); the state of the project is
[`.company-os/PROJECT_STATE.md`](.company-os/PROJECT_STATE.md), the plan ahead
[`.company-os/ROADMAP_V2.md`](.company-os/ROADMAP_V2.md). CI runs the three suites — control
plane, engine, console — on every push.
