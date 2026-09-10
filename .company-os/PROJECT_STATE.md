# AI Company OS — Project State

## Project
AI Company OS

## Status
Existing project. Initial architecture audit **completed** (TASK-000, 2026-09-11).
Awaiting differential review by Codex and human approval before TASK-001.

## Current phase
PHASE 0 — Existing System Audit (audit done, review pending)

## Current task
None active. TASK-000 closed; TASK-001 not started.

## Last completed task
TASK-000 — Existing Repository Audit.
Artifacts: `docs/audit/*` (7 files), `tasks/TASK-000/{IMPLEMENTATION,ARTIFACT,HANDOFF}.md`.

## Audit outcome (summary)
- Only application code in the repository: `backend/` — Spring Boot 4.1, Java 21, 13 Java files, ~350 lines.
- Three embryonic contexts: `agent` (read-only), `task` (partial CRUD), `ai.orchestrator` (keyword stub, **no AI**).
- Persistence: H2 in-memory, `ddl-auto=update`, no migrations. PostgreSQL driver present but unused.
- Absent although declared in `README.md`: frontend, Python/LangGraph service, Supabase.
- Coverage of the 25 target domains: **< 2%** (Agents ~10%, Task Engine ~8%).
- Build and tests green (`./mvnw -B test`, exit 0); functional test coverage effectively zero.
- Verdict: **do not rewrite**. The repository is a healthy skeleton, not a legacy system. One targeted REPLACE only: `MasterOrchestrator`.

## Open decisions requiring human approval
- **ADR-001** — keep Spring Boot as the control plane instead of migrating to FastAPI (as suggested in `docs/MASTER_PROMPT.md`), adding a separate Python service for the AI layer at M-5. See `docs/audit/MIGRATION_MAP.md` §5.
- **TASK-001 scope** — proposed: persistence and domain foundation (M-0 + M-1). It touches existing application code, which TASK-000 forbade, so it needs explicit approval per `AGENT_PROTOCOL.md` §4.

## Urgent operational issue
Application code is **staged but not committed** and **no Git remote is configured**. The only copy of the work is on the local disk. To be resolved first (M-0).

## Working principles
- Human-in-the-Loop.
- Autonomous execution inside the approved task scope.
- Persistent project artifacts instead of long chat histories.
- Minimal context loading.
- Claude Code and Codex alternate work through explicit handoffs.
- Local models should be preferred for low-risk/low-complexity work once routing is implemented.
- Never rewrite or discard existing working code without evidence and justification.

## Target architecture
AI Company OS will progressively include:
- Project Registry
- Agent Registry
- Skills / Rules / Subagents / Tools / MCP Registry
- Model Gateway and local/cloud routing
- Context Engineering
- Prompt Engineering
- Harness Engineering
- Loop Engineering / Human approval gates
- Graph Engineering
- Visual Code Architecture Graph with pseudocode
- Knowledge Vault and navigable Memory Graph
- Planner
- GitHub / GitLab
- ClickUp
- Google Drive / Gmail
- Software adapters
- Template Hub
- Framework Explorer
- External AI workspaces
- Voice Interaction Layer
- Payments / subscriptions / quota monitoring
- 3D Omniverse integration

## Immediate goal
Differential review of the TASK-000 audit by Codex, then human approval of ADR-001 and of the TASK-001 scope.
Minimum migration path recorded in `docs/audit/MIGRATION_MAP.md`: M-0 Git hygiene → M-1 PostgreSQL + Flyway + Docker → M-2 `Project` domain → M-3 API contracts → M-4 tests/CI → M-5 Model Gateway → M-6 minimal frontend.
