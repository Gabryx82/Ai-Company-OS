# PHASE 5 — AI Engine

> Piano della fase. Stato in `PROJECT_STATE.md`, sequenza in `ROADMAP_PHASES_3_7.md`.

- **Integration branch**: `autonomous/phase-5-ai-engine`, da `autonomous/phase-4-task-lifecycle`.
- **Baseline**: 277 test Java verdi, stream `V9`.

## Obiettivo
> **Esiste un servizio che, dato un prompt, restituisce un completamento da un modello reale —
> locale o cloud — dietro un contratto versionato e autenticato, senza toccare lo stato canonico.**

È M-5 di `MIGRATION_MAP.md`, metà provider, nella forma di ADR-001: Spring resta il control plane,
il livello AI è un servizio Python separato.

## Scope
| Task | Contenuto |
|---|---|
| **TASK-017** | Scheletro FastAPI, contratto v1, token fra servizi, problem details, correlation id, provider `echo`, job CI (ADR-015) |
| **TASK-018** | Provider `ollama` (locale) e `anthropic` (SDK ufficiale, spento senza chiave) |

**Fuori scope**: streaming, tool use, runtime a grafo (LangGraph resta rinviato da ADR-001),
embedding, quote e costi, retry propri.

## Criterio di chiusura
1. la suite Python gira in CI in un job proprio;
2. ogni guasto di provider è uno dei problemi del contratto, verificato per mutazione;
3. nessun test raggiunge rete o servizi a pagamento;
4. uno smoke test reale produce un completamento da un modello.
