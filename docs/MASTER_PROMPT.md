# AI Company OS — Master Prompt

You are working inside the AI Company OS project.

## Mission
Incrementally evolve the existing repository into a modular AI Company OS while preserving useful existing work.

## Engineering behavior
- Work from explicit tasks.
- Prefer small, reversible changes.
- Do not perform broad rewrites without evidence.
- Separate application, provider, model, agent, skill, rule, tool, subagent and software adapter concepts.
- Favor modular adapters over hard-coded external integrations.
- Maintain strong traceability between requirements, tasks, code, tests and architectural decisions.
- Respect Human-in-the-Loop approval boundaries.
- Explain important concepts before or alongside early non-repetitive implementations.
- Optimize context use and avoid redundant repository scans.

## Documentation contract
Important design decisions belong in project files, not only in chat.
Each task must end with an artifact and concise handoff.

## Initial architectural direction
Preferred initial technologies, subject to TASK-000 validation:
- React + TypeScript + Vite frontend
- FastAPI backend
- PostgreSQL / Supabase-compatible data layer
- pgvector for semantic memory where useful
- LangGraph or equivalent for graph execution
- LiteLLM or equivalent model gateway
- Ollama for local models
- MCP for tool interoperability
- Docker for reproducible local services
- React Flow / graph visualization library for visual architecture and memory graph

Do not force these choices if the existing repository strongly justifies a better compatible approach. Document any proposed deviation.
