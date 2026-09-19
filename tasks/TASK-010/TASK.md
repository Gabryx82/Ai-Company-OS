# TASK-010 — Vocabolario chiuso di `Task.status`

**Livello 5** di `AUTONOMOUS_LOOP.md` §4. Chiude **la metà `status` di TD-12**.
Auto-approvata in Autonomous Project Mode il 2026-09-19.

## 1. Il problema, come il repository stesso lo afferma

`docs/audit/CURRENT_FEATURES.md:51`:

> Stringhe libere: `"OPEN"`, `"open"`, `"banana"` sono tutti accettati. Nessun enum, nessuna
> macchina a stati, nessun vincolo DB.

Un task oggi **sa dove sta** (TASK-003) e **di chi è** (TASK-009). Quello che non sa è **in che
stato è**, in un modo che qualcosa possa verificare. `status` è una stringa che il database non
vincola, che nessun ramo del codice legge per decidere, e il cui unico scrittore è la creazione.

Non è un difetto astratto: è ciò che rende impossibile tutto il livello sopra. TD-12 lo dice
nella propria formulazione — «*Il Task Engine del Product Vision richiede transizioni controllate
e gate di approvazione: impossibili su stringhe arbitrarie*» — e con esso TD-08, la sostituzione
di `MasterOrchestrator`, che TASK-009 ha sbloccato ma non reso eseguibile.

## 2. Che cosa dice il censimento, e perché viene prima

`tasks/TASK-010/CENSUS.md`, eseguito **prima** di ogni decisione, perché
`tasks/TASK-009/HANDOFF.md` avvertiva che stringere `status` non è additivo se i dati contengono
valori fuori vocabolario — e in quel caso la domanda «cosa farne» è hard stop #2 o #3.

Il risultato è netto e rende l'avvertimento inapplicabile a questo repository:

| Fonte | Valori |
|---|---|
| Schema `V1`–`V6` | nessun vincolo; nessuna migrazione tocca `tasks.status` dopo `V1` |
| Dev seed | **zero righe di `tasks`** |
| Fixture e test (26 costruttori, 14 payload, 6 `INSERT`) | `OPEN`, e nient'altro |
| Database locale (`aicompany_postgres_data`, schema a **`V3`**) | 1 riga, `OPEN` |
| Storia git, tutti i branch | nessun altro valore è mai esistito |

**Valori esistenti: `{ OPEN }`. Righe da trasformare: zero. Hard stop: nessuno.**

E la seconda metà della domanda: **il contratto non promette alcun vocabolario.** Promette
esplicitamente la sua assenza (`@NotBlank` + `@Size(max=255)`, più un javadoc che dice
«free-form strings … left to a later task»). Questa è quella task.

## 3. Che cosa si fa

**Un vocabolario chiuso. Non una macchina a stati.** La distinzione è la decisione centrale e
non è una sfumatura: un vocabolario dice *quali valori esistono*, una macchina dice *quale valore
può seguire quale*. La seconda non è in scope e non viene introdotta nemmeno per metà.

1. **`TaskStatus`** — enum chiuso: `OPEN`, `IN_PROGRESS`, `DONE`. Motivazione e valori esclusi in
   ADR-011 §2.
2. **`V7`** — `tasks_status_check CHECK (status IN (...))`, il gemello di `projects_status_check`
   che `V2` diede ai progetti.
3. **`Task.status`** diventa `TaskStatus` con `@Enumerated(EnumType.STRING)`.
4. **Validazione al bordo** che produce un errore di *validazione*, non di *body illeggibile* —
   vedi §5.

## 4. Che cosa **non** si fa, e perché

| Escluso | Perché |
|---|---|
| **Transizioni, gate, macchina a stati** | Rule 1 del briefing, ed è anche la scelta giusta: nessun requisito le specifica. Un vocabolario è il prerequisito della macchina, non una sua versione debole |
| **`PUT /api/tasks/{id}/status`** | Un percorso di mutazione **è** l'occasione in cui le transizioni diventano una domanda obbligatoria. Introdurlo qui significherebbe rispondere per inerzia. Registrato: **TD-37** |
| **`Task.priority`** | TD-12 copre `status` **e** `priority`. Il briefing dice `status`, e «non combinare altre normalizzazioni solo perché vicine». TD-12 resta **parzialmente aperto**: **TD-36** |
| **Riscrivere o rimappare valori legacy** | Rule 2. Non ce ne sono: `OPEN` entra nel vocabolario così com'è, con lo stesso spelling, e nessuna riga viene toccata |
| **Restringere `VARCHAR(255)`** | Una `ALTER TYPE` su colonna popolata non è metadata-only e non guadagna nulla che il `CHECK` non dia già |
| **Filtro `?status=` su `GET /api/tasks`** | Nessun client lo pone. Lo stesso criterio con cui TD-34 è stato lasciato aperto |

