# TASK-022 — Implementazione

| File | Ruolo |
|---|---|
| `frontend/package.json`, `vite.config.ts`, `tsconfig.json` | React 19.3, Vite 8.3, TypeScript 5.9 (openapi-typescript richiede `^5`), Vitest 5, jsdom |
| `src/api/schema.d.ts` | **Generato** (`npm run api:types`) |
| `src/api/types.ts` | Alias sui tipi generati; archi di ADR-014 |
| `src/api/client.ts` | `ControlPlane`: token, `Versioned<T>`, `ApiProblem` |
| `src/session.ts` | Sessione in `sessionStorage` |
| `src/App.tsx`, `context.ts` | Layout, navigazione per hash, stato del backend, `401` → login |
| `src/views/*` | Login, Overview, TaskBoard (+ creazione), TaskDrawer (archi, run, assegnazione, dettagli), Agents, Projects, Engine |
| `src/components/ui.tsx` | Badge, `ProblemNote`, `Modal`, `Field` |
| `src/styles.css` | Token di design, chiaro/scuro, layout responsivo |
| `src/test/*`, `*.test.ts(x)` | `fetch` finto che registra le chiamate; 14 test |
| `scripts/start-dev.ps1` | Avvio dell'intero stack, un servizio per finestra, `-Database` per usare un clone |
| `.github/workflows/ci.yml` | Job `frontend`: tipi in sincronia, typecheck, test, build |
| `backend/.../security/CorsPolicy` | CORS anche su `/actuator/health` (difetto trovato dal vivo) |
