# ADR-011 — Vocabolario chiuso di `Task.status`

- **Stato**: **Accettata.** Autonomous Project Mode, `AUTONOMOUS_CHARTER.md` §2.
- **Data**: 2026-09-19
- **Task**: TASK-010
- **Rapporto con le precedenti**: non supera nessuna ADR. **Applica ADR-004 §2** — le due guardie,
  l'insieme chiuso posseduto anche dal database, e il criterio con cui si sceglie quanti stati —
  a una seconda entità, con un'estensione e una divergenza dichiarate (§6, §5). **Non tocca
  ADR-007**: nessun `type` nuovo, e la ragione è §4. **Non introduce** una macchina a stati, che
  è la decisione di §3.

## 0. Che cosa questa ADR decide, e che cosa no

Decide **quali valori `Task.status` può avere** e **chi lo impone**.

Non decide quale valore possa seguire quale, non introduce transizioni, gate di approvazione o un
percorso per mutare lo stato di un task esistente. La distinzione non è una sfumatura di scope:
è la ragione per cui questa ADR può essere scritta adesso, e §3 la argomenta.

## 1. Il censimento viene prima della decisione

`tasks/TASK-009/HANDOFF.md` aveva registrato un avvertimento preciso:

> Una migrazione che stringe `status` a un vocabolario chiuso **non è additiva** se i dati
> esistenti contengono valori fuori dal vocabolario: va deciso cosa farne, e se la risposta è
> «cancellarli» o «riscriverli» è **hard stop #2 o #3** del charter. Guardare i dati prima di
> scrivere l'ADR.

I dati sono stati guardati, e il censimento è un artefatto riproducibile —
`tasks/TASK-010/CENSUS.md`, con i comandi. In sintesi:

| Fonte | Valori |
|---|---|
| Schema `V1`–`V6` | nessun vincolo; `V2`…`V6` non toccano mai `tasks.status` |
| Dev seed | **zero righe di `tasks`** |
| Fixture e test | `OPEN`, in tutte e 26 le costruzioni d'entità, 13 payload e 6 `INSERT` |
| Database locale, schema a **`V3`** | 1 riga, `OPEN`, scritta a mano |
| Storia git, tutti i branch | nessun altro valore è mai esistito |

**Valori esistenti: `{ OPEN }`. Righe da trasformare: zero. Hard stop: nessuno.**

L'avvertimento era giusto e si è rivelato inapplicabile a questo repository. Va detto in questo
ordine: non era allarmismo, era una domanda a cui nessuno aveva risposto.

**E il contratto non promette alcun vocabolario.** Promette, in due punti e per iscritto,
la sua **assenza** — `@NotBlank` + `@Size(max=255)`, il javadoc di `TaskCreateRequest`
(«*free-form strings … left to a later task*») e `docs/audit/CURRENT_FEATURES.md:51`
(«*`"OPEN"`, `"open"`, `"banana"` sono tutti accettati*»). Entrambi dichiarano la cosa come
provvisoria. Questa è la *later task* a cui rinviavano.

*Una conseguenza del censimento che riguarda un altro documento.* L'installazione reale è a `V3`.
`PROJECT_STATE.md` dice «schema a `V6`», che è vero dello **stream Flyway** e falso di **quel
database**. Non cambia nessuna decisione qui — `V7` è additiva sui dati a qualunque versione la si
applichi — ma è un'affermazione del repository su se stesso che era imprecisa, e §9 la corregge.

## 2. Il vocabolario: `OPEN`, `IN_PROGRESS`, `DONE`

Tre valori. Il criterio con cui sono stati scelti è quello che ADR-004 §2 ha già fissato, applicato
alla lettera:

> Allargare l'insieme costa una migrazione e un valore nell'enum; restringerlo dopo che qualcuno ci
> ha scritto sopra costa una migrazione di dati.

L'asimmetria dice di **stringere**. Quindi la domanda non è «quali stati sono plausibili» — sono
molti — ma «qual è il più piccolo insieme che non sia sbagliato».

