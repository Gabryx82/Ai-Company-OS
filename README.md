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
| **Operator console** | React 19, TypeScript, Vite, d3-force, React Flow, three.js (React Three Fiber) | `frontend/` |
| **API contract** | OpenAPI, generated from the code and held to it by a test | `docs/api/openapi.json` |

The console covers: dashboard, projects (type, stack, workspace folder, master prompt, plan,
phase approvals, documents, the **Blueprint** -- a node editor of the project's components and
their relations, read from the code and editable, whose code changes become proposals and tasks --,
code graph, visual references), tasks with the orchestrator's decision,
a readiness check before every handoff and run -- for that agent and that tool, what it will find: context files,
skills and knowledge with their copy in the tool's own folder, MCP servers written or to configure by hand, model and
binding, each ok, a warning or blocking, and a blocker stops the delivery before anything is written (ADR-042) --,
handoffs and reviews, agents and sub-agents with their Agent → Model → Provider → Execution Target
binding, prompt engineering and harness, the **Ecosistema 3D** -- every agent and what defines it (model,
provider, skills, knowledge, MCP, plugins, prompts, directives, roles, sub-agents) as an interactive 3D map
read from the live configuration (ADR-037) --, a **file explorer** opened from the top bar that shows every file
of a project's folder without an IDE (ADR-039) -- with a right-click menu to rename, create, move to the Windows
Recycle Bin and open a file with the Software Hub program that suits it, suggested from its type (ADR-040) --, Daily Work, the Second Brain graph, the Software Hub
(detection and launch of the programs installed on this machine), an integrated terminal
(PowerShell, Claude Code, OpenCode), integrations (Open WebUI and 3D Omniverse embedded, started
with the system), a Knowledge Hub of skills kept as editable Markdown files -- which also shows the
skills of Claude Code, Claude Desktop, Codex and the other agent apps on the machine, installs new ones
from online marketplaces, keeps copies in sync and checks for updates --, a Mockup Hub, models,
usage and costs with budgets (Claude Code, Codex, Antigravity's per-model quotas, Kimi Code, the billed runs;
only figures something measured) and the agents' own consumption -- per run, model, agent and handoff: tokens and
the durations Ollama reports, prompt and package sizes in characters (ADR-041) --, and people with roles and a
security log.

Local models are kept lean (ADR-041): an engine run carries at most the two skills pertinent to its task, each by
its `## Essenziale` section, after a stable prompt prefix Ollama can reuse; the task document reaches it without
its outcome history, which lives in `tasks/TASK-NNN.esito.md`; each local model runs with the window and keep-alive
the catalog gives it, one at a time; and a task nobody chose a model for goes to the model of its role -- short
work to the small model, planning and code to the 9B.

Claude Code, Codex, Antigravity, OpenCode and the agentic IDEs are used through their own apps and
CLIs, never through the OpenAI or Anthropic APIs: the Master Orchestrator prepares the folder, the
context, the role and a compact prompt, then opens the tool -- naming the skills the tool loads by itself, after
checking with the Knowledge Hub that its copy is there.

AI Company OS is where agents are made, and the tools around it receive them (ADR-043): from **Agenti → Ecosistema**
an agent becomes an OpenClaw agent with its own workspace, an Open WebUI model with its system prompt, a LiteLLM alias
-- each written through the tool's own commands, API or file, only when asked, and only what was made here. OpenClaw
is also a place to hand a task to, and its own agents can be brought in. Its own interface is in the console too
(ADR-045): the **OpenClaw** page shows the gateway, the agents, a conversation with one of them and the recent
sessions, takes a session up again in `openclaw tui`, and opens the full Control UI -- which refuses to be framed --
in the browser through `openclaw dashboard`. No page waits for a slow tool (ADR-046): OpenClaw's agents are read in
the background and shared by both pages, the catalog is detected at startup, and embedded apps stay loaded.

Learning is done, not read (ADR-044): when a task is handed over the operator says how they want to work on it. The
agent writes a lesson -- the code, explained line by line -- and the operator copies each step into its file; the
**Impara** page asks for a prediction first, checks each step against the project's folder, then a quiz, a blank to
fill, an error to find and an explanation in the operator's own words; the concepts of a finished lesson come back as
review cards. When the operator cannot follow, the agent works alone and leaves the lesson to go through after.

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
(Open WebUI is the Docker container on 3000, embedded under *Chat LLM*). Manual steps, every endpoint, profiles and the database:
[`docs/RUNNING.md`](docs/RUNNING.md).

## How it is built

Decisions are ADRs in [`docs/adr/`](docs/adr/); the state of the project is
[`.company-os/PROJECT_STATE.md`](.company-os/PROJECT_STATE.md), the plan ahead
[`.company-os/ROADMAP_V2.md`](.company-os/ROADMAP_V2.md). CI runs the three suites — control
plane, engine, console — on every push.
