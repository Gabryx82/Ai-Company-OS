# Final handoff — PHASE 32: debito zero, e l'integrazione in `master`

Branch `autonomous/phase-32-debt-zero` (dalla punta di PHASE 31, `021404c`). Il 2026-10-11 l'operatore ha
accettato il lavoro e autorizzato il merge: `master` avanza in **fast-forward** da `f4c69b3` (punta di PHASE 29, già
su `origin`) alla punta di questo branch, con dentro PHASE 30, PHASE 31, PHASE 32 e l'Ecosistema 3D. Nessun merge
commit, nessun force. **Il push su `origin` non è ancora fatto**: lo decidi tu (comando in fondo).

## Il registro

Direttiva del 2026-10-07: nessun debito aperto alla fine. Esito:

- **Chiusi con il codice**, ciascuno con un test visto rosso per mutazione: tutto lo spazio dell'audit di TASK-000 e,
  nel registro vivo, TD-26…TD-90 dove si poteva — l'elenco con i test è in
  [DEBT_REGISTRY.md](../DEBT_REGISTRY.md) §2–§5, le ragioni nelle revisioni degli ADR.
- **Chiusi per decisione tua il 2026-10-10**: TD-51 (`llama3.2:3b` resta), TD-67 (iGPU accesa), TD-72 (una sola forma
  di eliminazione, fatta), TD-44 (finestre dedicate, connettori quando vorrai), TD-53 (cookie `HttpOnly`, fatto).
- **Chiusi per decisione tua il 2026-10-11**, senza modifiche: i vincoli esterni TD-50, TD-74, TD-83, TD-86, TD-87, e
  quattro voci che restavano aperte nei documenti senza un id, registrate e chiuse lo stesso giorno: TD-93 (`.env` e
  backend), TD-94 (tag di PostgreSQL e nomi di Compose), TD-95 (correzioni all'audit), TD-96 (domande di contratto di
  PHASE 2). Che cosa resta com'è, per ciascuno: [PHASE32_DECISIONS.md](../PHASE32_DECISIONS.md).

**Nessun debito aperto**, in nessuno dei due spazi. Prossimo id libero: **TD-97**.

## Ecosistema 3D

Scheda **Ecosistema 3D** (*Lavoro*, dopo *Agenti*): ogni agente e ciò che lo definisce — modello, provider, esecuzione,
skill, knowledge, MCP, tool, software, prompt, direttive, ruoli, sottoagenti, e le skill e i plugin delle app in cui
lavora — come grafo 3D navigabile. Legge le stesse API della scheda Agenti e del Knowledge Hub, non scrive nulla,
maschera i segreti. Decisioni: [ADR-037](../adr/ADR-037-agent-ecosystem-3d.md). Nuove dipendenze della console:
`three` e `@react-three/fiber`, in un chunk caricato solo quando apri la scheda.

## Verificato prima del merge (2026-10-11)

| Suite | Esito |
|---|---|
| Java (`cd backend && ./mvnw -B test`, Testcontainers) | **705, 0 falliti (1 saltato: il test dei link simbolici, che Windows non crea senza privilegi)** |
| Console (`npx vitest run`) | **142/142**, due esecuzioni consecutive; `npm run typecheck` pulito |
| AI Engine (`pytest`) | **73/73** |

Unica correzione in questa chiusura: il test della scheda 3D in `App.test.tsx` passava da solo e falliva con l'intera
suite, perché il caricamento di three.js superava il secondo che `findByRole` aspetta di default. Ora aspetta fino a
10 s.

## Da fare tu

1. **Push** di `master` (e, se vuoi conservarli su GitHub, dei branch delle fasi):

   ```bash
   git push origin master
   ```

   Il push avvia la CI su `master`.
2. Su un'altra macchina, dopo il pull: `npm ci` in `frontend/` (nuove dipendenze three.js). `scripts\start-dev.ps1`
   lo fa da solo solo se `node_modules` manca.
3. Riavvia il backend dal codice di `master` se gira ancora quello di prima: le migrazioni V28…V39 sono additive e
   Flyway le applica all'avvio.