| Valore | Perché c'è |
|---|---|
| **`OPEN`** | L'unico che esiste. Preservato **verbatim**: stesso spelling, nessun mapping, nessuna riga toccata. Il vocabolario si adatta ai dati, non i dati al vocabolario |
| **`IN_PROGRESS`** | Distingue «assegnato e in lavorazione» da «assegnato e fermo». È la domanda che **TASK-009 ha reso ponibile** rendendo esprimibile l'assegnazione, e a cui oggi non si può rispondere: `agent_id` dice di chi è il task, non se qualcuno ci sta lavorando |
| **`DONE`** | Senza un valore terminale l'insieme non è un ciclo di vita ma un elenco. La sua assenza costringerebbe a una migrazione al primo uso reale, ed è la migrazione che l'asimmetria di sopra dice di evitare |

**Esclusi, e dichiarati tali**: `BLOCKED`, `CANCELLED`, `IN_REVIEW`, `DRAFT`, `PAUSED`. Tutti
plausibili, nessuno richiesto da un requisito — che è testualmente l'argomento con cui ADR-004 §2
escluse `PAUSED`, `COMPLETED` e `DRAFT` dai progetti. Aggiungerne uno è una `V8` più un valore
nell'enum, ed è **intenzionale** che costi così.

### 2.1. L'alternativa seria: un vocabolario di un solo valore

Va registrata perché è la più difendibile delle alternative, e perché il criterio di ADR-004 §2,
applicato meccanicamente, ci arriva.

L'argomento è forte: l'unico valore **richiesto** è `OPEN`. Per i progetti, `ACTIVE` e `ARCHIVED`
erano due perché esistevano due operazioni — `archive` e `restore`. Per i task **non esiste alcuna
operazione su `status`** (§7), quindi il numero di stati che un'operazione esistente richiede è
zero, e l'insieme minimo è `{ OPEN }`.

**Ed è stata scartata, per una ragione precisa.** TD-12 non motiva il vocabolario con se stesso: lo
motiva con le transizioni controllate.

> Il Task Engine del Product Vision richiede transizioni controllate e gate di approvazione:
> impossibili su stringhe arbitrarie.

Una transizione richiede almeno due stati. Un insieme di un solo valore le rende impossibili **per
costruzione** — non rimanderebbe lo scopo di TD-12, lo **contraddirebbe**, chiudendo il debito con
una soluzione che rende irraggiungibile la cosa per cui il debito esiste. Il vocabolario è il
prerequisito della macchina; un prerequisito che esclude ciò che deve abilitare non è un
prerequisito.

Questo è anche il punto in cui ADR-004 §2 **non si applica meccanicamente** e va detto: là il
criterio era «quali operazioni esistono», qui è «quale insieme minimo rende esprimibile ciò che il
debito dichiara di volere abilitare». Due domande diverse, perché le due entità sono in due
momenti diversi della propria vita.

## 3. Un vocabolario **non** è una macchina a stati

La decisione più importante di questa ADR è ciò che non contiene.

`status` acquista un insieme chiuso di valori. **Non acquista alcuna regola su quale valore possa
seguire quale.** Qualunque valore ammesso è scrivibile alla creazione, `DONE` compreso: un task
può nascere già finito, il che serve a registrare lavoro già svolto, e rifiutarlo sarebbe la regola
«il lavoro comincia aperto» — una transizione, non un vocabolario.

*Perché la separazione è reale e non formale.* Un insieme chiuso è una proprietà di **un valore**:
si verifica guardando una riga sola, e il database può imporlo con un `CHECK`. Una transizione è
una proprietà di **due stati nel tempo**: richiede di conoscere quello precedente, non è esprimibile
in un `CHECK`, e soprattutto richiede di rispondere a domande che nessun requisito qui pone — chi
può approvare, cosa succede a un task riaperto, se `DONE` è terminale. Rispondere per inerzia,
mentre si fa un'altra cosa, è esattamente il modo in cui ADR-004 §1 rifiutò di collegare `Task` a
`Project`: «*il costo di rimandare è una migrazione in più; il costo di anticipare è una decisione
di dominio presa per inerzia*».

*Conseguenza registrata, non nascosta.* Il ciclo di vita resta **non percorribile**: non c'è modo
di mutare lo `status` di un task esistente (§7). È **TD-37**, ed è il contenuto della task che
introdurrà le transizioni, dove saranno la domanda centrale invece di un effetto collaterale.

