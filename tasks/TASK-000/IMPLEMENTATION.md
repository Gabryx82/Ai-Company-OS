# TASK-000 — IMPLEMENTATION

## Obiettivo
Audit del repository esistente prima di qualsiasi nuova architettura o implementazione.

## Vincolo di sicurezza rispettato
`tasks/TASK-000/CONTEXT.yaml` → `source_code_modification_allowed: false`.
**Nessun file di codice applicativo è stato creato, modificato o cancellato.** Le scritture sono rimaste dentro i percorsi consentiti: `docs/audit/**`, `tasks/TASK-000/**`, `.company-os/PROJECT_STATE.md`.

## Metodo

### Fase 1 — Contesto di governance
Letti nell'ordine imposto da `prompts/CLAUDE_TASK_000.md`: `PROJECT_STATE.md`, `AGENT_PROTOCOL.md`, `TOKEN_POLICY.md`, `CONTEXT_POLICY.md`, `MASTER_PROMPT.md`, `PRODUCT_VISION.md`, `TASK.md`, `CONTEXT.yaml`, più `ARCHITECTURE.md` e `IMPLEMENTATION_PLAN.md` elencati in `CONTEXT.yaml`.

### Fase 2 — Mappatura del repository
Scansione completa autorizzata da `CONTEXT.yaml` (`repository_scan.allowed: true`), con esclusione di `node_modules`, `.git`, `dist`, `build`, `target`, `__pycache__`, `.venv`, `venv`, `.idea`, `.vscode`.

Risultato: **34 file totali**, di cui **13 di codice applicativo Java** (~350 righe). Nessun altro codice eseguibile nel repository.

### Fase 3 — Lettura del codice
Letti integralmente tutti i 13 file Java, `pom.xml`, `application.properties`, `maven-wrapper.properties`, `backend/.gitignore`, `README.md`.

### Fase 4 — Verifica empirica (non solo lettura)
Le conclusioni dell'audit sono state validate eseguendo il sistema, non deducendole dal codice:

1. **Toolchain**: JDK 21.0.12 presente; Maven non su PATH (solo wrapper); cache `~/.m2` contiene Spring Boot 4.1.0.
2. **Build offline**: `./mvnw -o -B test` → **FALLITA** (plugin Maven assenti dalla cache). Documentato come TD-14.
3. **Build online**: `./mvnw -B test` → **SUCCESSO**, exit 0, `contextLoads` verde, Hibernate 7.4.1, H2 2.4.240.
4. **Runtime**: applicazione avviata su `:8080` e interrogata con `curl`.

| Sonda | Esito |
|---|---|
| `GET /api/agents` | 200, 3 agent seed |
| `POST /api/tasks` payload completo | 200, task persistito |
| `GET /api/tasks` | 200 |
| `POST /api/orchestrator` `"build me an ecommerce"` | 200, `"Backend Agent + Database Agent"` |
| `POST /api/tasks` body `{}` | **200, riga con tutti i campi null** |
| `GET /h2-console` | **404** |

5. **Stato Git**: 1 commit, **nessun remote**, codice applicativo staged ma non committato.
6. Applicazione arrestata al termine delle verifiche.

### Fase 5 — Classificazione e redazione
Ogni componente significativo classificato KEEP / REFACTOR / REPLACE / DELETE / NEW, e redazione dei 7 artefatti di audit.

## Correzioni introdotte dalla verifica empirica

Due ipotesi formulate durante la lettura del codice sono state **smentite** dall'esecuzione, e questo giustifica il costo della Fase 4:

1. *Ipotesi*: `POST /api/tasks` fallisce perché `Task` non ha setter e Jackson non può popolare i campi privati.
   *Realtà*: funziona — Jackson 3 (Spring Boot 4) usa il costruttore a 4 argomenti come creator implicito. Non è un bug, ma **è fragile**: riclassificato come TD-06 (accoppiamento implicito all'ordine dei parametri del costruttore).

2. *Ipotesi*: la console H2 è esposta e costituisce un rischio di sicurezza.
   *Realtà*: risponde 404, la proprietà è inerte in questa configurazione. Declassato da rischio a configurazione morta da rimuovere (D-03).

Un difetto reale è invece emerso solo dall'esecuzione: `POST /api/tasks` con `{}` persiste una riga interamente null restituendo 200 (TD-03).

## Artefatti prodotti

| File | Contenuto |
|---|---|
| `docs/audit/CURRENT_ARCHITECTURE.md` | Mappa repository, architettura a 3 contesti, copertura dei 25 domini |
| `docs/audit/CURRENT_FEATURES.md` | Funzionalità verificate a runtime, difetti, copertura di test |
| `docs/audit/CURRENT_STACK.md` | Stack reale vs dichiarato, esiti di build e sonde runtime |
| `docs/audit/REUSABLE_COMPONENTS.md` | Classificazione KEEP/REFACTOR/REPLACE/DELETE/NEW |
| `docs/audit/TECHNICAL_DEBT.md` | 22 voci di debito per severità + 3 rischi architetturali principali |
| `docs/audit/GAP_ANALYSIS.md` | Gap per dominio, gap trasversali, catena di dipendenze |
| `docs/audit/MIGRATION_MAP.md` | Sequenza M-0…M-7, ADR-001 proposto, TASK-001 raccomandato |
| `tasks/TASK-000/ARTIFACT.md` | Sintesi decisionale |
| `tasks/TASK-000/HANDOFF.md` | Handoff compatto per Codex |

## Aderenza alla Token Policy
Letture mirate, nessuna directory esclusa scansionata, ragionamento persistito nei file invece che in chat. Contesto iniziale: 10 file di governance, come previsto da `CONTEXT.yaml`.

## Stato finale
TASK-000 completato. **TASK-001 non avviato**, come imposto dal vincolo finale di `TASK.md`.
