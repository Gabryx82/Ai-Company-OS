# Roadmap — PHASE 3 → PHASE 7

> Piano persistente del blocco di cinque fasi aperto il 2026-09-23. La fonte primaria dello stato
> resta `PROJECT_STATE.md`: questo documento dice **quali fasi, in che ordine e perché**.
>
> Autorizzazione: richiesta umana esplicita del 2026-09-23 di completare almeno cinque fasi
> consecutive a partire dalla prossima non completata, con push su `origin` e uso della CI
> autorizzati, e una sola Human Final Review alla fine del blocco. `master` resta il checkpoint
> umano: nessuna fase del blocco vi entra senza quella review.

## 1. Da dove viene la sequenza

Il repository aveva **due** roadmap, e nessuna delle due diceva da sola cosa venisse dopo PHASE 2:

| Fonte | Che cosa dice | Stato |
|---|---|---|
| `docs/IMPLEMENTATION_PLAN.md` | Fasi 0–8 per domini (Intelligence, Graphs, Work Management…) | *Status: INITIAL / PRE-AUDIT*, dichiara di dover essere riscritto dopo TASK-000. Non è mai stato riscritto |
| `docs/audit/MIGRATION_MAP.md` §2 | Sequenza **M-0 → M-7**, prodotta *dall'audit* e con le dipendenze esplicite | M-0…M-4 sono coperte da PHASE 1 e PHASE 2 (persistenza, dominio, contratti, test, CI). **Restano M-5, M-6, M-7**, più la parte OpenAPI di M-3 |

La seconda è quella post-audit, quindi quella che vale. Più i due candidati che `PROJECT_STATE.md`
lasciava aperti per PHASE 3: **TD-04** (autenticazione = M-7) e **TD-37** (transizioni di stato).

## 2. Le cinque fasi

| Fase | Nome | Contenuto | Debiti / milestone |
|---|---|---|---|
| **PHASE 3** | **Security baseline** | Autenticazione a bearer token su tutta l'API, contratto `401` uniforme, policy CORS esplicita, bind su loopback | TD-04, TD-11, R5, R7 — M-7 anticipata |
| **PHASE 4** | **Task lifecycle** | Transizioni di `status` come macchina a stati; modifica dei dettagli di un task; vocabolario chiuso di `priority`; filtri sul listato | TD-37, TD-36, TD-34 (parziale) |
| **PHASE 5** | **AI Engine** | Il servizio Python di ADR-001: model gateway con provider (deterministico, Ollama locale, cloud opzionale), contratto HTTP versionato, autenticazione fra servizi | M-5 (metà provider) |
| **PHASE 6** | **Execution** | Le *run*: un agente esegue un task attraverso l'AI Engine; stato canonico delle run in Spring; `MasterOrchestrator` sostituito da routing sul registry reale | M-5 (metà orchestrazione), TD-08 |
| **PHASE 7** | **Operator console** | OpenAPI; frontend React + TypeScript + Vite: progetti, agenti, board dei task, lancio ed esito delle run | M-3 (OpenAPI), M-6, G-03 |

## 3. Perché questo ordine

Seguendo `AUTONOMOUS_LOOP.md` §4, livello per livello.

**PHASE 3 prima di tutto il resto — e prima di TD-37.** `PROJECT_STATE.md` diceva che la scelta
fra TD-04 e TD-37 era la prima decisione di PHASE 3. Tre argomenti, tutti dal repository, la
decidono per TD-04:

1. **È il debito che le fasi successive toccano** (livello 3). Ogni fase del blocco aggiunge
   endpoint, e ogni endpoint aggiunto senza autenticazione è superficie in più — `PHASE_2_PLAN.md`
   §2 lo diceva già. TD-37 invece non peggiora aspettando.
2. **`MIGRATION_MAP.md` M-7 lo dice testualmente**: «da anticipare se prima di M-7 vengono
   introdotte credenziali di provider». PHASE 5 le introduce.
3. **Il costo noto** — «cambierebbe ogni test di API» — cresce con ogni test scritto prima. Farlo
   per primo significa che i test di PHASE 4–7 nascono autenticati.

**PHASE 4 prima dell'esecuzione.** `PROJECT_STATE.md`: «un orchestratore che non muove il lavoro
attraverso gli stati non ha niente da orchestrare». TD-37 è il prerequisito dichiarato di TD-08.

**PHASE 5 prima di PHASE 6.** La run dipende dal gateway, non il contrario. ADR-001 mette le
chiamate ai provider nel servizio Python e lo stato canonico delle run in Spring: si costruisce
prima la dipendenza, poi chi la usa.

**PHASE 7 per ultima**, come M-6 nella mappa: la UI consuma contratti che devono già esistere e
non guida la loro forma. Nessuna fase architetturale viene saltata per arrivarci prima.

## 4. Git

```
master                                   checkpoint umano (6dc5989 → d166870, fermo)
└── autonomous/phase-3-security          da master
    └── autonomous/phase-4-task-lifecycle    da phase-3
        └── autonomous/phase-5-ai-engine         da phase-4
            └── autonomous/phase-6-execution         da phase-5
                └── autonomous/phase-7-operator-console  da phase-6
```

Come PHASE 2 discendeva da PHASE 1. Branch di task da ciascun integration branch, fast-forward
dopo suite verde. Ogni branch è pushato su `origin`, la CI gira su ogni push. **Nessun merge in
`master`**: alla fine del blocco `FINAL_HANDOFF.md` prepara una sola review umana, e l'ultimo
integration branch contiene tutte e cinque le fasi in linea retta.

## 5. Criterio di chiusura del blocco

1. ogni fase ha il proprio criterio (nel suo `PHASE_N_PLAN.md`) soddisfatto;
2. suite Java e suite Python verdi in locale **e in CI**;
3. uno smoke test end-to-end reale: stack avviato, operatore autenticato, task creato, assegnato,
   eseguito da un agente attraverso l'AI Engine, esito visibile nella console;
4. `PROJECT_STATE.md` e `FINAL_HANDOFF.md` bastano a una sessione fredda.
