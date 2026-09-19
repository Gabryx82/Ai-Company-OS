# TASK-012 — TD-14 e TD-31

Due debiti indipendenti, affrontati insieme su richiesta umana del 2026-09-19, prima di PHASE 3.
Commit separati, perché non si toccano.

## TD-14 — CI / remote

**Obiettivo**: configurare il remote e rendere possibile una CI reale.

**Esito: BLOCCATO su un dato che non esiste nel repository.** Evidenza in `EVIDENCE_TD14.md`.
Nessun `origin` configurato, nessuna traccia di un remote mai esistito, nessun URL/owner/repo
citato in alcun file, `gh` non installato. La regola era esplicita — «non inventare owner, URL o
repository remoto» — quindi ci si ferma qui e si riporta il dato mancante.

**Non fatto di proposito**: nessun workflow CI scritto. Scegliere GitHub Actions invece di
un'altra piattaforma è una decisione che dipende da dove sta il remote, e inventarla sarebbe la
stessa classe di invenzione che la regola vieta.

## TD-31 — Ciclo di vita di `Agent`

**Obiettivo**: unificare la rappresentazione del ciclo di vita, eliminando `agents.active`.

**Autorizzazione umana esplicita** del cambiamento distruttivo, il 2026-09-19, **limitata allo
scope documentato**. È l'hard stop #3 del charter, sciolto da una persona.

**Esito: chiuso.** `V8`, `AgentStatus`, contratto pubblico invariato. Decisioni in
`docs/adr/ADR-012-agent-lifecycle-unification.md`, chiusura in `ARTIFACT.md`.

### Scope, e il suo confine

TD-31 è una divergenza **dentro il database**: `Project` usa un enum chiuso, `Agent` un booleano.
Non è una promessa ai chiamanti — `AgentResponse` pubblica `status: ACTIVE|INACTIVE` **derivato**
dalla TASK-007.

Perciò dentro lo scope: la colonna, l'entità, la query del repository, la migrazione, i test.
**Fuori**: togliere `active` dalla risposta, rinominare `?active=`, rinominare `isActive()`,
aggiungere un indice, aggiungere un terzo stato. Elenco e motivazioni in ADR-012 §7.

### Invarianti

| # | Invariante |
|---|---|
| **I-1** | Il backfill è una **biiezione**: `TRUE`→`ACTIVE`, `FALSE`→`INACTIVE`, entrambe le direzioni verificate su dati seminati |
| **I-2** | `agents.active` **non esiste più**; `agents.status` esiste ed è `NOT NULL` |
| **I-3** | Il database impone l'insieme chiuso, non solo l'enum |
| **I-4** | **Il JSON di `AgentResponse` non cambia**: `active` e `status` entrambi presenti, stessi valori |
| **I-5** | `?active=true|false` continua a filtrare correttamente |
| **I-6** | `V8` si applica al database di sviluppo reale (a `V3`) senza perdere righe |
| **I-7** | Un database **già seminato** sopravvive alla modifica del seed |
| **I-8** | ETag, lock e precondizioni invariati: nessun percorso di scrittura cambia forma |
