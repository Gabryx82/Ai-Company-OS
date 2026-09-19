# TASK-012 → prossimo agente

Stato completo in `.company-os/PROJECT_STATE.md`, decisioni in
`docs/adr/ADR-012-agent-lifecycle-unification.md`, evidenza TD-14 in `EVIDENCE_TD14.md`.

## Stato

- **223 test verdi**, schema **`V8`**, nessun failure aperto.
- `master` è la linea principale. **PHASE 3 non è iniziata.**
- Nessun remote configurato.

## Le quattro cose da sapere

**1. `agents.active` non esiste più.** Il ciclo di vita è `AgentStatus` (`ACTIVE`/`INACTIVE`), con
`agents_status_check` nel database. `Agent.isActive()` **esiste ancora** ed è un lettore
**derivato** — non è un residuo da ripulire: è ciò che le regole altrove chiedono davvero
(`Task.assignTo` vuole sapere se il lavoro può essere assegnato). ADR-012 §4.

**2. `AgentResponse` espone ancora `active` E `status`, e deve continuare a farlo.**
Non è una ridondanza da eliminare: è contratto pubblico da TASK-007, e toglierlo romperebbe ogni
client per una ridenominazione. Ciò che TD-31 ha cambiato è **quale dei due è reale**, non la
forma della risposta. Stessa cosa per `?active=true|false`, che resta un booleano.

**3. Il dev seed è valido SOLO alla testa dello stream.** Nomina la colonna del ciclo di vita, e
quella colonna è cambiata con `V8`. Nel prodotto non è un problema —
`DevSeedFlywayConfiguration` applica il seed dopo l'intero schema — ma un fixture che semina a una
versione intermedia deve chiedere a `information_schema` quale colonna esista
(`MigrationStreamTest.insertAgentAt`).

**4. `DevSeedFlyway.apply` chiama `repair()`, e NON va copiato sullo stream dello schema.**
Serve perché `V8` ha costretto a modificare un seed già applicato, e Flyway valida i checksum.
Sullo stream dello schema quel fallimento rumoroso è il comportamento **voluto**: è l'incidente
che la validazione esiste per intercettare. ADR-012 §5.

## TD-14 — che cosa serve per sbloccarlo

**Un dato solo: l'URL del repository remoto.**

```
https://github.com/<owner>/<repo>.git     oppure     git@github.com:<owner>/<repo>.git
```

Non è deducibile dal repository: non esiste in nessun file, nessuna configurazione, nessun ref.
Verificato, non supposto — `EVIDENCE_TD14.md` ha i comandi.

Serve anche sapere **se il repository esiste già o va creato**, perché crearlo è un'azione su un
sistema esterno (hard stop #4) e `gh` non è installato su questa macchina.

Quando arriva: `git remote add origin`, verifica fetch/push (il push resta hard stop #4 e va
autorizzato), poi un workflow che esegua `./mvnw -B clean test` — e il runner **deve avere un
daemon Docker**, perché la suite gira su Testcontainers.

## PHASE 3 — non iniziata, e non decisa

I due candidati restano **TD-04** (autenticazione) e **TD-37** (transizioni di `status`).
Nessuno dei due è stato scelto: questa task ha chiuso TD-31 e ha documentato il blocco di TD-14,
niente altro.

Se si sceglie TD-37, le tre cose che si applicheranno a qualunque mutazione nuova sono in
`tasks/TASK-011/HANDOFF.md` e non vanno riscoperte.

## Debito

**Nessuno di nuovo.** Prossimo id libero: **`TD-38`**.
Rilievi LOW in `ARTIFACT.md` §6; nessuno blocca niente.
