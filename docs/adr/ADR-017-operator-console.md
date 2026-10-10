# ADR-017 — La console dell'operatore: un client del contratto, non una seconda fonte di regole

- **Stato**: Accettata e implementata (TASK-021, TASK-022)
- **Data**: 2026-09-23
- **Decisa da**: agente, entro il blocco PHASE 3–7 autorizzato il 2026-09-23
- **Attua**: `MIGRATION_MAP.md` M-6 («React + TypeScript + Vite; client tipizzato generato da
  OpenAPI») e la metà OpenAPI di M-3.

## 1. Decisione

Una single-page application in `frontend/` — React 19, TypeScript, Vite — che parla **solo** con il
control plane, con il token dell'operatore. **Nessuna regola di dominio vive nella console**: mostra
ciò che il server permette e riporta ciò che il server rifiuta. L'unica eccezione è di presentazione:
la console mostra solo gli archi che partono dallo stato corrente (ADR-014), ma è il server a
decidere, e un arco rifiutato è mostrato come il server lo rifiuta.

## 2. Il contratto è generato, e tenuto al codice due volte

```
codice Java ──(OpenApiContractTest: byte per byte)──▶ docs/api/openapi.json
docs/api/openapi.json ──(CI: npm run api:types && git diff --exit-code)──▶ frontend/src/api/schema.d.ts
```

Una deriva in qualunque punto rende rossa la build. `schema.d.ts` non si modifica a mano;
`src/api/types.ts` ne deriva alias che rendono espliciti i campi sempre presenti, perché springdoc non
marca come obbligatorio nessun campo di risposta.

springdoc **3.1.1**, solo API (nessuna Swagger UI). `/v3/api-docs` richiede il token come ogni altra
rotta (ADR-013).

## 3. I tre protocolli del server, nel client

| Protocollo | Nel client |
|---|---|
| Bearer (ADR-013) | Ogni richiesta; token in `sessionStorage`, **mai in un cookie** (il browser non deve allegarlo da sé); un `401` riporta al login |
| `ETag`/`If-Match` (ADR-009) | Ogni lettura singola restituisce il tag; ogni mutazione lo **richiede come argomento**. Una lettura singola senza `ETag` leggibile fallisce subito (`missing-etag`) invece di produrre un `428` dopo |
| Problem details (ADR-007) | `ApiProblem` con `type`, `slug`, `errors` per campo; la console ramifica sul `type` |

**Il `412` non si ritenta.** Il drawer del task agisce col tag con cui ha letto il task; se qualcuno
ha scritto nel frattempo, dice che il task è cambiato e lo rilegge — non ripete la richiesta col tag
nuovo, perché sovrascriverebbe una modifica che l'operatore non ha visto. Verificato per mutazione.

I listati non portano `ETag` (TD-33): un'azione su una riga di tabella (agenti, progetti) legge prima
la risorsa singola e agisce col suo tag. È dichiarato, non nascosto.

## 4. Il flusso che la console rende percorribile

1. crea un task (board a tre colonne, una per stato di ADR-011);
2. aprilo: suggerimenti di routing (TD-08) e assegnazione;
3. lancia una run scegliendo il modello (quelli **disponibili** dell'engine, attraverso
   `GET /api/engine/models`, così il browser non tiene mai il token dell'engine);
4. leggi l'output, i token, il tipo di un fallimento, i prompt esatti inviati;
5. decidi tu: `Mark done`. **Una run non completa mai un task** (ADR-016 §3).

## 5. Fuori scope

Router con URL profonde (la navigazione è per hash), aggiornamenti push (si fa polling solo mentre
una run è in corso), gestione multi-utente, internazionalizzazione, test end-to-end in browser nella
CI (i test di componente usano un `fetch` finto; la verifica dal vivo è documentata in TASK-022).

## Revisione PHASE 32 (2026-10-08) — il contratto dice che cosa può essere null (TD-47)

springdoc non segnava nessun campo di risposta come richiesto: i tipi generati erano tutti facoltativi
e la console li correggeva con `Wire<T>` (primo livello) e `Deep<T>` (livelli annidati), che però
dichiarava non-null campi che il server manda `null`.

- **Il contratto**: `ResponseShapes` (un `OpenApiCustomizer`) rende *richiesti* tutti i componenti di
  ogni record raggiungibile solo dalle risposte — un record li serializza sempre, Jackson scrive i null —
  e *nullable* esattamente quelli annotati `@Nullable` (jspecify; mai un primitivo). Nel 3.1:
  `"type": ["string", "null"]`, o `anyOf` con `null` per un riferimento. Le forme che sono anche corpi di
  richiesta restano come le genera springdoc: lì un campo si può omettere.
- **La verifica**: un filtro presente solo nei test (`ResponseContractRecorder`) confronta **ogni**
  risposta JSON della suite con `docs/api/openapi.json`; `ResponseContractTest` fallisce su un campo
  richiesto assente o su un `null` non dichiarato, con lo schema, il campo e la rotta. Le annotazioni
  sono state ricavate così (217 campi) e completate a mano per i casi che i test non producono (30:
  l'utente di un token di servizio, il primo accesso, una sessione di terminale collegata…).
- **La console** usa i tipi generati così come sono: `Deep<T>` è eliminato, e con lui le correzioni a
  mano di `WorkspaceDocuments`, `AgentProfile`, `DailyItem` (ora le dice il contratto). `Wire<T>` resta
  solo per la struttura di un blocco del Blueprint, che è anche un corpo di richiesta.

## Revisione PHASE 32 (2026-10-10) — la sessione in un cookie (TD-53)

La riga «Bearer (ADR-013)» della tabella sopra non vale più per la console: il token di sessione è un cookie
`HttpOnly` che il control plane imposta e nessuno script legge; la console manda `credentials: "include"` e, su
ogni scrittura, il token anti-CSRF della sessione in `X-AICOS-CSRF`. Nel `sessionStorage` restano indirizzo,
utente e token anti-CSRF. Un `401` riporta ancora al login. Dettagli in ADR-024, revisione TD-53.
