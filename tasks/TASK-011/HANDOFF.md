# TASK-011 → prossimo agente

**PHASE 2 è finita.** Il documento da leggere è `FINAL_HANDOFF.md`, che è di fase e non di task.
Stato completo in `.company-os/PROJECT_STATE.md`.

## Stato

- Branch `task-011-debt-registry-and-docs`, integrato in fast-forward in `autonomous/phase-2-assignment`.
- **219 test verdi**, schema **`V7`**, nessun failure aperto.
- `master` fermo a `d5ff121`. Intoccato, e resta il gate umano.

## Le tre cose da sapere

**1. Ci sono due spazi di identificatori del debito, e cinque collidono.**
`TD-14`, `TD-19`, `TD-20`, `TD-21`, `TD-22` significano cose diverse in
`docs/audit/TECHNICAL_DEBT.md` e in `PROJECT_STATE.md`. **Leggere
`docs/DEBT_REGISTRY.md` prima di chiudere o citare un `TD-NN`.** Il vivo è autoritativo, il
prossimo id libero è **`TD-38`**, e un test rende rossa la suite se un id dell'audit smette di
comparire nel registro.

**2. Gli otto debiti solo-audit NON sono stati rivalutati**, e diversi sono con ogni evidenza
risolti. È deliberato: «con ogni evidenza risolto» è «il codice sembra diverso», che il charter
vieta come criterio di chiusura. Rivalutarli è la task successiva più ovvia su questo materiale,
ed è piccola.

**3. `docs/RUNNING.md` è di nuovo vero, e conteneva istruzioni false — non solo lacune.**
Diceva che la prossima migrazione si chiama `V2` mentre lo stream era a `V7`. Se si trova un altro
valore scritto a mano in quel file, sospettarlo: è la seconda volta che un numero lì va stantio.

## PHASE 3 — i due candidati

Il charter §8 dice che a fine fase **ci si ferma**, quindi questa non è una scelta da fare senza
un umano. I due candidati, con gli argomenti già raccolti:

- **TD-04 — autenticazione.** `PHASE_2_PLAN.md` §2 lo indicava già come primo candidato di
  PHASE 3. Reale e in crescita: oggi non c'è niente, e ogni endpoint aggiunto è superficie. Costo
  noto: cambierebbe ogni test di API della fase.
- **TD-37 — le transizioni di `status`.** Aperto da TASK-010, ed è ciò che sblocca **TD-08** (la
  sostituzione di `MasterOrchestrator`) e il Planner: un task adesso sa dove sta, di chi è e in
  che stato è, ma **non può cambiare stato**, e un orchestratore che non muove il lavoro non ha
  niente da orchestrare. Arriva con le domande già formulate e non ancora decise — quali
  transizioni sono legali, `DONE` è terminale, serve un gate di approvazione.

Tre cose che si applicheranno a qualunque mutazione nuova, e che non vanno riscoperte:

- **eredita `If-Match`**, e `PreconditionCoverageTest` non lascia dimenticarlo (ADR-009 P4);
- **deve chiamare `Task.requireNotFrozen()`**, o ADR-006 §2 smette di essere vera — e non è
  automatico: vale perché il percorso la chiama (lezione di TASK-009);
- il ciclo di vita sta **sull'entità**, con transizioni esplicite e nessun setter (ADR-004 §4).

## Debito che questa task lascia

**Nessuno di nuovo.** Prossimo identificatore libero: **`TD-38`**.

| Rilievo | Contenuto |
|---|---|
| L-1 | `DEBT_REGISTRY.md` §5 duplica informazione che sta in `PROJECT_STATE.md`; l'allineamento fra i due resta a disciplina, quello con l'audit è coperto da test |
| L-2 | Le cinque collisioni sono una costante scritta a mano nel test. È voluto: una sesta deve obbligare qualcuno a dichiararla |
| L-3 | L'elenco degli endpoint di `RUNNING.md` è a mano e nessun test lo verifica completo. Si chiuderebbe con OpenAPI (`TD-03`/M-3 dell'audit), che è una decisione a sé |
