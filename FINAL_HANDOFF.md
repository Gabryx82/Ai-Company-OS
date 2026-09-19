# FINAL HANDOFF — PHASE 2, Assignment

> ## ✅ ACCETTATA. Integrata in `master` il 2026-09-19.
>
> Questo documento è stato scritto **per** la review umana; la review è avvenuta e il lavoro è
> stato accettato. Il testo che segue è conservato **com'era**, perché è ciò che è stato
> valutato — tranne questo riquadro e §5, che dicono che cosa è poi realmente successo.
>
> `master`: **`d5ff121` → `6dc5989`**, due fast-forward consecutivi (PHASE 1, poi PHASE 2),
> 41 commit, **nessun merge commit**, nessuna storia riscritta. Suite **158/158** dopo il primo
> merge e **219/219** dopo il secondo.

- Integration branch: **`autonomous/phase-2-assignment`** (`6dc5989`, fermo: marcatore storico)
- `master`: **`6dc5989`** — era `d5ff121`
- Suite: **219 test verdi** contro PostgreSQL reale, **migration stream a `V7`**
  (il database di sviluppo locale è a `V3`: sono due numeri diversi, vedi §5)
- Nessun failure aperto, nessun push, nessun remote configurato

> **Le due fasi sono state accettate insieme**, come questo documento prevedeva: PHASE 2 è
> costruita **sopra** PHASE 1 e la contiene, perché `autonomous/phase-2-assignment` discende da
> `autonomous/phase-1-foundations` (`0a35ac0`) in linea retta.
> L'handoff di PHASE 1 è conservato, non sostituito, in
> **`docs/handoff/FINAL_HANDOFF_PHASE_1.md`**.

---

## 1. Che cosa è stato costruito

Quattro task autonome, senza approvazione intermedia, dopo le sette di PHASE 1.

L'obiettivo dichiarato della fase era:

> **Il Company OS sa dire chi lavora su che cosa, e due client non possono sovrascriversi in
> silenzio mentre lo dicono.**

È raggiunto in entrambe le metà, e la fase ne ha aggiunta una terza che il piano prevedeva come
possibile: un task adesso sa anche **in che stato è**.

| Task | Che cosa ha aggiunto |
|---|---|
| **TASK-008** | **Concorrenza ottimistica nel contratto HTTP.** `ETag`/`If-Match` obbligatorio su ogni mutazione di risorsa esistente, protocollo P0–P4. I lock serializzavano ma non **rilevavano**: adesso il sistema fa entrambe le cose |
| **TASK-009** | **La relazione `Task` → `Agent`.** Il sistema sa dire chi lavora su che cosa. Lock graph ridimostrato su tre classi di righe |
| **TASK-010** | **Il vocabolario chiuso di `Task.status`.** `OPEN`, `IN_PROGRESS`, `DONE`, imposti in tre punti. `"banana"` non entra più |
| **TASK-011** | **Il registro del debito disambiguato**, e `docs/RUNNING.md` reso di nuovo vero |

Suite: **158 → 219**. Schema: **`V4` → `V7`**, tre migrazioni, tutte additive sui dati.

## 2. Le decisioni che meritano una lettura umana

Non sono tutte. Sono quelle in cui il sistema ha preso una direzione che sarebbe stato ragionevole
prendere diversa.

### 2.1. `@Version` è un contatore, non il rilevatore (ADR-009)

La cosa ovvia sarebbe stata affidarsi a `OptimisticLockException`. **Non scatta mai**: dopo
l'attesa su `PESSIMISTIC_WRITE` l'entità è caricata **già alla versione nuova**, quindi le due
versioni coincidono e JPA non solleva niente. Affidarcisi sarebbe stato un `412` che non arriva.

Il rilevamento è un confronto esplicito, **dentro la transazione, dopo il lock, prima delle
regole**. Non c'è handler per `OptimisticLockException`, e **non va aggiunto**.

### 2.2. Disattivare un agente **non** congela i suoi task (ADR-010, D3)

È la decisione dove il dominio ha divergito da sé. Archiviare un progetto **congela** i task che
contiene; disattivare un agente **no**, ed è l'opposto della regola gemella.

La ragione: il momento in cui si disattiva un agente è esattamente il momento in cui bisogna poter
**riassegnare** il suo lavoro. Congelarlo lo intrappolerebbe con chi non può eseguirlo, e l'unica
via d'uscita sarebbe riattivare l'agente — cioè annullare la ragione per cui lo si è spento.

L'alternativa (far fallire `deactivate` finché ha lavoro) avrebbe **chiuso un ciclo nel lock
graph**. Dominio e concorrenza rispondono la stessa cosa.

### 2.3. `Task.status` ha tre valori e nessuna transizione (ADR-011)

Il censimento ha trovato **un solo valore esistente** (`OPEN`) in cinque fonti, quindi la
migrazione non ha toccato una riga. La scelta di aggiungerne **due** e non zero è argomentata: un
vocabolario di un solo valore renderebbe le transizioni impossibili *per costruzione*, e le
transizioni sono la ragione per cui TD-12 chiedeva il vocabolario.