## 5. La decisione di contratto che va presa, non ereditata

Con `status` chiuso, `{"status":"banana"}` deve produrre un errore. **Quale**, non è indifferente,
e la scelta ovvia è quella sbagliata.

Se il campo del record diventasse di tipo `TaskStatus`, Jackson fallirebbe la
deserializzazione → `HttpMessageNotReadableException` → `MALFORMED_REQUEST`, il cui `detail` è
«*The request body could not be read*». **È falso**: il body è stato letto benissimo. E il campo
colpevole non comparirebbe da nessuna parte, perché quel ramo non popola `errors`.

Oggi uno `status` vuoto dà `VALIDATION_FAILED` con `errors: {"status": "..."}`. Uno `status`
fuori vocabolario deve restare **nella stessa famiglia**: stesso `type`, campo nominato, elenco
dei valori ammessi nel messaggio.

Perciò il record mantiene `String status` e acquista un vincolo Jakarta. **Nessun `ApiProblem`
nuovo, nessuna modifica a `ApiExceptionHandler`, ADR-007 invariato** — che è la regola 6 del
briefing, rispettata scegliendo la forma, non subendola.

## 6. Invarianti

Un invariante è una frase che può essere falsa.

| # | Invariante |
|---|---|
| **I-1** | `POST /api/tasks` con uno `status` fuori vocabolario → `400`, `type` `validation-failed`, `errors.status` presente, **nessuna riga creata** |
| **I-2** | Il confronto è **case-sensitive**: `"open"` è fuori vocabolario quanto `"banana"`. Il database distingue `OPEN` da `open`, e un vocabolario che accettasse entrambi avrebbe due nomi per uno stato |
| **I-3** | `POST /api/tasks` con ciascuno dei tre valori ammessi → `201`, e il valore torna **identico** in `GET /api/tasks/{id}` |
| **I-4** | Un `INSERT`/`UPDATE` SQL diretto con uno `status` fuori vocabolario è **rifiutato dal database**, non solo dall'applicazione |
| **I-5** | `V7` è applicabile a un database che contiene già righe, e **non ne perde, altera o aggiunge nessuna** |
| **I-6** | Su un database che contiene uno `status` fuori vocabolario, `V7` **fallisce**. È il comportamento voluto, non un difetto |
| **I-7** | L'insieme dei valori è **pinnato da un test**: aggiungerne o toglierne uno senza decidere rende rosso qualcosa |
| **I-8** | L'enum e il `CHECK` dichiarano lo **stesso** insieme. Due guardie che divergono sono peggio di una |
| **I-9** | Nessuna regola di transizione esiste: qualunque valore ammesso è scrivibile alla creazione, senza ordine |
| **I-10** | Il contratto di errore di TASK-005 è invariato: nessun `type` nuovo, `ApiProblemCoverageTest` verde senza modifiche |
| **I-11** | Nessun percorso di scrittura nuovo, quindi `PreconditionCoverageTest` resta verde **senza essere modificato** — e questo va verificato, non assunto |

## 7. Criterio di chiusura

Tutte e cinque:

1. gli invarianti I-1…I-11 sono resi veri da test, e i test di I-1, I-2 e I-4 sono stati **visti
   rossi** sulla baseline;
2. `V7` è additiva sui dati e verificata da un upgrade test su database popolato;
3. verifica per mutazione su ciò che è portante: togliere il vincolo Jakarta, togliere il `CHECK`,
   allargare l'enum;
4. suite completa verde, e `MigrationStreamTest` verde **senza modifiche**, perché scrive `OPEN`
   a ogni versione;
5. `PROJECT_STATE.md` aggiornato, debito registrato (**TD-36**, **TD-37**), merge fast-forward in
   `autonomous/phase-2-assignment`. `master` fermo a `d5ff121`.
