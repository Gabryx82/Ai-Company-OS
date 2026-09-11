# TASK-001A — Fix Flyway Migration and Dev Seed Strategy

## Owner
Claude Code / Opus 5

## Reviewer
Codex / GPT-6 Astra (review differenziale dei soli fix)

## Baseline
- Branch di lavoro: `task-001-persistence-foundation` (lo stesso di TASK-001).
- HEAD all'inizio: `b6e0b81` — `docs(task-001): document persistence foundation and record ADRs`.
- Nessun remote configurato. Nessun merge su `master`.

## Origine
`docs/reviews/TASK-001_CODEX_REVIEW.md`, rilievo **R1 (HIGH)**: il seed di sviluppo
`V1000__dev_seed_agents.sql` vive nello stesso stream versionato dello schema. Dopo
`V1 + V1000` il database dev è alla versione 1000, quindi una futura `V2` è una
versione inferiore a quella applicata e, con `outOfOrder=false`, Flyway **blocca
`migrate`** con `Detected resolved migration not applied to database: 2`.

Conseguenza secondaria (**R2, MEDIUM**) toccata dallo stesso fix: la garanzia
dichiarata «un database seminato in dev non è avviabile in prod» non esisteva. Con
`ignoreMigrationPatterns=[*:future]` il profilo `prod` si avviava senza errori su un
database seminato in dev.

## Objective
Separare in modo robusto **migrazioni di schema** e **seed di sviluppo**, in modo che:
lo stream di schema resti lineare e proseguibile (`V2`, `V3`, …), il seed dev resti
riproducibile e non duplicato, e la produzione sia **indipendente** dal seed dev.

## In scope
1. Separazione dei due stream Flyway (schema vs seed dev), con storia distinta.
2. Rinumerazione del seed dev nel proprio stream e idempotenza a livello SQL.
3. Procedura esplicita di transizione per i database dev che contengono già `V1000`.
4. Test di regressione che dimostrino i quattro comportamenti richiesti.
5. Aggiornamento di ADR-002, `docs/RUNNING.md`, artefatti TASK-001/TASK-001A e
   `.company-os/PROJECT_STATE.md`.

## Invarianti da preservare (vincolo esplicito dell'utente)
- PostgreSQL + Flyway.
- Hibernate `ddl-auto=validate`.
- `POST /api/tasks` valido → `201` + header `Location`.
- `status` e `priority` obbligatori (`@NotBlank`).

## Out of scope
- R3 (`.env` non letto dal backend), R5 (`server.address`), R6, R7 della review: rilievi
  reali ma indipendenti dal fix R1, da trattare come task separata.
- Enum di dominio, entità `Project`, relazioni, nuovi endpoint CRUD, frontend, servizio
  Python, autenticazione, CI.
- Refactoring generale, cambi di stack.
- TASK-002.

## Acceptance criteria
- **AC-1** — Database vuoto migrabile dal solo stream di schema; la storia di schema
  contiene esattamente `1`, nessuna versione di seed.
- **AC-2** — Seed dev applicato in profilo `dev`, in una storia Flyway propria, con i tre
  agent attesi; riesecuzione delle migrazioni → nessun duplicato.
- **AC-3** — Una futura `V2` è applicabile su un database dev già seminato, con i dati
  preesistenti conservati.
- **AC-4** — La produzione (location di solo schema) è indipendente dal seed dev: su un
  database seminato in dev `validate` ha successo, `migrate` e `V2` funzionano.
- **AC-5** — Un database dev legacy contenente `V1000` nella storia di schema è
  recuperabile con una procedura esplicita, senza duplicare i dati esistenti e senza
  cancellare il volume.
- **AC-6** — Invarianti preservate: `POST /api/tasks` → `201`, `{}` → `400`, Hibernate in
  `validate`, nessun H2.
- **AC-7** — `./mvnw -B clean test` verde su PostgreSQL reale.

## Safety
Modifica del codice applicativo consentita entro questo scope.
Vietati: force push, reset/rebase distruttivi, cancellazione di branch, riscrittura della
storia Git, merge su `master`, configurazione di remote, push automatico, commit di
segreti o file generati, cancellazione del volume dati locale.

## Required outputs
- `tasks/TASK-001A/{TASK.md,CONTEXT.yaml,IMPLEMENTATION.md,ARTIFACT.md,HANDOFF.md}`
- `docs/adr/ADR-003-dev-seed-separate-migration-stream.md`
- Correzione di `docs/adr/ADR-002-postgresql-flyway-persistence.md`
- Aggiornamento di `docs/RUNNING.md` e `.company-os/PROJECT_STATE.md`

## Final constraint
Fermarsi al termine di TASK-001A. **Non iniziare TASK-002.**