## 4. Un valore fuori vocabolario è un errore di **validazione**, non di body illeggibile

La scelta ovvia qui è quella sbagliata, e va scritta perché il prossimo che tocca questo codice
sarà tentato di «semplificare».

**Scartato: rendere `TaskCreateRequest.status` di tipo `TaskStatus`.** Sembra la cosa pulita — il
tipo al bordo, la conversione gratis — e produce questo: Jackson fallisce la deserializzazione,
Spring solleva `HttpMessageNotReadableException`, `ApiExceptionHandler` risponde
`urn:ai-company-os:problem:malformed-request` con `detail` «*The request body could not be read*».

Due cose non vanno, e la prima è grave:

1. **È falso.** Il body è stato letto perfettamente. Il `detail` afferma un fatto che non è
   accaduto, e ADR-007 §2 ha fatto del `type` la parte machine-readable del contratto proprio
   perché un client ci si basa: un client che riceve `malformed-request` conclude che il suo
   serializzatore è rotto, e va a cercare un difetto che non esiste.
2. **Il campo colpevole sparisce.** Quel ramo non popola `errors`, mentre oggi uno `status`
   **vuoto** produce `validation-failed` con `errors: {"status": "status is required"}`. Lo stesso
   campo, per due difetti vicini, darebbe due forme d'errore diverse e quella peggiore al difetto
   più probabile.

**Deciso**: il record mantiene `String status` e acquista un vincolo Jakarta. Un valore fuori
vocabolario dà `400 urn:ai-company-os:problem:validation-failed`, con `errors.status` che **nomina
i valori ammessi** — perché un chiamante a cui si dice solo «sbagliato» deve indovinare fra tre.

**Nessun `ApiProblem` nuovo. `ApiExceptionHandler` invariato. ADR-007 invariato.** Il contratto di
errore di TASK-005 non è stato preservato per fortuna: è stato preservato scegliendo la forma
invece di subirla.

## 5. Il confronto è case-sensitive

`"open"` è fuori vocabolario quanto `"banana"`, e l'audit li elencava entrambi come accettati oggi.

*Perché.* Accettare entrambe le grafie obbligherebbe a sceglierne una da memorizzare, cioè a
introdurre una **regola di normalizzazione** — un'altra decisione non richiesta da nessun
requisito, e con conseguenze proprie (cosa torna al client? quella che ha mandato o quella
memorizzata?). Senza normalizzazione, accettare entrambe significa due nomi per uno stato, e
`status = 'OPEN'` che non trova le righe `'open'`.

*Perché è la scelta **opposta** a quella degli agenti, e non è un'incoerenza.* ADR-008 rese
l'unicità dei nomi case-insensitive (`lower(name)`), e il criterio che distingue i due casi è
questo: **un nome lo digita una persona, uno stato lo manda un programma.** Su un nome la
differenza di grafia è quasi sempre un errore di battitura da perdonare; su un valore d'enum è un
client che ha sbagliato la costante, e perdonarlo nasconde un difetto invece di risolverlo.

## 6. Tre guardie, non due

ADR-004 §2 ne stabilì due — enum e check constraint — con questo argomento:

> L'enum protegge il percorso applicativo; il check constraint protegge tutto il resto — import,
> script di manutenzione, correzioni fatte a mano in `psql`.

Qui ce ne vogliono tre, perché fra il client e il dominio c'è un livello che i progetti non avevano
quando ADR-004 fu scritta: il record di richiesta.

| # | Guardia | Che cosa impedisce |
|---|---|---|
| 1 | Vincolo Jakarta su `TaskCreateRequest.status` | Un client fuori vocabolario riceve `400` con il campo nominato, invece di un `500` dal database |
| 2 | `TaskStatus` + `@Enumerated(EnumType.STRING)` su `Task` | Il percorso applicativo non può scrivere altro. `STRING` e non `ORDINAL`: un ordinale rende il dato illeggibile fuori dall'applicazione e riordinare l'enum riscriverebbe il significato di ogni riga senza toccarne una |
| 3 | `V7 tasks_status_check` | Tutto ciò che non passa dall'applicazione. È il gemello di `projects_status_check`, che `V2` diede ai progetti il giorno in cui la tabella nacque, e che `tasks` non ha mai ricevuto |

