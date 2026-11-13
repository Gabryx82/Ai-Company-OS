# AI Company OS — governance per gli agenti

> Letto da qualunque agente aperto in questa cartella: Claude Code nel terminale e nell'app desktop
> (tramite `CLAUDE.md`), Codex, OpenCode, Antigravity e Gemini CLI (tramite `GEMINI.md`), Cursor,
> Copilot, Devin, Verdent. Non ripete la governance: dice dove sta.

## Prima di lavorare

Leggi, in quest'ordine, solo ciò che serve:

| Cerchi | Leggi |
|---|---|
| Lo stato del progetto e la fase corrente (la prima sezione «▶▶▶» vince sul resto) | `.company-os/PROJECT_STATE.md` |
| L'autorità, i guardrail e gli hard stop — dove ci si ferma e si chiede | `.company-os/AUTONOMOUS_CHARTER.md` |
| Come si lavora su una task, passo per passo | `.company-os/AUTONOMOUS_LOOP.md`, `.company-os/AGENT_PROTOCOL.md` |
| Che contesto caricare e che cosa lasciare fuori | `.company-os/CONTEXT_POLICY.md`, `.company-os/TOKEN_POLICY.md` |
| Le decisioni architetturali | `docs/adr/` |
| Avvio, endpoint, profili, test | `docs/RUNNING.md` |
| Il debito aperto | `docs/DEBT_REGISTRY.md` |

Non scansionare il repository intero senza una ragione dichiarata.

## Le regole che non si discutono

Sono del charter, che resta la fonte: `master` è il gate umano — si lavora su un branch
`autonomous/phase-NN-<nome>`, merge e push solo su autorizzazione esplicita dell'operatore; lo schema
è di Flyway e le migrazioni sono additive; niente segreti nei file e nei commit; quando il rosso è il
punto, un test si vede fallire prima di vederlo verde; quello che si nota e non si fa, si scrive.

## Dove sta cosa

| Parte | Stack | Cartella |
|---|---|---|
| Control plane | Java 21, Spring Boot, PostgreSQL 17, Flyway | `backend/` |
| AI Engine | Python ≥ 3.11, FastAPI | `ai-engine/` |
| Console dell'operatore | React 19, TypeScript, Vite | `frontend/` |
| Contratto API | OpenAPI generato dal codice | `docs/api/openapi.json` |

## Comandi

- Control plane: `cd backend && ./mvnw test` — i test usano PostgreSQL in Testcontainers, quindi
  **Docker deve essere in esecuzione**.
- Dopo una modifica voluta dell'API: `cd backend && ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.write=true`,
  poi `cd frontend && npm run api:types`.
- Console: `cd frontend && npm run typecheck` e `npm test`. **Mai `tsc -b`**: scrive file `.js` accanto
  ai sorgenti e raddoppia la suite di Vitest.
- AI Engine: `cd ai-engine && .venv/Scripts/python -m pytest`.
- Tutto il sistema in locale: `.\scripts\start-dev.ps1` (vedi `docs/RUNNING.md`).

## Lingua

Documenti, ADR e testi della console in italiano; nomi e commenti nel codice in inglese, come il
codice esistente.
