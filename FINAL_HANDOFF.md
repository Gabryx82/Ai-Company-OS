# FINAL HANDOFF — Blocco PHASE 3 → PHASE 7

> **Da accettare.** Scritto per la Human Final Review del blocco autorizzato il 2026-09-23. Il
> lavoro non è accettato finché un umano non lo accetta; il merge in `master` è quel gesto, e
> nessun agente lo esegue (charter §8).
>
> L'handoff di PHASE 2 è conservato in `docs/handoff/FINAL_HANDOFF_PHASE_2.md`, quello di PHASE 1
> in `docs/handoff/FINAL_HANDOFF_PHASE_1.md`.

- **`master`**: `d166870`, **intoccato**.
- **Da integrare**: `autonomous/phase-7-operator-console`, che contiene le cinque fasi in linea retta
  sopra `master` (catena `phase-3 → phase-4 → phase-5 → phase-6 → phase-7`). Un solo fast-forward.
- **Suite**: **329** Java + **51** AI Engine + **14** console = **394 verdi**; CI con tre job.
- **Stream**: `V8` → **`V11`**. **Live dev DB: ancora `V3`**, mai toccato: ogni smoke test ha girato
  su un clone.

## 1. Che cosa è stato costruito

| Fase | Obiettivo raggiunto | Task | ADR |
|---|---|---|---|
| **3 — Security baseline** | Nessuna richiesta senza un chiamante che il control plane sappia nominare; browser solo da origini dichiarate | TASK-013, 014 | 013 |
| **4 — Task lifecycle** | Un task si muove lungo quattro archi dichiarati; dettagli modificabili; priorità chiusa | TASK-015, 016 | 014 |
| **5 — AI Engine** | Il servizio Python di ADR-001: un modello reale dietro un contratto v1 autenticato | TASK-017, 018 | 015 |
| **6 — Execution** | Un agente esegue un task, e il control plane registra tutto; `MasterOrchestrator` sostituito | TASK-019, 020 | 016 |
| **7 — Operator console** | Tutto ciò che l'API permette, da un browser, con tipi generati dal contratto | TASK-021, 022 | 017 |

**Debiti chiusi**: TD-04, TD-08, TD-11, TD-12 (metà `priority`), TD-17, TD-18, TD-36, TD-37; rilievi
R5 e R7 di TASK-001. **Aperti dal blocco**: TD-38, TD-39, TD-40.

## 2. Le decisioni che meritano una lettura umana

### 2.1. Sicurezza prima delle transizioni (ROADMAP §3)
`PROJECT_STATE.md` lasciava la scelta TD-04/TD-37 come prima decisione di PHASE 3. Scelto TD-04:
è il debito che ogni fase successiva tocca, `MIGRATION_MAP.md` M-7 dice di anticiparlo prima delle
credenziali di provider (PHASE 5), e il suo costo cresce con ogni test scritto prima.

### 2.2. Token per nome, non utenti (ADR-013 §2)
Nessun utente, password, RBAC o OAuth: il prodotto è *local-first* con un operatore al centro, e
ogni alternativa richiedeva un bootstrap del primo utente o un sistema esterno. Il nome del token è
già il *principal*, e le run lo registrano (`requestedBy`). **Utenti veri sono una decisione futura.**

### 2.3. Quattro archi, e due esclusi (ADR-014 §2)
`OPEN → DONE` e `DONE → IN_PROGRESS` non esistono. Solo `start` guarda l'agente: uscire da
`IN_PROGRESS` non dipende mai da lui (ADR-010 D3 sopravvive).

### 2.4. Una run non completa il task (ADR-016 §3)
`SUCCEEDED` dice che il modello ha risposto, non che la risposta sia buona. L'operatore legge e
decide. È la scelta *human in the loop* di `PRODUCT_VISION.md`, presa esplicitamente.

### 2.5. Il costo cloud è spento per costruzione (ADR-015 §5)
Il provider Anthropic (SDK ufficiale, `claude-opus-5`, refusal fallback `"default"`) esiste ma **è
disattivo senza `ANTHROPIC_API_KEY`**. Nessun test lo raggiunge, nessuna chiamata cloud è stata
fatta. TD-40 (nessun limite di costo) va chiuso prima di abilitarlo senza presidio.

### 2.6. Il runtime a grafo resta non deciso
ADR-001 lo rinviava a un esperimento con criteri misurabili; ADR-015 non lo sceglie per omissione.

## 3. Che cosa hanno trovato le verifiche, e che i test non vedevano

Il charter chiede di vedere rosso un test prima di crederci. Nel blocco questo ha trovato difetti
**nel lavoro dell'agente stesso**, e sono registrati:

| Dove | Che cosa | Esito |
|---|---|---|
| Harness di mutazione (TASK-013) | Cinque «rossi» erano falsi: il wrapper Maven non partiva | L'harness rifiuta un verdetto senza output del runner. Ha poi rifiutato correttamente altri tre giri difettosi |
| Sicurezza (TASK-013) | Un token sbagliato trattato come anonimo non cambiava nulla sulle rotte protette | Test aggiunto; il comportamento dichiarato è ora verificato |
| CORS (TASK-014) | Un mutante equivalente, e un test che guardava il valore invece della ragione | Mutante sostituito; test stretto |
| Concorrenza (TASK-015) | Il test a barriera restava verde **senza lock di riga** | Sostituito da interleaving forzato |
| Routing (smoke di TASK-020) | `post` ↔ `postgresql`: database specialist suggerito per un endpoint HTTP | Soglia del prefisso a 5, pinnata |
| Console (dal vivo, TASK-022) | `/actuator/health` senza CORS: la console avrebbe detto «down» a un backend acceso | Corretto, pinnato, riverificato nel browser |

**Mutazioni eseguite: 58** (5+5+7+6 nel control plane di PHASE 3–4, 6+6 nell'engine, 9+5 sulle run
e sul routing, 3+5 su contratto e console, 1 sul README). **57 rosse** dopo le correzioni; **una**
(TASK-014 M1) è un mutante equivalente — Spring Security applica CORS da sé quando il bean esiste — ed
è stata sostituita da M1b, rossa. Albero verificato pulito prima e dopo ciascuna.

## 4. Database

| | Prima | Dopo |
|---|---|---|
| Stream | `V8` | **`V11`** |
| `V9` | — | `tasks_priority_check` (`LOW`, `MEDIUM`, `HIGH`). Non additiva per costruzione, additiva sui dati: censimento in `tasks/TASK-016/CENSUS.md` (solo `HIGH`/`LOW`, live DB `LOW`). Si ferma lasciando intatta una riga fuori vocabolario |
| `V10` | — | `task_runs`, con vincoli che legano esito e stato e al più una run non finita per task. Additiva |
| `V11` | — | `agents.model`, nullable. Additiva |
| Live dev DB | `V3` | **`V3`**, intoccato |

**Upgrade verificati**: `MigrationStreamTest` su ogni coppia consecutiva fino a `V11`, più `V8→V9`
reale e il rifiuto di `V9`; e **tre volte su un clone del database reale** (`V3 → V8`, `V3 → V11`
due volte), con la riga reale e i tre agenti del seed preservati. **Al primo avvio in `dev` sul
database reale si applicheranno `V4…V11`**, compresa `V8` (distruttiva, autorizzata il 2026-09-19).

## 5. Git e CI

- 28 commit sopra `master`, storia lineare, nessun merge commit, nessuna riscrittura, nessun force.
- Branch creati e pushati: 5 integration branch (`autonomous/phase-{3-security,4-task-lifecycle,5-ai-engine,6-execution,7-operator-console}`)
  e 9 branch di task (`task-013…task-021`; TASK-022 condivide il branch di TASK-021, dichiarato in
  `PHASE_7_PLAN.md`).
- CI: job `build-and-test` (Java), `ai-engine` (Python 3.12), `frontend` (Node 22: tipi in sincronia,
  typecheck, test, build).

## 6. Come si prova

```powershell
.\scripts\start-dev.ps1 -Database aicompany_try   # dopo aver clonato, vedi RUNNING.md §0
```

Console `http://localhost:5173`, token `dev-operator-token-change-me`. Con Ollama in esecuzione e
`llama3.2:3b` installato (su questa macchina lo è), un agente con `model: ollama:llama3.2:3b` produce
output reale in ~15–25 s. Senza Ollama, `echo:default` risponde in modo deterministico.

## 7. Verificato e non verificato

- ✅ Flusso API end-to-end con modello locale reale, su clone del DB reale (TASK-020).
- ✅ Console avviata, login reso, CORS dal browser verificato.
- ❌ **Login e flusso autenticato nel browser**: l'agente non digita token nei campi di un browser.
  È il primo controllo da fare nella review.
- ❌ Nessuna chiamata a un modello cloud.

## 8. Candidati per il blocco successivo

In ordine di `AUTONOMOUS_LOOP.md` §4: **TD-40** (costi), **TD-38** (storia delle transizioni), la
rivalutazione degli otto debiti solo-audit (`DEBT_REGISTRY.md` §4), **TD-39** (cancellazione delle
run), poi la target architecture: Skills/Rules/Tools/MCP registry, Planner, runtime a grafo (con
l'esperimento che ADR-001 chiede).
