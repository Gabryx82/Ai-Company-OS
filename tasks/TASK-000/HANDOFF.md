# TASK-000 — HANDOFF → Codex (review differenziale)

## Cosa è stato fatto
Audit completo del repository. Nessun codice applicativo toccato (`source_code_modification_allowed: false` rispettato). Scritture limitate a `docs/audit/**`, `tasks/TASK-000/**`, `.company-os/PROJECT_STATE.md`.

## Stato reale in 6 righe
- Unico codice applicativo: `backend/` — Spring Boot 4.1, Java 21, 13 file Java, ~350 righe.
- Tre contesti: `agent` (read-only), `task` (CRUD parziale), `ai.orchestrator` (**stub a keyword, nessuna AI**).
- Persistenza: **H2 in-memory**, `ddl-auto=update`, nessuna migrazione. Driver PostgreSQL presente ma inutilizzato.
- Assenti: frontend, servizio Python/LangGraph, Supabase — tutti e tre dichiarati nel `README.md`.
- Copertura dei 25 domini target: **< 2%** (solo Agents ~10% e Task Engine ~8%).
- Build e test: **verdi** (`./mvnw -B test`, exit 0). Test funzionali: **zero** (solo `contextLoads`).

## Verificato a runtime (non dedotto)
| Sonda | Esito |
|---|---|
| `GET /api/agents` | 200, 3 agent |
| `POST /api/tasks` completo | 200, persistito |
| `POST /api/orchestrator` | 200, `"Backend Agent + Database Agent"` |
| `POST /api/tasks` body `{}` | **200, riga tutta null** ← difetto TD-03 |
| `GET /h2-console` | **404** (config inerte) |
| `./mvnw -o test` (offline) | **fallisce**, plugin non in cache |

## Conclusioni da verificare in review differenziale
1. **Non riscrivere.** Lo stack è valido; il repository è uno scheletro sano, non un legacy.
2. **Un solo REPLACE**: `MasterOrchestrator` + `OrchestratorController`.
3. **Collo di bottiglia**: persistenza durevole (G-01) e modello di dominio (G-02). Nessun lavoro AI produce valore durevole prima.
4. **ADR-001 proposto**: mantenere Spring Boot come control plane invece di FastAPI (`MASTER_PROMPT.md`), con servizio Python separato per l'AI in M-5. **Richiede approvazione umana.**
5. **TASK-001 raccomandato**: fondazione di persistenza e dominio (M-0 + M-1).

## Punti su cui è più utile un parere indipendente
- ADR-001: Spring Boot control plane + servizio AI Python è la scelta giusta, o FastAPI unico backend è preferibile nonostante il costo di riscrittura?
- L'ordine M-0…M-7 in `MIGRATION_MAP.md` regge, o M-2 (dominio `Project`) va anticipato dentro TASK-001?
- Il perimetro di TASK-001 è corretto, o va ristretto al solo M-0 + M-1 senza estensione a M-2?
- Severità di TD-04 (sicurezza assente): rimandarla a M-7 è accettabile o va anticipata?

## Cosa NON rifare
L'inventario del repository, la lettura dei 13 file Java e le sonde runtime sono completi e documentati. Usare `docs/audit/*` come base e produrre una **review differenziale**, non un secondo audit (`.company-os/AGENT_PROTOCOL.md` §6).

## Rilievo urgente
Codice applicativo **staged ma non committato**, **nessun remote Git**. Unica copia sul disco locale. Da risolvere per primo.

## Vincolo
TASK-001 **non avviato**, come imposto da `TASK.md`. Nessun lavoro implementativo deve iniziare prima della review e dell'approvazione umana.