**Non è stata introdotta nessuna macchina a stati**, e non esiste un percorso per **cambiare** lo
stato di un task. È il limite più rilevante che la fase lascia: **TD-37**.

### 2.4. Un valore fuori vocabolario è un errore di validazione (ADR-011 §4)

Tipizzare il campo della request come enum sembrava più pulito e avrebbe prodotto
`malformed-request` — «*the request body could not be read*» — che è **falso**, su un `type` su
cui i client si ramificano. Il campo resta `String` con un vincolo. **Nessun `type` nuovo, il
contratto d'errore di PHASE 1 è intatto.**

### 2.5. Il registro del debito non è stato rinumerato (TASK-011)

Cinque identificatori significano cose diverse nei due registri. Entrambe le rinumerazioni
avrebbero rotto qualcosa — i riferimenti scritti finora, oppure ciò che l'audit *osservò*. La
collisione è stata resa **interpretabile** invece che cancellata.

## 3. Che cosa il sistema **non** fa, dichiarato

Questa è la sezione da leggere se si sta per costruirci sopra.

| Limite | Debito |
|---|---|
| **Nessuna autenticazione.** Backend e database non vanno esposti fuori da `localhost` | **TD-04** — primo candidato di PHASE 3 |
| **Non si può cambiare lo `status` di un task.** Si sceglie alla creazione e resta | **TD-37** |
| **`Task.priority` è ancora una stringa libera** | **TD-36** |
| **`MasterOrchestrator` è un placeholder**: quattro `if` su `contains()` che restituiscono nomi di agent che non esistono nel database | **TD-08** |
| **Nessuna CI.** Con 219 test e invarianti di concorrenza, il costo cresce a ogni task. Richiede un remote → **hard stop #4** | **TD-14** (vivo) |
| **`Agent` usa un booleano dove `Project` usa un enum.** Unificarli elimina una colonna → **hard stop #3**: decisione umana | **TD-31** |
| Nessun `DELETE` delle associazioni; nessun filtro per stato o per agente inattivo sui listati; nessuna paginazione | TD-34, TD-35, e le domande di contratto aperte |
| **Otto debiti dell'audit non sono stati rivalutati**, e diversi sono con ogni evidenza risolti | `docs/DEBT_REGISTRY.md` §4 |

## 4. Le due decisioni che aspettano una persona

> **Aggiornamento 2026-09-19, dopo l'accettazione.** Entrambe sono state affrontate da
> **TASK-012**, su richiesta umana e prima di PHASE 3:
>
> - **TD-31 è CHIUSO.** La decisione è stata presa esplicitamente da una persona, delimitata allo
>   scope documentato, e `V8` l'ha eseguita: `agents.active` eliminata, backfill biiettivo,
>   contratto pubblico invariato. ADR-012.
> - **TD-14 resta APERTO**, e il blocco si è rivelato essere **un dato mancante, non una
>   decisione**: il repository non contiene alcun URL, owner o nome di repository remoto, nessun
>   remote è mai stato configurato e `gh` non è installato. Serve l'URL del remote.
>   `tasks/TASK-012/EVIDENCE_TD14.md`.
>
> Il testo qui sotto è quello che è stato sottoposto alla review, conservato com'era.

Sono hard stop del charter, e nessun agente le ha prese.

**1. `TD-31` — unificare il ciclo di vita di `Agent`.** `Project` usa un enum chiuso, `Agent` un
booleano. Unificarli richiede di **eliminare una colonna**: migrazione irreversibile, hard stop
#3. Nel frattempo il contratto pubblico è già uniforme, perché `AgentResponse.status` è
**derivato**. Il costo di non decidere è basso, ed è la ragione per cui è ancora qui.

**2. `TD-14` — la CI.** Una CI reale richiede un remote, e un push è hard stop #4. È l'unico
debito di questa lista il cui costo cresce **a ogni task**, e va deciso prima di PHASE 3, non
dopo.

## 5. Che cosa è successo quando è stata accettata

**Eseguito il 2026-09-19**, dopo l'accettazione esplicita della review umana. Due fast-forward, in
quest'ordine, con la suite completa eseguita dopo ciascuno:

```bash
git checkout master                                      # d5ff121
git merge --ff-only autonomous/phase-1-foundations       # -> 0a35ac0 ... 158/158 verdi
git merge --ff-only autonomous/phase-2-assignment        # -> 6dc5989 ... 219/219 verdi
```

Due merge invece del solo `--ff-only` verso PHASE 2 che questo documento suggeriva: l'esito su
`master` è identico — PHASE 2 contiene PHASE 1 — ma così **l'accettazione di ciascuna fase è un
passo distinto e verificato**, con la suite della fase eseguita al suo checkpoint.

Verificato dopo i merge:

- **nessun merge commit prodotto**: `git log --merges d5ff121..master` è vuoto. L'unico merge
  commit della storia è `e8d0286` (TASK-001, 2026-09-11) ed era già in `master`;
- **nessuna storia riscritta**: `d5ff121` è ancora un antenato di `master`;
- working tree pulito dopo entrambi i merge;
- **219/219 verdi**, nessuna regressione.

**Docker deve essere in esecuzione** per riprodurlo: i test girano contro un PostgreSQL reale via
Testcontainers.

### `V3` e `V7` sono due numeri diversi

È l'ambiguità che il censimento di TASK-010 ha trovato nello stato del progetto, e vale la pena
non ricrearla:

| | Valore | Che cos'è |
|---|---|---|
| **Migration stream** | **`V7`** | La migrazione più alta che esiste nel repository. La prossima da scrivere è `V8` |
| **Live dev DB** | **`V3`** | Ciò che è stato realmente applicato al volume locale `aicompany_postgres_data` |

Sono indipendenti: il primo è una proprietà del **codice**, il secondo di **un'installazione**.
«Schema a `V7`» senza qualificatore significa **lo stream**, mai un database.

```bash
ls backend/src/main/resources/db/migration                      # stream -> V7
docker exec aicompany-postgres psql -U aicompany -d aicompany \
  -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank;"   # dev DB -> 1,2,3
```

Riverificato dopo i merge: stream `V7`, dev DB `V1,V2,V3`. Al primo avvio in profilo `dev` le
quattro migrazioni mancanti si applicheranno in ordine; `V7` passerà, perché l'unica riga di
`tasks` ha `status = 'OPEN'`. **Non serve `docker compose down -v`.**

## 6. Come riprendere senza questa conversazione

Il repository basta, ed è una proprietà verificata e non sperata: TASK-010 e TASK-011 sono state
eseguite in una sessione fredda, leggendo solo `.company-os/` e i documenti che esso indica.

| File | Ruolo |
|---|---|
| `.company-os/PROJECT_STATE.md` | **Fonte primaria dello stato.** Nessun altro lo sostituisce |
| `.company-os/AUTONOMOUS_CHARTER.md` | Autorità, guardrail, hard stop |
| `.company-os/AUTONOMOUS_LOOP.md` | La procedura, e come si sceglie la prossima task |
| `.company-os/PHASE_2_PLAN.md` | Dove andava la fase, e perché questo scope |
| `docs/DEBT_REGISTRY.md` | **I due spazi di identificatori del debito.** Da leggere prima di chiudere o citare un `TD-NN` |
| `docs/RUNNING.md` | Come si avvia, i 18 endpoint, il protocollo `If-Match` |
| `tasks/TASK-011/HANDOFF.md` | Il briefing per chi riprende |
| `docs/adr/ADR-0*.md` | Undici decisioni, con le alternative scartate e il perché |

## 7. Che cosa è andato storto, e perché è qui

Un handoff che elenca solo successi non è verificabile.

**TASK-009 — una mutazione che non sapeva revertirsi.** L'harness di verifica per mutazione ha
sostituito un blocco di codice con la stringa vuota e ha lasciato l'albero mutato. Ne sono seguite
**due diagnosi sicure e sbagliate** di un'implementazione che era corretta, la seconda costruita
su un log SQL vero ma raccolto da un albero corrotto.

La regola che ne è uscita è applicata da allora: **quando un test fallisce su codice che si crede
pulito, la prima ipotesi da verificare è che l'albero sia pulito davvero.** In TASK-010 l'albero è
stato verificato prima di ogni mutazione e dopo ogni revert, e le quattro mutazioni sono state
pulite.

**TASK-010 — un test il cui nome prometteva più di quanto verificasse.**
`theCheckConstraintDeclaresTheSameSetAsTheEnum` confrontava il vincolo con una costante, non con
l'enum, ed è rimasto **verde** mentre l'enum veniva allargato di un membro. L'ha trovato la
verifica per mutazione, non la rilettura — che è il motivo per cui la verifica per mutazione
esiste.

**TASK-011 — due istruzioni false in `docs/RUNNING.md`**, non due omissioni. Diceva che la
prossima migrazione si chiama `V2` mentre lo stream era a `V7`: chi la seguiva creava una
migrazione che Flyway rifiuta. Era lì da sei task.

**E tre affermazioni stantie in `PROJECT_STATE.md`**, corrette lungo la strada: lo schema
dichiarato `V6` per un database che è a `V3`, un conteggio di test fermo a 158, e un HEAD
dell'integration branch che il commit stesso che lo scriveva rendeva falso.

Il tema è uno, e vale la pena portarlo in PHASE 3: **i numeri e i percorsi scritti a mano vanno
stantii, e la documentazione che istruisce è più pericolosa di quella che manca.** È la ragione
per cui `docs/RUNNING.md` adesso dice *come leggere* la testa dello stream invece di limitarsi a
dichiararla, e per cui l'HEAD dell'integration branch non è più scritto come hash.