**Le tre devono dichiarare lo stesso insieme**, e questo è verificato da un test e non da
disciplina: un valore che il validatore rifiuta e il database accetta è una riga che solo uno
script può creare; uno che il validatore accetta e il database rifiuta è un `500`.

Togliendone una qualsiasi, qualcosa diventa rosso. È stato verificato per mutazione, non sostenuto
(`tasks/TASK-010/ARTIFACT.md` §5).

## 7. Quello che questa ADR lascia aperto, dichiarato

| # | Limite | Debito |
|---|---|---|
| 1 | **Non esiste un percorso per mutare lo `status` di un task esistente.** Nessun `PUT`, nessun setter. Un task creato `OPEN` resta `OPEN` per sempre. Il vocabolario è chiuso, il ciclo di vita non è percorribile | **TD-37** |
| 2 | **`Task.priority` resta una stringa libera**, senza enum e senza vincolo DB. TD-12 copre `status` **e** `priority`: questa ADR ne chiude una metà, e lo dice invece di lasciar credere che TD-12 sia chiuso | **TD-36** |
| 3 | Nessun filtro `?status=` su `GET /api/tasks`, mentre `GET /api/projects?status=` esiste. Nessun client lo pone — stesso criterio con cui TD-34 è stato lasciato aperto | fra le domande di contratto aperte |
| 4 | La colonna resta `VARCHAR(255)`. Restringerla non è metadata-only su una tabella popolata e non aggiunge nulla al `CHECK` | non è debito: è una non-azione motivata |

## 8. Rotture di contratto, dichiarate

| Cosa cambia | Prima | Dopo |
|---|---|---|
| `POST /api/tasks` con `status` fuori vocabolario | `201` | **`400`** `validation-failed`, `errors.status` |
| `POST /api/tasks` con `"open"` (grafia diversa) | `201` | **`400`**, per §5 |
| `INSERT`/`UPDATE` SQL con `status` fuori vocabolario | riuscito | **rifiutato dal database** |
| Migrazione `V7` su un database con valori fuori vocabolario | — | **fallisce** |

La prima è una rottura reale e voluta: un contratto che documentava «`banana` è accettato» non si
può stringere senza rompere qualcuno. Il censimento dice che qui non c'è nessuno da rompere, e
un contratto che promette l'assenza di regole non può essere mantenuto e chiuso insieme.

**Sull'ultima riga.** È il comportamento **corretto**, ed è il precedente esatto di `V4` con
`agents_name_unique_idx`, che dichiarò lo stesso rischio con le stesse parole:

> *a migration that stops is the conversation that makes them.*

L'alternativa sarebbe riscrivere in silenzio i valori che non conosce, cioè inventare un mapping —
che è precisamente ciò che il briefing di questa task vietava e che il censimento ha reso
inutile. Chi si trovasse in quella posizione deve fare quella scelta di persona.

## 9. Conseguenze

- `V7` è additiva **sui dati**: zero righe lette, scritte o riscritte. Non è additiva sul
  **contratto**, ed è §8 a dirlo.
- Aggiungere uno stato **non** è una modifica solo applicativa: serve una migrazione che riscriva
  il constraint. Come per i progetti, è intenzionale.
- `MigrationStreamTest` continua a passare **senza modifiche**, e non per caso: scrive `'OPEN'` in
  `tasks` a ogni versione dello stream prima di migrare alla successiva. `OPEN` è nel vocabolario,
  quindi quel test è già l'upgrade test della coppia `V6 → V7`, scritto da TASK-006 per una
  migrazione che allora non esisteva. Un vocabolario che avesse escluso `OPEN` lo avrebbe reso
  rosso a ogni coppia.
- `PROJECT_STATE.md` acquista una precisazione: lo schema **dello stream** è a `V7`; quello di
  un'installazione è quello che le sue migrazioni dicono, e quella di sviluppo è a `V3`.
- TD-12 passa a **parzialmente chiuso**, con **TD-36** e **TD-37** aperti e nominati.
