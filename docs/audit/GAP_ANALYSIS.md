# GAP_ANALYSIS — Distanza tra stato reale e visione target (TASK-000)

Riferimenti: `docs/PRODUCT_VISION.md`, `docs/ARCHITECTURE.md` (25 domini), `.company-os/PROJECT_STATE.md`.

## 1. Sintesi

| Metrica | Valore |
|---|---|
| Domini target | 25 |
| Domini con implementazione ≥ 50% | **0** |
| Domini con implementazione parziale (< 20%) | **2** (Agents, Task Engine) |
| Domini a 0% | **23** |
| Copertura complessiva stimata | **< 2%** |
| Righe di codice applicativo | ~350 (13 file Java) |
| Livelli dello stack dichiarato realmente presenti | 1 su 4 |

## 2. Gap per dominio

| # | Dominio | Stato | Gap principale |
|---|---|---|---|
| 1 | Core / Identity / Permissions | 0% | Nessun utente, nessun ruolo, nessuna autenticazione |
| 2 | **Projects** | 0% | **Entità centrale del vision completamente assente** |
| 3 | Agents | ~10% | Anagrafica read-only a 4 campi; nessun provider, prompt, skill, tool, esecuzione |
| 4 | Models / Providers | 0% | Nessuna astrazione, nessun gateway, nessuna chiave, nessun routing |
| 5 | Skills | 0% | — |
| 6 | Rules | 0% | — |
| 7 | Subagents | 0% | — |
| 8 | Tools / Tool Bundles | 0% | — |
| 9 | MCP / Software Adapters | 0% | — |
| 10 | Context Engine | 0% | Esiste solo come policy documentale (`CONTEXT_POLICY.md`), non come codice |
| 11 | Prompt Engine | 0% | — |
| 12 | Task / Artifact Engine | ~8% | CRUD parziale di Task; nessun artefatto, stato libero, nessuna transizione |
| 13 | Human Approval Engine | 0% | Esiste come processo umano in `.company-os/`, non come gate applicativo |
| 14 | Graph Execution | 0% | Nessun LangGraph, nessun runtime a grafo |
| 15 | Code Intelligence / Visual Code Graph | 0% | — |
| 16 | Knowledge Vault / Memory Graph | 0% | Nessun pgvector, nessun embedding, nessuno store |
| 17 | Planner | 0% | — |
| 18 | Git integration | 0% | Il repository stesso non ha nemmeno un remote |
| 19 | Collaboration (ClickUp, Drive, Gmail) | 0% | — |
| 20 | Template Hub | 0% | — |
| 21 | Framework Explorer | 0% | — |
| 22 | External AI Workspaces | 0% | — |
| 23 | Voice Interaction | 0% | — |
| 24 | Billing / Subscriptions / Quotas | 0% | — |
| 25 | 3D Omniverse integration | 0% | Nessun codice nel repository |

## 3. Gap trasversali (non riconducibili a un singolo dominio)

| Gap | Descrizione | Blocca |
|---|---|---|
| **G-01 Persistenza durevole** | H2 in-memory, nessuna migrazione | Domini 2, 12, 16 e ogni feature con stato |
| **G-02 Modello di dominio** | Entità isolate senza relazioni, nessun `Project` | Domini 1–3, 12, 17 |
| **G-03 Frontend** | Nessuna UI | Domini 15, 16, 22, 23 e l'usabilità dell'intero sistema |
| **G-04 Livello AI** | Nessuna dipendenza AI, nessuna chiamata a modelli | Domini 4, 10, 11, 14 |
| **G-05 Sicurezza** | Nessuna auth | Domini 1, 18, 19, 24 |
| **G-06 Contratti API** | Entità esposte, nessun DTO, nessun errore strutturato, nessuna OpenAPI | Ogni consumatore, incluso il frontend |
| **G-07 Test e CI** | 1 test, nessuna pipeline | Il refactoring sicuro di tutto quanto sopra |
| **G-08 Riproducibilità** | Nessun Docker, build offline rotta | Onboarding, ambienti coerenti, PostgreSQL locale |

## 4. Ordine di dipendenza dei gap

I gap non sono indipendenti. Questa è la catena reale:

```
G-08 Riproducibilità (Docker)
      └─► G-01 Persistenza durevole (PostgreSQL + Flyway)
              └─► G-02 Modello di dominio (Project ↔ Agent ↔ Task)
                      ├─► G-06 Contratti API (DTO, errori, OpenAPI)
                      │       └─► G-03 Frontend
                      ├─► G-05 Sicurezza
                      └─► G-04 Livello AI (Model Gateway → Context → Prompt → Graph)
                              └─► Domini 14, 16, 17
G-07 Test e CI ── trasversale, deve precedere ogni refactoring significativo
```

**Conseguenza operativa**: G-01 e G-02 sono i colli di bottiglia. Nessun lavoro sui domini AI (4, 10, 11, 14, 16) produce valore durevole finché non sono risolti, perché ogni cosa costruita su H2 volatile e su entità scollegate andrà rifatta.

## 5. Disallineamenti da risolvere esplicitamente

### DA-01 — Backend: Spring Boot vs FastAPI
`docs/MASTER_PROMPT.md` indica FastAPI come backend preferito ("subject to TASK-000 validation"). La realtà è Spring Boot 4.1, funzionante.
**Raccomandazione dell'audit**: mantenere Spring Boot come control plane e aggiungere un servizio Python separato per il livello AI. Riscrivere in FastAPI significherebbe buttare l'unico codice funzionante per un beneficio non dimostrato. Deviazione da formalizzare come ADR (vedi `MIGRATION_MAP.md` §5).

### DA-02 — Documentazione che descrive desideri come realtà
`README.md` dichiara React, Python/LangGraph e Supabase: nessuno esiste. Va allineato alla realtà con una sezione "stato attuale" distinta da "target".

### DA-03 — Ampiezza dello scope
25 domini dichiarati, ~2% implementato. Il rischio non è tecnico ma di pianificazione: senza un ordine di priorità stretto, si producono 25 stub invece di 3 domini funzionanti.

## 6. Percorso minimo di migrazione

Il percorso più breve verso un sistema **utilizzabile** (non completo) tocca 5 domini su 25:

1. **Fondazione** — PostgreSQL + Flyway + Docker + profili.
2. **Dominio 2 (Projects)** + relazioni con Agents e Tasks.
3. **Contratti API** — DTO, validazione, errori, OpenAPI.
4. **Dominio 4 (Models/Providers)** — Model Gateway con almeno un provider reale, che sostituisce `MasterOrchestrator`.
5. **Frontend minimo** — dashboard che elenca progetti, agent e task e permette di lanciare un'esecuzione.

Questo è il Minimum Viable Company OS. Tutto il resto (grafi, memoria, voce, 3D, pagamenti) è successivo e non va aperto prima.
