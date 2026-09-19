# TASK-010 — Chiusura

**Vocabolario chiuso di `Task.status`.** Chiude **la metà `status` di TD-12**, apre **TD-36** e
**TD-37**. Suite **201 → 216**, schema **`V6` → `V7`**. Nessun failure aperto.

## 1. Che cosa è cambiato, in una frase

Da oggi un task sa **in che stato è** in un modo che qualcosa può verificare: `status` non è più
una stringa che accetta `"banana"`, ed è imposto in tre punti che dicono la stessa cosa.

## 2. Il censimento ha cambiato la natura della task

Era la parte a rischio. `tasks/TASK-009/HANDOFF.md` avvertiva che stringere `status` non è
additivo se i dati contengono valori fuori vocabolario, e che in quel caso la domanda tocca gli
hard stop #2 e #3. **Contare prima è ciò che ha reso la task eseguibile in autonomia**, e il
conteggio sta in `CENSUS.md` con i comandi per rifarlo.

Risultato: cinque fonti, **un solo valore** (`OPEN`), **zero righe da trasformare**, nessun hard
stop. Niente strategia staged, niente mapping, nessun debito da irreversibilità.

**Tre cose che il censimento ha trovato e che nessun documento diceva:**

1. **Il database di sviluppo reale è a `V3`, non a `V6`.** `PROJECT_STATE.md` dichiarava «schema a
   `V6`», vero dello *stream Flyway* e falso di **quell'installazione**, che non ha mai visto `V4`,
   `V5` né `V6`. Corretto in §9 di questa chiusura.
2. **`Task.status` non ha alcun percorso di mutazione.** Un solo scrittore — la creazione — e
   nessun ramo del codice legge il valore per decidere qualcosa. Questo ha cambiato lo scope: la
   task chiude il vocabolario, non il ciclo di vita, e **lo dichiara** (TD-37) invece di lasciar
   credere il contrario.
3. **`MigrationStreamTest` era già l'upgrade test di `V6 → V7`.** Scrive `'OPEN'` in `tasks` a ogni
   versione dello stream prima di migrare alla successiva — scritto da TASK-006 per migrazioni che
   allora non esistevano. È un vincolo reale sulla scelta: **un vocabolario che avesse escluso
   `OPEN` lo avrebbe reso rosso a ogni coppia.**

## 3. Le decisioni, e quella che verrà rifatta male

| # | Decisione | Contenuto |
|---|---|---|
| **1** | **Il vocabolario è `OPEN`, `IN_PROGRESS`, `DONE`** | `OPEN` preservato verbatim. Esclusi e dichiarati: `BLOCKED`, `CANCELLED`, `IN_REVIEW`, `DRAFT`, `PAUSED` — plausibili, nessuno richiesto, ed è testualmente l'argomento di ADR-004 §2 |
| **2** | **Un vocabolario non è una macchina a stati** | Nessuna regola di transizione. `DONE` è un valore legale alla creazione. Un test lo pinna, così una task futura non può aggiungere «il lavoro comincia aperto» mentre crede di fare pulizia |
| **3** | **Il campo della request resta `String`** | ⚠️ È la decisione che sembra sbagliata e non lo è. Vedi sotto |
| **4** | **Case-sensitive** | `"open"` è fuori quanto `"banana"`. Scelta **opposta** a quella di ADR-008 sui nomi degli agenti, e il criterio che le distingue è: un nome lo digita una persona, uno stato lo manda un programma |
| **5** | **Tre guardie, non due** | ADR-004 §2 ne voleva due; fra client e dominio c'è un livello che i progetti non avevano quando fu scritta |

**Sulla 3, perché verrà «semplificata».** Tipizzare il campo come `TaskStatus` sembra più pulito e
produce questo: Jackson fallisce, e la risposta diventa `malformed-request` — «*The request body
could not be read*» — che è **falso**, su un `type` che ADR-007 §2 ha reso la parte su cui i client
si ramificano. In più quel ramo non popola `errors`, quindi il campo colpevole sparisce, mentre
uno `status` **vuoto** produce già `errors.status`. Stesso campo, due forme, e la peggiore
all'errore più probabile.

**Nessun `ApiProblem` nuovo, `ApiExceptionHandler` invariato.** Il contratto di TASK-005 non è
sopravvissuto per fortuna: è sopravvissuto perché la forma è stata scelta.

## 4. Rotture dichiarate

