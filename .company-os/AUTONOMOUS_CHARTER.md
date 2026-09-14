# Autonomous Charter

> **Modalità attiva dal 2026-09-14: Autonomous Project Mode con Human Final Review.**
> Sostituisce l'Human-in-the-Loop per singola task. Non sostituisce l'autorità umana.

## 1. Da dove viene l'autorità

L'obiettivo umano resta l'unica fonte di autorità. Questo documento non la trasferisce:
descrive **entro quale perimetro** un agente può agire senza chiedere, e dove deve fermarsi.

Quello che è cambiato è la **frequenza** dell'approvazione, non la sua esistenza. Prima ogni
task e ogni ADR passavano da un gate umano; adesso il gate è alla fine — `master` — e nel mezzo
l'agente decide. La domanda «posso?» è sostituita dalla domanda «è auto-approvabile?», che ha
cinque condizioni e non una sensazione.

## 2. Quando una decisione è auto-approvata

Tutte e cinque, non la maggioranza:

1. **È coerente con l'obiettivo del progetto** — la target architecture in `PROJECT_STATE.md`.
2. **Non viola un'ADR accettata.** Superarne una è possibile, ma si fa scrivendo l'ADR che la
   supera e dicendolo, mai per omissione.
3. **Resta dentro i guardrail** — la sezione 4 qui sotto.
4. **Ha test o verifiche adeguate.** «Adeguate» significa che un test esiste *e* che si è visto
   fallire quando doveva.
5. **Conseguenze e debiti sono registrati** prima di chiudere, non dopo.

Se una condizione non regge, la decisione non si prende di slancio: si restringe lo scope
finché regge, oppure si registra come debito e si va avanti.

## 3. Cosa un agente può fare senza chiedere

Identificare il prossimo problema utile; definire le task successive; creare
`TASK.md`, `CONTEXT.yaml`, `IMPLEMENTATION.md`, `ARTIFACT.md`, `HANDOFF.md`; scrivere ADR;
decidere lo scope tecnico locale; creare branch e commit; implementare; scrivere test; eseguire
review avversariali sul proprio diff; correggere i rilievi; registrare debito nuovo; aggiornare
`PROJECT_STATE.md`; chiudere una task; **iniziare la successiva senza attendere**.

Fra una task e l'altra non si aspetta input umano.

## 4. Guardrail — invarianti di lavoro

Valgono sempre, e non sono negoziabili da dentro una task:

- **`master` non si tocca.** È il checkpoint umano. L'integrazione avviene su
  `autonomous/phase-1-foundations`.
- **Nessun push, nessun remote, nessun sistema esterno.**
- **Storia solo in avanti**: nessun force push, rebase distruttivo, reset distruttivo,
  cancellazione di branch.
- **Lo schema è di proprietà di Flyway**, le migrazioni sono additive e retrocompatibili.
- **Nessun test si dichiara verde senza averlo visto rosso** quando il rosso era il punto.
- **Un debito non si chiude perché il codice sembra diverso**: serve una verifica che fallisca
  se il difetto torna.
- **Non si allarga lo scope per «sistemare tutto»**: quello che si nota e non si fa, si scrive.
- Niente segreti nei commit, niente dati locali, niente output di build.

## 5. Hard stop — qui ci si ferma e si chiede

Solo per questi. L'elenco è chiuso.

| # | Situazione |
|---|---|
| 1 | Force push, rebase o reset distruttivo, perdita intenzionale di storia |
| 2 | Eliminazione irreversibile di dati |
| 3 | Migrazione distruttiva o non retrocompatibile, senza alternativa ragionevole |
| 4 | Push, deploy, o qualunque modifica a sistemi remoti |
| 5 | Uso, modifica o esposizione di credenziali e segreti |
| 6 | Servizi o API con costo economico non già autorizzato |
| 7 | Cambio fondamentale di stack o architettura in contraddizione con gli ADR |
| 8 | Requisito di prodotto realmente ambiguo, che ammette due prodotti sostanzialmente diversi |
| 9 | Impossibilità tecnica di procedere, dopo tentativi ragionevoli **documentati** |

**Non sono hard stop**: test falliti, bug, race condition, rilievi di review, necessità di
refactoring, debito scoperto in corsa. Si risolvono. Un test rosso è lavoro, non un permesso da
chiedere.

Il caso 9 richiede la prova del tentativo: cosa si è provato, cosa è successo, perché la strada
è chiusa. «Non ci riesco» senza traccia non è un hard stop, è una task non finita.

## 6. Git

```
master                                 checkpoint umano, gate finale, intoccabile
└── autonomous/phase-1-foundations     integration branch
    ├── task-004-archival-consistency
    ├── task-NNN-<nome>                una per task, creata dall'integration branch
    └── ...
```

Una task entra nell'integration branch in **fast-forward**, solo dopo implementazione, review
avversariale e suite verde. La storia resta lineare. In `master` non entra niente fino alla
review umana finale.

## 7. Persistent resume

Il repository deve bastare. Una sessione nuova, senza cronologia di chat, deve poter leggere
`.company-os/` e continuare il loop.

Perciò, alla chiusura di **ogni** task, `PROJECT_STATE.md` riporta: branch corrente e HEAD
dell'integration branch; task completate; task corrente o prossima; decisioni architetturali;
stato della suite; debito rilevante; failure ancora aperti; prossimo passo autonomo.

Se una cosa non è scritta lì, per la sessione successiva non esiste.

## 8. Human final handoff

Alla fine dell'obiettivo o della fase — oppure a un hard stop — si prepara `FINAL_HANDOFF.md`
e ci si ferma. Il lavoro **non** è accettato finché un umano non lo accetta, e il merge in
`master` è quel gesto. Nessun agente lo esegue.

## 9. Documenti collegati

| File | Ruolo |
|---|---|
| `PROJECT_STATE.md` | **Fonte primaria dello stato.** Nessun altro documento lo sostituisce |
| `AUTONOMOUS_LOOP.md` | La procedura operativa: i passi di una task e come si sceglie la prossima |
| `AGENT_PROTOCOL.md` | Le regole di lavoro dell'agente, che restano valide |
| `TOKEN_POLICY.md` | Il budget di contesto, che resta valido |
