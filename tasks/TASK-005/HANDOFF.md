# TASK-005 — Handoff

## Stato
Completata. Suite **136 verdi**, schema `V3`, integrata in `autonomous/phase-1-foundations`.

## Le due cose da sapere

1. **Il `type` è il contratto.** `urn:ai-company-os:problem:<slug>`, enumerato in `ApiProblem`.
   Un `title` si può riscrivere; uno slug no, senza dichiararlo. Aggiungere un problema è un
   gesto in un posto solo, e un test asserisce l'insieme esatto.

2. **Un'eccezione di dominio nuova senza mappatura fa fallire i test.**
   `ApiProblemCoverageTest` scansiona i package di eccezioni. Se ne aggiungi una, aggiungi anche
   il suo `ApiProblem` e il suo handler — altrimenti finirebbe nel catch-all e il chiamante
   riceverebbe «internal error» per una regola di dominio.

## Debito che il prossimo lavoro incontrerà

| ID | Perché conta |
|---|---|
| **TD-28 / TD-30** | Gemelli: manca la **rilevazione** dell'intento stantio, su `Project` e su `Task`. Si chiudono insieme con `ETag`/`If-Match` |
| **TD-22** | Nessun test di upgrade incrementale `V1` → `V2`. Il modello esiste in `MigrationStreamTest` |
| **TD-14** | Nessuna CI. Con 136 test e invarianti di concorrenza, il costo di non averla cresce |
| **TD-04, TD-11** | Sicurezza e CORS: nessuna autenticazione, `@CrossOrigin` senza origine |

## Domande di contratto ancora aperte
Invariate rispetto a TASK-004: scopribilità dello stato congelato, nessun `DELETE`
dell'associazione, nessun filtro «task senza progetto», `archive` non idempotente.
