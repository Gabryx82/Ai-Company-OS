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

## Revisione PHASE 33 (ADR-039, 2026-10-11) — W3 in lettura

L'explorer dei file della console legge **ogni** file della cartella del progetto, non solo i documenti di W3:
l'operatore vuole vedere il codice senza aprire un IDE. W2 vale com'è (nessuna uscita dalla cartella, link compresi);
i file di credenziali sono elencati ma mai letti; solo le immagini sono servite come byte. **La scrittura non cambia**:
l'API scrive ancora soltanto i documenti elencati in W3.

## Revisione PHASE 33 (ADR-040, 2026-10-13) — W3 in scrittura, dall'explorer

Dal menu contestuale dell'explorer l'operatore rinomina, crea file e cartelle vuoti e sposta nel Cestino di Windows
**qualsiasi** elemento della cartella del progetto, uno alla volta. Restano fuori: la struttura del workspace
(`MASTER_PROMPT.md`, `AGENTS.md`, `CLAUDE.md`, `.aicos/`), `.git`, i documenti a cui punta un record, i collegamenti.
W2 e W4 valgono come prima. Il contenuto dei file si scrive ancora solo per i documenti di W3.

## Revisione PHASE 34 (2026-11-07) — `CLAUDE.md` importa `AGENTS.md`; il pacchetto per primo

- Il `CLAUDE.md` dello scaffold contiene `@AGENTS.md`: Claude Code importa la governance all'avvio, invece di doverla
  aprire perché una riga in prosa glielo chiede.
- `AGENTS.md` mette al primo passo di «Come si lavora su una task» il pacchetto di handoff della task, se esiste in
  `.aicos/handoffs/`, e lo elenca in «Dove sta cosa»: anche un agente partito da «Esegui TASK-NNN…» trova ruolo,
  skill e knowledge, quando la task è stata consegnata.
- `.aicos/CONTEXT_MAP.md` dà come prompt tipo quello della consegna, e dice che cosa lascia fuori l'altro.

Per W1 valgono solo per le cartelle nuove: lo scaffold non riscrive i `CLAUDE.md` e gli `AGENTS.md` che esistono.

### Ogni strumento trova il suo file, anche in una cartella portata dall'operatore

- **`GEMINI.md`** si aggiunge alla struttura (template, scaffold, W3 in scrittura, struttura che l'explorer non
  rinomina né cestina): è il file di Antigravity e di Gemini CLI, e importa `AGENTS.md` con `@./AGENTS.md`.
  Antigravity legge anche `AGENTS.md` dalla 1.20.3; `GEMINI.md` copre le versioni precedenti e Gemini CLI.
- **All'handoff** `ProjectWorkspaceService.ensureGovernance` scrive, dove mancano, `AGENTS.md`, `CLAUDE.md`,
  `GEMINI.md`, `.aicos/CONTEXT_MAP.md` e `.aicos/project.json`. Prima lo scaffold partiva solo se la cartella non
  esisteva: in una cartella già esistente (un repository dell'operatore, i progetti importati) il pacchetto mandava
  l'agente a un `AGENTS.md` che non c'era, e Claude Code non trovava `CLAUDE.md`. A differenza dello scaffold non
  aggiunge `docs/`, `references/` né `MASTER_PROMPT.md`, e un file dell'operatore resta com'è (W1). Una task senza
  progetto riceve anche `GEMINI.md` nella sua cartella inbox.
- **Il repository di AI Company OS** ha i suoi `AGENTS.md`, `CLAUDE.md` e `GEMINI.md` alla radice: dicono dove
  stanno lo stato, il charter e i comandi, senza ripeterli.

Test: `HandoffAnywhereApiTest` (cartella dell'operatore: i tre file scritti, il suo `CLAUDE.md` intatto, niente
`docs/`, `references/`, `MASTER_PROMPT.md`; un secondo handoff non riscrive nulla; inbox con `GEMINI.md`),
`ProjectWorkspaceApiTest`, `WorkspacePathsTest`, `WorkspaceDeletionApiTest`.

## Revisione PHASE 36 (2026-11-08) — i testi generati si aggiornano dove sono ancora com'erano (ADR-042 §5)

W1 resta per lo scaffold. In più, **alla consegna**, `ProjectWorkspaceService.upgradeGenerated` confronta `AGENTS.md`,
`.aicos/CONTEXT_MAP.md` e i documenti `tasks/*.md` (non gli esiti) con i passaggi che AI Company OS vi scriveva prima
(`GeneratedTextUpgrades`): un passaggio trovato **identico** è sostituito con la versione corrente, gli a capo del file
restano, il resto del file è intatto; il file com'era è prima copiato in `.aicos/upgrades/<data-ora>/`. Un passaggio
che l'operatore ha cambiato non è più identico e resta com'è; la verifica prima della consegna lo segnala. È ciò che
porta i progetti iniziati prima di PHASE 34 (`AGENTS.md` senza il pacchetto per primo, «Prompt compatto» nei documenti
delle task) alla consegna di oggi. Test: `GeneratedTextUpgradesTest`, `DeliveryPathApiTest`.
