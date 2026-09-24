# ADR-020 — La cartella del progetto è il contesto; il database ne è l'indice

- **Stato**: Accettata e implementata (PHASE 9–10)
- **Data**: 2026-09-24
- **Decisa da**: agente, su direttiva umana del 2026-09-24 (§7–12, §9)
- **Dipende da**: ADR-004 (Project Registry), ADR-018, ADR-019

## 1. Contesto

La direttiva chiede che il contesto persistente viva nei file — `MASTER_PROMPT.md`,
`IMPLEMENTATION_PLAN.md`, `PHASE_X.md`, `TASK-XXX.md`, reference — e che agli agenti basti un prompt
compatto («Esegui TASK-042 seguendo il relativo file e la governance del progetto»). Gli agenti
esterni (Claude Code, Codex, Antigravity, OpenCode) leggono file, non il nostro database. Quindi i
file sono la **fonte**, e il database deve sapere **dove** sono e in che stato è il lavoro.

## 2. Decisione

### 2.1. Il profilo del progetto (V14, additiva)

`projects` guadagna: `project_type` (vocabolario chiuso, nullable per i progetti esistenti), `stack`,
`workspace_path`, `autonomy_level` (default `GUIDED`, ADR-022) e `plan_status` (`NONE` → `DRAFT` →
`APPROVED`). Si scrivono con `PUT /api/projects/{id}/profile` sotto il protocollo di ADR-009, su un
servizio separato: `ProjectService` e il contratto di PHASE 1–7 restano invariati.

### 2.2. La struttura governata

```
<workspace>/
├── MASTER_PROMPT.md            l'artefatto di origine (da ChatGPT Classic)
├── AGENTS.md                   la governance per qualunque agente: dove sta cosa
├── CLAUDE.md                   una riga: "leggi AGENTS.md" (Claude Code legge CLAUDE.md)
├── docs/
│   ├── IMPLEMENTATION_PLAN.md  generato dal Master Orchestrator (PHASE 10)
│   ├── phases/PHASE_N.md
│   └── adr/
├── tasks/TASK-NNN.md
├── references/{images,mockups,screenshots,design-targets}/
└── .aicos/
    ├── project.json            manifesto leggibile da macchina (id, tipo, stack, autonomia)
    ├── CONTEXT_MAP.md          la mappa del contesto
    ├── plan.json               il piano canonico (PHASE 10)
    └── handoffs/               i prompt consegnati agli agenti esterni (PHASE 11)
```

`AGENTS.md` è il nome che Codex e OpenCode leggono da soli; `CLAUDE.md` rimanda a esso. Così un
agente esterno aperto nella cartella trova la governance **senza** che il prompt la ripeta.

### 2.3. Invarianti di scrittura

- **W1 — Mai sovrascrivere al momento dello scaffold.** Un file esistente (un `CLAUDE.md`
  dell'operatore, un README) viene saltato e riportato come tale. Lo scaffold è idempotente.
- **W2 — Dentro la cartella, sempre.** Ogni percorso relativo è normalizzato e deve restare sotto la
  cartella del progetto, anche dopo la risoluzione dei link simbolici del padre. `..`, percorsi
  assoluti e nomi di dispositivo sono rifiutati con `400`.
- **W3 — Solo testo, solo dove serve.** L'API scrive soltanto file di testo (`.md`, `.json`, `.txt`)
  sotto `MASTER_PROMPT.md`, `AGENTS.md`, `docs/`, `tasks/`, `references/`, `.aicos/`; legge anche le
  immagini sotto `references/`. Il codice del progetto non è affare di questa API.
- **W4 — Un progetto archiviato non si scrive** (ADR-004 §8), né il profilo né i documenti.

### 2.4. La cartella

Di default `<aicos.workspace.root>/<slug del nome>` con radice `~/AI-Company-Projects`; l'operatore
può indicare una cartella esistente (un repository già suo). Il percorso deve essere assoluto.

## 3. Tipologie e stack

`catalog/project-types.json` descrive per ogni tipologia lo stack proposto, i software del catalogo
consigliati e i ruoli d'agente coinvolti. È la proposta **deterministica** del Master Orchestrator al
momento della creazione: l'operatore la accetta o la modifica, e il piano (PHASE 10) può raffinarla.

## 4. Conseguenze

- `ProjectFolders` (ADR-019 I2) risolve finalmente una cartella: il launcher apre gli IDE e i
  terminali nel progetto.
- Il contesto di una task (PHASE 11) è una lista di **percorsi**, non di contenuti copiati.
- Nessun dato di progetto esce dalla macchina: i file restano dove l'operatore li vuole.
