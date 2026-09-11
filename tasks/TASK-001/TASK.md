# TASK-001 — Reproducible Persistence Foundation

## Owner
Claude Code / Opus 5

## Reviewer
Codex / GPT-6 Astra (differential review)

## Baseline
- Branch di lavoro: `task-001-persistence-foundation`, creato da `task-000-audit` (HEAD `503663c`).
- Contiene sia il backend (`ea25bee`) sia gli artefatti TASK-000 (`503663c`) sia la review Codex (`2f01194`).
- Nessun remote configurato. Nessun merge su `master`.

## Decisione architetturale approvata (input umano)
- Spring Boot / Java 21 resta il control plane principale.
- Il livello AI sarà un servizio Python separato, **da non implementare ora**.

## Objective
Sostituire la persistenza temporanea e non riproducibile (H2 in-memory + `ddl-auto=update`) con una base PostgreSQL versionata, avviabile in modo riproducibile e verificata da test automatici.

## In scope
1. PostgreSQL come database applicativo.
2. Docker Compose per il solo database, con volume persistente.
3. Migrazioni Flyway versionate: lo schema è creato **esclusivamente** dalle migrazioni.
4. Configurazione Spring Boot e profili necessari (`dev`, `prod`, test).
5. Revisione **minima** delle entity, limitata a ciò che serve alla persistenza e alla validazione dello schema.
6. DTO e Bean Validation minima, sufficienti a impedire input invalidi come `POST /api/tasks {}`.
7. Seed/dev data riproducibile, con **un solo proprietario** e chiaramente separato dalla produzione.
8. Test automatici su persistenza, migrazioni e validazione, eseguiti contro PostgreSQL reale.
9. Documentazione minima per avviare database e backend.

## Out of scope (vincoli espliciti)
- Frontend.
- LangGraph.
- Agent Registry.
- Servizio Python.
- Autenticazione completa.
- Refactoring generale.
- Cambi di stack.
- Entità `Project`, nuove relazioni fra entità, enum di dominio, macchine a stati (rinviati al task di dominio, come richiesto da `CODEX_REVIEW.md` R5).
- Nuovi endpoint CRUD.
- TASK-002.

## Acceptance criteria
Derivati da `docs/audit/CODEX_REVIEW.md` §«TASK-001 corretto e sequenza proposta».

- **AC-1** — Base Git dichiarata, lavoro preesistente preservato, nessun merge su `master`, nessuna riscrittura di storia.
- **AC-2** — PostgreSQL locale avviabile via Docker Compose con volume nominato e procedura documentata. Nessuna credenziale reale nel repository.
- **AC-3** — Database vuoto popolato **solo** da migrazioni versionate; Hibernate in `validate`, senza modificare lo schema.
- **AC-4** — Secondo avvio senza drift; un mismatch di schema viene segnalato come errore.
- **AC-5** — Comportamenti validi di lettura e creazione preservati (`GET /api/agents`, `GET`/`POST /api/tasks` con payload valido).
- **AC-6** — `POST /api/tasks {}` rifiutato con `400`, nessuna riga persistita.
- **AC-7** — Seed con un solo proprietario, attivo solo in dev, nessun duplicato al riavvio.
- **AC-8** — Test contro PostgreSQL reale (Testcontainers) per migrazioni, persistenza e validazione. Un test H2 o `contextLoads` non è sufficiente.
- **AC-9** — Dati conservati al riavvio dell'applicazione **e** alla ricreazione del container con lo stesso volume (verifica documentata).
- **AC-10** — Build e test ripetibili e documentati; nessun `Project`, relazione, provider, frontend o nuovo CRUD introdotto implicitamente.

## Safety
Modifica del codice applicativo **consentita** entro questo scope (approvazione umana ricevuta), a differenza di TASK-000.
Vietati: force push, reset/rebase distruttivi, cancellazione di branch, modifica della storia Git, merge su `master`, configurazione di remote senza approvazione, commit di segreti o file generati.

## Required outputs
- `tasks/TASK-001/TASK.md` (questo file)
- `tasks/TASK-001/CONTEXT.yaml`
- `tasks/TASK-001/IMPLEMENTATION.md`
- `tasks/TASK-001/ARTIFACT.md`
- `tasks/TASK-001/HANDOFF.md`
- ADR per le decisioni architetturali permanenti introdotte
- Aggiornamento di `.company-os/PROJECT_STATE.md`

## Final constraint
Fermarsi al termine di TASK-001. **Non iniziare TASK-002.**
