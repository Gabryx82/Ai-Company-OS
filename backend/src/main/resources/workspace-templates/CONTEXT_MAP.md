# Mappa del contesto — {{PROJECT_NAME}}

Direttiva permanente: **minimizzare i token spostando il contesto persistente nei file e recuperando
solo ciò che serve.** Ogni livello sa dove trovare il livello sotto.

| Livello | File | Chi lo scrive | Chi lo legge |
|---|---|---|---|
| Progetto | `MASTER_PROMPT.md`, `.aicos/project.json` | operatore (brainstorming con ChatGPT Classic) | Master Orchestrator |
| Piano | `docs/IMPLEMENTATION_PLAN.md`, `.aicos/plan.json` | Master Orchestrator | operatore (approvazione), agenti |
| Fase | `docs/phases/PHASE_N.md` | Master Orchestrator | agenti della fase |
| Task | `tasks/TASK-NNN.md` | Master Orchestrator; l'agente ne compila l'*Esito*: un riassunto e il link | agente assegnato |
| Esito | `tasks/TASK-NNN.esito.md` | l'agente, a fine lavoro: cosa ha fatto, file toccati, test, cosa resta | operatore (review), console |
| Handoff | `.aicos/handoffs/TASK-NNN-<target>.md` | Master Orchestrator | agente esterno (Claude Code, Codex, …) |
| Decisioni | `docs/adr/ADR-NNN-*.md` | agenti, operatore | tutti |
| Reference | `references/images`, `references/mockups`, `references/screenshots`, `references/design-targets` | operatore (es. Gemini), agenti | agenti di UI, grafica, 3D |

Prompt tipo, che la consegna dalla console (Master Orchestrator → «Prepara e apri») scrive e passa allo
strumento insieme al pacchetto:

> Leggi .aicos/handoffs/TASK-NNN-<strumento>.md ed esegui la task che descrive.

Se avvii l'agente a mano, il prompt che porta al pacchetto è quello del documento della task:

> Esegui TASK-NNN partendo dal suo pacchetto di consegna, il più recente `.aicos/handoffs/TASK-NNN-*.md` (ruolo, skill, knowledge e regole); se non c'è, chiedi all'operatore di consegnare la task da AI Company OS prima di cominciare.
