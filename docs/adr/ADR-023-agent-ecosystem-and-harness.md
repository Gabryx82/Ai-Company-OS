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

## Revisione PHASE 36 (2026-11-08) — ogni campo del profilo arriva all'agente (ADR-042)

§4 diceva che un agente con profilo porta nel system prompt «istruzioni, limiti, output, direttive e l'elenco del
proprio harness». Un test campo per campo (`HarnessChainApiTest`) ha mostrato che non bastava: la politica di contesto,
la descrizione, le capability, il dominio, il genitore di un sottoagente e il software consentito si salvavano e non
arrivavano alla run (e capability, dominio, genitore e software nemmeno al pacchetto di handoff). Da PHASE 36 la parte
stabile del prompt di sistema di una run porta, quando ci sono: descrizione, capability, dominio, «You are a sub-agent of
X», istruzioni, responsabilità, limiti, output, direttive, politica di contesto, l'harness per nome e «Software you may
use». Il pacchetto di handoff porta gli stessi campi in italiano, nell'ordine della pagina. Un agente senza nessuno di
questi campi e senza harness produce ancora il prompt di PHASE 6 (regressione in `OrchestratorApiTest`).

La pagina della console salva ogni campo in una sola scrittura e lo rilegge alla riapertura (`Agents.test.tsx`, vista
rossa con un campo non inviato e con un campo non riletto).
