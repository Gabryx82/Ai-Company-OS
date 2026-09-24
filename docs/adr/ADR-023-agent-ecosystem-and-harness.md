# ADR-023 — Ecosistema degli agenti: prompt engineering, sottoagenti, harness

- **Stato**: Accettata e implementata (PHASE 12)
- **Data**: 2026-09-24
- **Decisa da**: agente, su direttiva umana del 2026-09-24 (§13–15, §22)
- **Estende**: ADR-008 (Agent Registry), ADR-018 (concetti)

## 1. Decisione

- **Prompt engineering** (§13): `agents` guadagna `system_prompt`, `responsibilities`, `limits`,
  `output_format`, `directives` (una per riga), `context_policy`, `domain` (V18, colonne nullable).
- **Sottoagenti** (§15): `agents.parent_id`. Un sottoagente è un `Agent` con un genitore, non un tipo
  diverso. Cicli rifiutati: `CHECK` per l'auto-riferimento, controllo del servizio (risalita degli
  antenati sotto il lock dell'agente spostato) per quelli più lunghi → `409 agent-hierarchy-cycle`.
- **Harness** (§14): catalogo `harness_resources` (`SKILL`, `KNOWLEDGE`, `MCP`, `TOOL`, `FRAMEWORK`,
  `TEMPLATE_PROVIDER`) e i legami `agent_resources`, `agent_software`, `project_resources`.
- **Explorer** (§22): ricerca per tipo e parole; «installare» significa associare a un agente o
  adottare in un progetto.
- **Template di agenti**: `catalog/agent-templates.json` (Master Orchestrator, Software Engineer con
  Backend/Frontend/Database/Test Engineer, Graph Engineer, Tech Writer, UI/UX Designer, 3D Artist,
  Learning Coach). Si installano su richiesta; un nome già presente non viene toccato.

## 2. Perché su risorse separate

Il contratto `AgentResponse` di PHASE 2–7 resta byte per byte com'era: profilo e harness vivono su
`/api/agents/{id}/profile` e `/api/agent-profiles`. Nessun client esistente cambia.

## 3. Perché template e non seed

Lo stream del seed di sviluppo è coperto da test di migrazione molto rigidi (inclusi i percorsi legacy
di TASK-001). Soprattutto: gli agenti sono dati dell'operatore, e crearli è un suo gesto — il pulsante
«Installa ecosistema base» della console.

## 4. Effetto sulle run

Un agente **con** profilo porta nel system prompt le proprie istruzioni, limiti, output, direttive e
l'elenco del proprio harness. Un agente **senza** profilo produce il prompt di PHASE 6 invariato
(test di regressione in `OrchestratorApiTest`).

## 5. Legami come insiemi

`PUT` aggiunge, `DELETE` toglie, entrambi idempotenti; non mutano la riga dell'agente o del progetto,
quindi non richiedono il suo tag (esenzione motivata in `PreconditionCoverageTest`).
