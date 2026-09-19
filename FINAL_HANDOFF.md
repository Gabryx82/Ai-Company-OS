# FINAL HANDOFF — PHASE 2, Assignment

**Per la review umana. Niente è stato integrato in `master`.**

- Integration branch: **`autonomous/phase-2-assignment`**
- `master`: fermo a **`d5ff121`**, intatto
- Suite: **219 test verdi** contro PostgreSQL reale, schema **`V7`**
- Nessun failure aperto, nessun push, nessun remote configurato

> **Due fasi attendono la stessa accettazione.** PHASE 2 è costruita **sopra** PHASE 1 e la
> contiene: `autonomous/phase-2-assignment` discende da `autonomous/phase-1-foundations`
> (`0a35ac0`) in linea retta. Accettare PHASE 2 accetta anche PHASE 1.
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

Sono hard stop del charter, e nessun agente le ha prese.

**1. `TD-31` — unificare il ciclo di vita di `Agent`.** `Project` usa un enum chiuso, `Agent` un
booleano. Unificarli richiede di **eliminare una colonna**: migrazione irreversibile, hard stop
#3. Nel frattempo il contratto pubblico è già uniforme, perché `AgentResponse.status` è
**derivato**. Il costo di non decidere è basso, ed è la ragione per cui è ancora qui.

**2. `TD-14` — la CI.** Una CI reale richiede un remote, e un push è hard stop #4. È l'unico
debito di questa lista il cui costo cresce **a ogni task**, e va deciso prima di PHASE 3, non
dopo.

## 5. Che cosa succede se si accetta

Il merge in `master` è il gesto con cui il lavoro è accettato, ed è umano (charter §8).

```bash
git checkout master
git merge --ff-only autonomous/phase-2-assignment
```

La storia è lineare, quindi è un fast-forward. Porta in `master` **PHASE 1 e PHASE 2 insieme**,
perché la seconda contiene la prima.

Per una conferma indipendente prima di accettare:

```bash
cd backend && ./mvnw -B clean test
```

219 verdi. **Docker deve essere in esecuzione**: i test girano contro un PostgreSQL reale via
Testcontainers.

**Una cosa da sapere sul database di sviluppo locale.** È a **`V3`** e non ha mai visto
`V4`…`V7` — trovato dal censimento di TASK-010, e nessun documento lo diceva prima. Al primo
avvio in profilo `dev` le quattro migrazioni si applicheranno in ordine. `V7` passerà, perché
l'unica riga di `tasks` ha `status = 'OPEN'`. **Non serve `docker compose down -v`.**

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
