# TASK-008 → prossimo agente

Conciso per chi arriva senza cronologia. Lo stato completo è in
`.company-os/PROJECT_STATE.md`; le decisioni in
`docs/adr/ADR-009-optimistic-concurrency-http-contract.md`.

## Stato

- Branch di lavoro `task-008-optimistic-concurrency`, integrato in fast-forward in
  `autonomous/phase-2-assignment`.
- **179 test verdi**, schema **`V5`**, nessun failure aperto.
- `master` fermo a `d5ff121`. Intoccato, e resta il gate umano.

## Le tre cose da sapere prima di scrivere codice

**1. Ogni mutazione di una risorsa esistente richiede `If-Match`, e il confronto ha un posto
solo.** Dentro la transazione, **dopo** il lock esclusivo sulla riga (L0/L1), **prima** di
qualunque regola. Sopra il lock è un check-then-act; sotto le regole un chiamante stantio riceve
il `409` del dominio invece del `412`. Entrambe verificate per mutazione.

**2. `@Version` non è il rilevatore, e non aggiungere un handler per `OptimisticLockException`.**
Sui nostri write path l'entità è caricata sotto `PESSIMISTIC_WRITE`: una transazione che ha atteso
rilegge l'ultima versione committata, quindi le versioni coincidono sempre e quell'eccezione non
arriva mai. Sarebbe un `412` che non scatta.

**3. Se aggiungi un percorso di scrittura, `PreconditionCoverageTest` ti ferma.** L'insieme dei
write path è pinnato per servizio. Non puoi aggiungerne uno senza scriverlo lì e dichiarare se
prende una `Precondition`. È P4 reso eseguibile, e serve esattamente alla prossima task.

## TASK-009 — Task → Agent, e che cosa eredita

`PUT /api/tasks/{id}/agent` **nasce con la precondizione**, non la acquista dopo: firma
`(Long taskId, Long agentId, Precondition precondition)`, confronto subito dopo `findByIdForUpdate`.
Aggiungi la riga a `PreconditionCoverageTest` con l'ultima, non alla fine.

Le tre domande di dominio da porre **prima** del codice, come ADR-005 fece per le sue:

1. un task può essere assegnato a un agente **disattivato**?
2. un task in un progetto **archiviato** può cambiare agente? (ADR-006 §2 ha già la forma della
   risposta: «qualunque scrittura futura su quel task → `409`, per costruzione»);
3. disattivare un agente fa qualcosa ai suoi task, o la coerenza è **derivata** come in ADR-006 §1?

**Il punto che non si può assumere.** L'ordine globale L5 di ADR-006 §4 è `tasks` → `projects`. Con
`agents` diventa una terza classe di righe, e ADR-006 lo dice a chiare lettere: «se un percorso
futuro dovesse bloccare un task tenendo già un lock su un progetto, l'ordine globale va rivisto,
non aggirato». **L'aciclicità va ridimostrata**, non ereditata.

Nota utile: la versione dell'agente **non** deve muoversi quando un task gli viene assegnato
(**P3**). Vale per gli agenti la stessa ragione per cui vale per i progetti — due assegnazioni allo
stesso agente attivo non sono in conflitto, e `I-5` ha il test gemello già scritto da copiare.

## Debito che questa task lascia

| ID | Contenuto |
|---|---|
| **TD-32** | *(nuovo, MINOR)* L'entity-tag è forte ma deriva dalla versione della riga, non dai byte della rappresentazione. Nessun effetto su `If-Match`; effetto sulla cache HTTP, che il progetto non usa |
| **TD-33** | *(nuovo, MINOR)* I listati non portano ETag: mutare N risorse costa N letture singole. Non motivato finché non esiste un client che muta in blocco |
| L-1…L-4 | Rilievi LOW della review, in `ARTIFACT.md` §7. Nessuno blocca TASK-009 |

**TD-28 e TD-30 sono chiusi**, e non perché il codice sembri diverso: togliere il confronto rende
rosso, e un test a due thread mostra il `412` che prima non esisteva.