| Cosa | Prima | Dopo |
|---|---|---|
| `POST /api/tasks` con `status` fuori vocabolario | `201` | **`400`** `validation-failed`, `errors.status` |
| `POST /api/tasks` con `"open"` | `201` | **`400`** |
| `INSERT`/`UPDATE` SQL fuori vocabolario | riuscito | **rifiutato dal database** |
| `V7` su un database con valori fuori vocabolario | — | **fallisce**, e lascia la riga intatta |

La prima è reale e voluta: un contratto che documentava «`banana` è accettato» non si stringe
senza rompere qualcuno. Il censimento dice che qui non c'è nessuno da rompere.

**JSON invariato.** `TaskResponse.status` è l'enum, e Jackson lo scrive col nome: gli stessi byte
che i client ricevevano.

## 5. Verifica per mutazione — quattro, tutte rosse, e due hanno insegnato qualcosa

Protocollo seguito alla lettera, dopo la lezione di TASK-009: **albero verificato pulito prima di
ogni mutazione e dopo ogni revert**, e mai interpretato un failure senza averlo fatto.

| # | Mutazione | Esito |
|---|---|---|
| **M1** | Tolto `@InTaskStatusVocabulary` dal record | **3 rossi**, e con `500` invece di `400` |
| **M2** | `V7` non crea più il vincolo | **12 rossi** su tre classi |
| **M3** | Enum allargato con `BLOCKED`, migrazione invariata | **2 rossi** — e ha trovato un difetto, §6 |
| **M4** | `@Enumerated(ORDINAL)` invece di `STRING` | **8 errori**: il contesto Spring **non parte** |

**M1 ha confermato una dipendenza dichiarata invece di assunta.** Il javadoc di `statusValue()`
afferma che la conversione è sicura *perché il vincolo ha già girato*, e che togliendolo si
ottiene un `500`. È esattamente ciò che succede: `400` atteso, `500` ottenuto. La catena non è
un'argomentazione, è una cosa che è stata vista.

**M2 ha riprodotto empiricamente l'argomento di ADR-004 §2**, che finora era una previsione:

> Senza il constraint basta una `UPDATE` per mettere nel database uno stato che il dominio non sa
> interpretare, e Hibernate fallirebbe **alla lettura, non alla scrittura, cioè lontano dalla
> causa**.

Senza il vincolo, il `'open'` scritto in SQL grezzo dal test è entrato in tabella e ha poi fatto
fallire **la lettura di ogni task** con `No enum constant ... TaskStatus.open` — dentro un
`@BeforeEach`, cioè nel posto più lontano possibile dalla causa. La frase scritta nel 2026 per i
progetti è stata verificata nel 2026 sui task.

**M4 si è rivelato più forte di quanto il javadoc dichiarasse.** Era scritto che `ORDINAL`
renderebbe il dato illeggibile e riordinare l'enum riscriverebbe il significato delle righe. Vero,
ma non è così che fallisce: fallisce **all'avvio**, perché Hibernate in `validate` rifiuta un
mapping ordinale su una colonna `varchar`. Il difetto non arriva mai in produzione.

## 6. Adversarial review — due rilievi, entrambi corretti

**MEDIUM-1 — un test il cui nome prometteva più di quanto verificasse.** `M3` ha allargato l'enum
e `theCheckConstraintDeclaresTheSameSetAsTheEnum` è **rimasto verde**: non confrontava il vincolo
con l'enum, ma con la costante `VOCABULARY` scritta a mano.

La garanzia complessiva regge — enum `=` `VOCABULARY` e database `=` `VOCABULARY` implicano
enum `=` database, per transitività, e i due test stanno nella stessa classe — ma **il nome
affermava una cosa diversa da quella asserita**, ed è la definizione di MEDIUM in
`AUTONOMOUS_LOOP.md` §2.

Corretto il **nome**, non il disegno: confrontare ogni guardia con un insieme scritto
**indipendentemente** è più forte che confrontarle fra loro, perché il confronto diretto passerebbe
il giorno in cui qualcuno cambia entrambe e non decide nessuna delle due. La motivazione è adesso
nel javadoc.

**MEDIUM-2 — una motivazione senza test.** Il javadoc di `InTaskStatusVocabulary` spiegava a lungo
perché blank passa il controllo di vocabolario e resta affare di `@NotBlank` — e **niente lo
asseriva**. Entrambi i percorsi rispondono `400 validation-failed`, quindi il codice di stato non
distingue quale vincolo ha sparato. Aggiunto un test che asserisce il **messaggio**.

**LOW registrati, non fatti:**

