# TASK-022 → prossimo agente

- Avvio: `.\scripts\start-dev.ps1` (o `-Database <clone>`), poi `http://localhost:5173`.
- Test: `cd frontend && npm test`; tipi: `npm run api:types` dopo ogni rigenerazione di `openapi.json`.
- Un'origine nuova per la console va aggiunta a `aicos.cors.allowed-origins`.
- Nessuna regola di dominio nel frontend: se serve una regola, va sul server.
