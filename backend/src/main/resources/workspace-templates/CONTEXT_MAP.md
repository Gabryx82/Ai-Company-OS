# Mappa del contesto — {{PROJECT_NAME}}

Direttiva permanente: **minimizzare i token spostando il contesto persistente nei file e recuperando
solo ciò che serve.** Ogni livello sa dove trovare il livello sotto.

| Livello | File | Chi lo scrive | Chi lo legge |
|---|---|---|---|
| Progetto | `MASTER_PROMPT.md`, `.aicos/project.json` | operatore (brainstorming con ChatGPT Classic) | Master Orchestrator |
| Piano | `docs/IMPLEMENTATION_PLAN.md`, `.aicos/plan.json` | Master Orchestrator | operatore (approvazione), agenti |
| Fase | `docs/phases/PHASE_N.md` | Master Orchestrator | agenti della fase |
| Task | `tasks/TASK-NNN.md` | Master Orchestrator; l'agente ne compila l'*Esito* | agente assegnato |
| Handoff | `.aicos/handoffs/TASK-NNN-<target>.md` | Master Orchestrator | agente esterno (Claude Code, Codex, …) |
| Decisioni | `docs/adr/ADR-NNN-*.md` | agenti, operatore | tutti |
| Reference | `references/images`, `references/mockups`, `references/screenshots`, `references/design-targets` | operatore (es. Gemini), agenti | agenti di UI, grafica, 3D |

Prompt tipo, che basta a un agente per partire:

> Esegui TASK-NNN seguendo `tasks/TASK-NNN.md` e la governance in `AGENTS.md`.