- **L-1** — `@Size(max=255)` tolto da `status` perché subsunto dal vocabolario. Cambia il messaggio
  per una stringa di 300 caratteri, non il codice né il `type`.
- **L-2** — 25 call site di test aggiornati meccanicamente. Diff ampio, contenuto nullo.
- **L-3** — `docs/audit/CURRENT_FEATURES.md:51` («`banana` è accettato») descrive uno stato che
  non è più vero. È un documento di audit datato TASK-000, e `PROJECT_STATE.md` registra già le
  correzioni a `docs/audit/*` come debito documentale aperto. Non allargato qui.

## 7. Gli invarianti, e dove sono resi veri

| # | Invariante | Test |
|---|---|---|
| I-1 | Fuori vocabolario → `400` `validation-failed`, `errors.status`, nessuna riga | `aStatusOutsideTheVocabularyIsRejectedAsAValidationFailure`, `theRefusalNamesTheValuesThatWouldHaveWorked` |
| I-2 | Case-sensitive, e le guardie concordano | `theVocabularyIsCaseSensitive`, `theDatabaseIsCaseSensitiveTooAndAgreesWithTheValidator`, `membershipIsExactAndCaseSensitive` |
| I-3 | I tre valori tornano identici | `everyValueOfTheVocabularyIsAcceptedAndComesBackUnchanged` |
| I-4 | Il **database** rifiuta, su `INSERT` e su `UPDATE` | `theDatabaseRefusesAnInsertOutsideTheVocabulary`, `theDatabaseRefusesAnUpdateOutsideTheVocabulary` |
| I-5 | `V7` su database popolato non perde né altera righe | `taskStatusVocabularyIsAppliedToAPopulatedV6Database`, più `everyConsecutiveUpgradePreservesWhatWasAlreadyThere` |
| I-6 | `V7` fallisce sui valori fuori vocabolario, e non distrugge | `taskStatusVocabularyStopsOnADatabaseThatHoldsAValueOutsideIt` |
| I-7 | L'insieme è pinnato | `theEnumIsExactlyTheDecidedVocabulary` |
| I-8 | Le guardie dichiarano lo stesso insieme | `theCheckConstraintDeclaresTheDecidedVocabulary` (+ I-7, per transitività) |
| I-9 | Nessuna transizione | `anyValueOfTheVocabularyMayBeTheFirstOne` |
| I-10 | Contratto d'errore invariato | `ApiProblemCoverageTest`, verde **senza modifiche** |
| I-11 | Nessun write path nuovo | `PreconditionCoverageTest`, verde **senza essere modificato** — non compare nel diff |

I-10 e I-11 sono gli unici due che si dimostrano **con l'assenza di una modifica**, ed è per
questo che sono stati verificati guardando il diff e non ricordando l'intenzione.

## 8. Debito

| ID | Stato | Contenuto |
|---|---|---|
| **TD-12** | **PARZIALMENTE CHIUSO** | La metà `status` è chiusa. La metà `priority` no |
| **TD-36** | *(nuovo)* | `Task.priority` resta una stringa libera senza vincolo DB. Stesso difetto, stessa forma di soluzione. Non combinato di proposito: è una normalizzazione adiacente, non la stessa |
| **TD-37** | *(nuovo)* | Nessun percorso muta lo `status` di un task esistente: creato `OPEN`, resta `OPEN`. Il vocabolario è chiuso, il ciclo di vita **non è percorribile**. Si chiude con la task delle transizioni, dove le regole sono la domanda centrale |

**TD-37 è il candidato naturale per TASK-011**, e va detto che è anche la task che ADR-011 §3 ha
deliberatamente evitato: introdurre il `PUT` *è* il momento in cui le transizioni diventano una
domanda obbligatoria, ed è per questo che non è stato introdotto qui di sfuggita.

## 9. Correzione a `PROJECT_STATE.md`

«Schema: **`V6`**» è vero dello stream Flyway e non di un'installazione. Il database di sviluppo è
a `V3`. Lo stato adesso distingue le due cose.

Nessun effetto sulla migrazione: `V7` è additiva sui dati a qualunque versione la si applichi, e
quando quel database sarà migrato `V4`…`V7` si applicheranno in ordine — `V7` compresa, perché la
sua unica riga è `OPEN`. È una previsione, e l'evidenza che la sostiene è il censimento più
`taskStatusVocabularyIsAppliedToAPopulatedV6Database`.

## 10. Suite

**216/216 verdi.** `./mvnw -B clean test` → `BUILD SUCCESS`. Schema `V7`. Nessun remote, nessun
push, `master` fermo a `d5ff121`.
