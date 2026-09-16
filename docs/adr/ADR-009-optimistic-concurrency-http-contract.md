# ADR-009 — Concorrenza ottimistica nel contratto HTTP: `ETag` / `If-Match`

- **Stato**: **Accettata.** Scope deciso in Autonomous Project Mode, **forma fissata da una precisazione umana del 2026-09-16** (§0).
- **Data**: 2026-09-16
- **Task**: TASK-008
- **Rapporto con le precedenti**: non supera nessuna ADR. **Completa ADR-006 §8**, che indicava
  il meccanismo senza sceglierlo, e chiude **TD-28** e **TD-30**. Estende il contratto di errore
  di ADR-007 con tre `type` nuovi, senza toccarne le regole. Tocca le rotte di ADR-004, ADR-005
  e ADR-008 in un punto solo: la precondizione.

## 0. Che cosa ha deciso chi

Questa ADR mescola due sorgenti di decisione, e tenerle distinte è parte della tracciabilità
che il charter chiede.

| Origine | Contenuto |
|---|---|
| **Precisazione umana, 2026-09-16** | La distinzione dei due meccanismi (§1); il fatto che `@Version` da sola non basti e **perché** (§2); il flusso obbligatorio per ogni mutazione di un task (§3); `If-Match` obbligatorio sui write di un task esistente; `412` per ETag stantio; `428` per `If-Match` assente; la precondizione **prima** del no-op idempotente; un percorso canonico per ottenere l'ETag corrente, con preferenza per `GET /api/tasks/{id}`; `@Version` come contatore persistente con migrazione additiva; l'ereditarietà del protocollo su ogni write path futuro del task, TASK-009 incluso |
| **Deciso qui, in autonomia** | L'estensione a `Project` e `Agent` e la sua motivazione (§4); la forma dell'entity-tag e il rifiuto di `If-Match: *` (§5); i tre `ApiProblem` e l'ordine di valutazione (§6); dove vive il confronto nel codice (§7); il debito nuovo (§9) |

Dove le due sorgenti si sovrappongono, vince la precisazione umana. Non è successo: le sette
richieste sono tutte recepite senza riformularle.

## 1. Il problema, e i due meccanismi che non vanno confusi

ADR-006 ha costruito un protocollo di lock che rende vere le regole di dominio. Fa una cosa
sola, e la fa interamente: **serializza**. Non **rileva**.

> «I lock serializzano ma non rilevano: il secondo scrittore sovrascrive con un corpo composto
> senza conoscere il primo.» — TD-28
>
> «Manca la rilevazione dell'intento stantio verso il chiamante.» — TD-30

I due meccanismi rispondono a due domande diverse e **nessuno dei due sostituisce l'altro**:

| | **L0 / L1 / L2 — protocollo di lock (ADR-006 §4)** | **`ETag` / `If-Match` — questa ADR** |
|---|---|---|
| Domanda | *La regola è stata applicata a dati veri?* | *Il chiamante sapeva su cosa stava scrivendo?* |
| Ambito | **Consistenza interna** fra righe del database | **Intento del client**, nel contratto HTTP |
| Vittima del difetto | L'invariante di dominio | Il primo chiamante, che non sa di essere stato sostituito |
| Meccanismo | `SELECT … FOR UPDATE` / `FOR SHARE`, tenuto fino al commit | Confronto fra ciò che il client ha letto e ciò che c'è adesso |
| Se manca | Una regola viene valutata su uno stato che non esiste più | Il lost update resta invisibile: due `200`, una scrittura persa |

**Sono complementari, e ciascuno rende sano l'altro.**

- Il **lock rende atomico il confronto**. Senza L0, due richieste che portano lo *stesso* ETag
  valido passerebbero entrambe la precondizione e poi correrebbero: è il classico
  check-then-act, e la precondizione da sola lo apre invece di chiuderlo.
- Il **confronto rende visibile la staleness**. Senza `If-Match`, L0 serializza due
  riassegnazioni e le fa decidere entrambe su stato fresco — che è esattamente ADR-006 §8 — ma
  nessuno dice al primo chiamante che la sua scrittura è stata sostituita.

## 2. Perché `@Version` da sola non basta, e perché non è in contraddizione con ADR-006 §4

Due affermazioni distinte che è facile far collassare in una.

### 2.1 ADR-006 §4 scartò `@Version` **al posto del protocollo di lock**, non come rilevatore

La tabella di ADR-006 §4 confronta `@Version` su `Project` con il protocollo di lock come
**soluzioni alternative di TD-25**, e conclude — correttamente — che l'ottimistico non chiude
TD-25, perché l'assegnazione di un task non scrive la riga del progetto e quindi non c'è nessuna
versione da confrontare. Quella conclusione **resta valida e non viene toccata qui**: il
protocollo di lock non si sostituisce, non si allenta, non si riduce.

La stessa ADR, §8, dice che TD-28 e TD-30 «si chiudono allo stesso modo — concorrenza ottimistica
**nel contratto HTTP**, `ETag`/`If-Match` o un numero di versione esposto — e nessuno dei due si
chiude con un lock». Questa ADR fa quello, e niente di più.

Un corollario che ADR-006 §4 rende obbligatorio e che qui si eredita:
**`OPTIMISTIC_FORCE_INCREMENT` resta vietato.** Assegnare un task a un progetto non incrementa la
versione del progetto. Due assegnazioni allo stesso progetto attivo non sono in conflitto, e farle
diventare un `412` sarebbe inventare un conflitto che il dominio non ha. Diventa la regola **P3**
(§3).

### 2.2 Il controllo automatico di `@Version` non scatta mai sui nostri percorsi di scrittura

Questo è il punto tecnico decisivo, e senza di esso l'implementazione «ovvia» sarebbe un falso
verde.

JPA solleva `OptimisticLockException` quando la versione **caricata nel persistence context**
diverge da quella presente nel database al momento del flush. Sui nostri write path l'entità è
caricata con `PESSIMISTIC_WRITE` (L0 per il task, L1 per progetto e agente). Sotto
`READ COMMITTED`, una transazione che **aspetta** su quel lock riparte *dopo* il commit dell'altra
e `SELECT … FOR UPDATE` **rilegge l'ultima versione committata**: carica già `N+1`, scrive
`N+1 → N+2`, le versioni coincidono e JPA non ha niente da segnalare.

> Il lock ha reso il controllo ottimistico **banalmente soddisfatto**. Non è rotto: sta
> confrontando le due cose sbagliate.

Quindi la rilevazione **non può** nascere dal confronto fra *caricato* e *memorizzato*. Deve
nascere dal confronto fra **ciò che il client ha visto l'ultima volta** e **ciò che c'è adesso**,
e quel confronto ha un solo posto corretto: **dopo che L0/L1 ha preso la riga, e prima che
qualunque regola la legga.**

### 2.3 Quello che `@Version` fa comunque, e che non è la rilevazione

Trovato per mutazione, non previsto. Spostando il confronto **sopra** il lock — la costruzione
check-then-act che P1 esiste per vietare — il risultato non è una scrittura persa: è
`StaleObjectStateException` di Hibernate al momento della lettura bloccante, perché la riga
arriva a una versione diversa da quella che il persistence context tiene già, letta senza lock
un attimo prima.

Quindi il mutante **non perde la riga: perde la risposta.** Il chiamante riceve un errore interno
al posto di «sei stato preceduto, rileggi e riprova» — un `500` dove va un `412`.

Vale la pena dirlo perché non contraddice §2.2 e non va confuso con essa: su un percorso
*ordinato correttamente* il controllo automatico continua a non rilevare niente. Ma siccome il
contatore è una vera versione JPA, **sbagliare l'ordine fallisce rumorosamente invece di perdere
una scrittura in silenzio.** È una rete, non il meccanismo.

Da cui la decisione: **`@Version` è un contatore persistente della riga, non il rilevatore.**
Serve a dare all'ETag un valore che cambia a ogni scrittura della riga e che il database mantiene
per noi. Il rilevatore è il confronto esplicito di §3.

## 3. Il protocollo di precondizione — P0–P4

Enunciato per esteso, come L0–L7, perché ogni percorso di scrittura futuro dovrà applicarlo senza
interpretarlo.

> **P0 — Ogni mutazione di una risorsa esistente richiede `If-Match`.**
> Assente → **`428 Precondition Required`**, senza guardare altro. Vale per ogni metodo che muta
> una risorsa che esiste già, `POST` di transizione compresi. Non vale per la creazione: non c'è
> nessuno stato precedente che il chiamante possa aver visto.
>
> **P1 — La precondizione si valuta dentro la transazione, dopo il lock esclusivo sulla riga
> bersaglio (L0 per `tasks`, L1 per `projects` e `agents`), e prima che qualunque regola di
> dominio legga quella riga.**
> Mai in un filtro, mai in un interceptor, mai prima del lock. Valutarla prima del lock la
> riporterebbe a essere un check-then-act; valutarla dopo le guardie significherebbe applicare
> regole a nome di un'intenzione già scaduta.
>
> **P2 — La precondizione si valuta prima di ogni regola di dominio e di ogni scorciatoia
> idempotente.**
> Un `PUT` che risulterebbe un no-op è comunque una richiesta di scrittura: il fatto che *contro
> lo stato nuovo* non cambierebbe niente è una coincidenza, non una conferma. E una richiesta
> stantia che **sarebbe rifiutata anche nel merito** riceve `412`, non il `409` della regola: il
> chiamante non sa che la risorsa si è mossa, e rispondergli sullo stato nuovo — «la destinazione
> è archiviata» — è rispondere a una domanda che non ha posto, scelta sotto informazioni diverse.
>
> *La prima formulazione di P2 era più debole di così, e la verifica per mutazione l'ha
> mostrato.* Diceva che spostare il confronto sotto `assignTo` avrebbe reso rosso il caso
> idempotente. **Non lo rende rosso**: `Task.assignTo` esce presto da **sé**, non dal service, e
> il confronto gira comunque. Ciò che la posizione decide davvero è **quale rifiuto** riceve un
> chiamante stantio, ed è quello che P2 adesso dice e che un test asserisce
> (`aStaleCallerIsToldItIsStaleAndNotWhatIsWrongWithTheNewState`).
>
> **P3 — La versione è il contatore della propria riga, e di nessun'altra.**
> Incrementata dal livello di persistenza a ogni `UPDATE` di quella riga, e da niente altro. La
> scrittura di una riga **correlata** non la muove: assegnare un task a un progetto non cambia la
> versione del progetto. `OPTIMISTIC_FORCE_INCREMENT` resta vietato (ADR-006 §4).
>
> **P4 — Clausola di chiusura.**
> Ogni percorso di scrittura, presente o futuro, che muta una risorsa esistente applica P0–P3.
> **TASK-009 incluso**: `PUT /api/tasks/{id}/agent` nasce con la precondizione, non la acquista
> dopo. Non esistono eccezioni «tanto questo caso è innocuo» — è il ragionamento che ha prodotto
> TD-25, e L7 di ADR-006 esiste per la stessa ragione.

### Il flusso, per una mutazione di un task esistente

L'ordine è il protocollo, e non è intercambiabile:

```
1.  If-Match                 assente  → 428     (P0; prima di toccare il database)
                             illeggibile → 400
2.  L0                       SELECT … FROM tasks WHERE id = ? FOR UPDATE
3.  versione corrente        letta dalla riga appena bloccata
4.  verifica precondizione   mismatch → 412     (P1, P2; prima di ogni guardia e di ogni no-op)
5.  L4/L5/L2                 insieme dei progetti, deduplicato, ordinato, FOR SHARE
6.  guardie di dominio       le regole di ADR-005 §3 e ADR-006 §2, sull'entità
7.  mutazione
8.  commit                   la versione della riga del task passa a N+1
```

Il passo 3 è la ragione per cui il passo 2 non è opzionale: la «versione corrente» ha significato
solo se nessuno può cambiarla fra la lettura e il commit, ed è precisamente ciò che L0 garantisce.

Per progetto e agente il flusso è lo stesso con L1 al posto di L0, e senza i passi 5.

## 4. Chi adotta il protocollo: tutte e tre le risorse, non le due del debito

TD-28 vive su `PUT /api/projects/{id}`, TD-30 su `PUT /api/tasks/{id}/project`. Il protocollo si
applica anche ad `Agent`, e a tutte le transizioni di ciclo di vita.

*Perché non solo i due endpoint del debito.* Perché «quali rotte sono protette» diventerebbe una
valutazione caso per caso, e il repository ha già pagato una volta per quel tipo di ragionamento:
L7 di ADR-006 esiste perché «tanto questo caso è innocuo» ha prodotto TD-25. Una precondizione con
buchi non è una precondizione: è una promessa che vale dove qualcuno si è ricordato di metterla,
e un client non ha modo di sapere dove. `Agent` non ha un debito registrato per la sovrascrittura
stantia solo perché TASK-007 è arrivata dopo la registrazione di TD-28 — non perché la sua
superficie di scrittura sia diversa.

| Rotta | `If-Match` | Perché |
|---|---|---|
| `POST /api/tasks` | **no** | Creazione: nessuno stato precedente |
| `PUT /api/tasks/{id}/project` | **sì** | TD-30 |
| `POST /api/projects` | **no** | Creazione |
| `PUT /api/projects/{id}` | **sì** | TD-28 |
| `POST /api/projects/{id}/archive` | **sì** | P0. Un `archive` e un `restore` concorrenti sono due intenzioni formate su stati diversi |
| `POST /api/projects/{id}/restore` | **sì** | P0 |
| `POST /api/agents` | **no** | Creazione |
| `PUT /api/agents/{id}` | **sì** | P0, e il gemello esatto di TD-28 |
| `POST /api/agents/{id}/activate` | **sì** | P0 |
| `POST /api/agents/{id}/deactivate` | **sì** | P0 |
| Ogni `GET` | **no** | L6: una lettura non prende lock e non ha intenzioni da dichiarare |

## 5. Dove il client prende l'ETag, e che forma ha

### 5.1 Il percorso canonico

> **`GET /api/{risorsa}/{id}` risponde `200` con l'header `ETag`.** È l'unico percorso canonico.

Questo richiede **`GET /api/tasks/{id}`, che oggi non esiste** e viene introdotto da questa task:
`200` con l'ETag, `404` con il `ProblemDetail` di `task-not-found`. Senza di esso l'unico modo di
conoscere la versione di un task sarebbe scorrere `GET /api/tasks`, il che rende obbligatorio un
listato completo per modificare una riga.

**L'ETag viene restituito anche dalle mutazioni e dalle creazioni**, sulla risposta `200` o `201`:
un client che ha appena scritto conosce già lo stato e non deve rileggerlo per poter scrivere di
nuovo. Ne segue un invariante verificabile, ed è il più utile della task:

> **L'ETag restituito da una mutazione è identico a quello che una `GET` successiva restituisce.**

Non è banale: la versione viene incrementata al flush, e una risposta composta *prima* del flush
porterebbe il numero vecchio. Il test esiste apposta (§8, I-7).

### 5.2 Che cosa **non** porta un ETag

I listati — `GET /api/tasks`, `GET /api/projects`, `GET /api/agents`,
`GET /api/projects/{id}/tasks` — non ne portano uno. Una collezione non ha una versione: averne
una significherebbe inventare un aggregato che nessuna riga rappresenta. Conseguenza dichiarata:
**un client che vuole mutare legge prima la risorsa singola.** È un round-trip in più, ed è il
prezzo della precondizione.

### 5.3 La forma

`ETag: "7"` — un entity-tag **forte**, il cui valore è la versione della riga.

*Perché forte e non `W/"7"`.* Non per eleganza: RFC 9110 impone che `If-Match` usi la
**comparison function forte**, quindi un entity-tag debole non corrisponderebbe **mai** in
`If-Match`. Un ETag debole qui sarebbe decorativo.

*Il prezzo, dichiarato.* Un entity-tag forte afferma l'uguaglianza ottetto per ottetto della
rappresentazione, e il nostro token traccia lo **stato della riga**, non i byte della risposta. Se
un giorno la forma di `TaskResponse` cambiasse senza che la riga cambi, due rappresentazioni
diverse condividerebbero un ETag. È un limite reale, registrato come **TD-32**, e non ha effetto
sulla correttezza di `If-Match`, che confronta versioni di stato — ha effetto sulla cache HTTP,
che questo progetto non usa.

*`If-Match: *` è rifiutato con `400`.* Divergenza deliberata da RFC 9110, dichiarata: il wildcard
significa «purché la risorsa esista», cioè non afferma **niente** sullo stato che il chiamante ha
visto. Accettarlo darebbe a ogni client un modo documentato di aggirare l'intero protocollo
scrivendo tre caratteri, e P0 diventerebbe una formalità. Una lista di entity-tag espliciti è
invece accettata e corrisponde se **uno** dei suoi membri è la versione corrente: enumerare
versioni che si sono davvero viste è un'affermazione, il wildcard no.

## 6. Contratto di errore — tre `type` nuovi

Dentro ADR-007 senza eccezioni: enumerati in `ApiProblem`, resi da `ApiExceptionHandler`, nessun
advice nuovo.

| Situazione | Stato | `type` |
|---|---|---|
| `If-Match` assente su una rotta che lo richiede | **`428`** | `urn:ai-company-os:problem:precondition-required` |
| `If-Match` presente, nessun membro corrisponde alla versione corrente | **`412`** | `urn:ai-company-os:problem:precondition-failed` |
| `If-Match` illeggibile, o `*` | **`400`** | `urn:ai-company-os:problem:invalid-precondition` |

*Perché `400` copre sia il malformato sia il wildcard, con un `type` solo.* Sono la stessa classe
di errore — «il valore di `If-Match` non è qualcosa che questa API possa valutare» — e il `detail`
dice quale dei due. ADR-007 §2 vieta di distinguere due problemi **dalla loro frase inglese**
quando sono problemi diversi; non impone di spaccare in due un problema solo.

### Ordine di valutazione, e la conseguenza scomoda

P0 si valuta prima di qualunque accesso al database. Quindi una richiesta **senza** `If-Match`
verso un task **inesistente** riceve `428`, non `404`.

È deliberato e va saputo: la precondizione è una proprietà della *richiesta*, non della risorsa, e
rispondere `404` significherebbe rivelare l'esistenza — o l'inesistenza — di una riga a una
richiesta che non è ancora entrata nel contratto. Con `If-Match` presente e sintatticamente
valido, `404` torna a vincere su `412`: una risorsa che non esiste non ha una versione con cui
confrontarsi. Asserito da un test.

## 7. Dove vive il confronto, nel codice

P1 esclude da solo le due implementazioni più comode:

| Alternativa | Perché no |
|---|---|
| `ShallowEtagHeaderFilter` di Spring | Calcola un hash della risposta **dopo** averla prodotta: ha già eseguito la mutazione. Non è una precondizione |
| `@Version` con `OptimisticLockException` tradotta in `412` | §2.2: dopo l'attesa sul lock pessimistico l'entità è già caricata alla versione nuova e l'eccezione non arriva mai. Sarebbe un `412` che non scatta, cioè un falso verde |
| Un `HandlerInterceptor` che legge la versione | Legge fuori transazione e senza lock: check-then-act |

Quindi: il **controller** interpreta l'header — sintassi, assenza, wildcard — e il **service**
riceve un oggetto precondizione e lo verifica **dentro la transazione, subito dopo il lock**. Il
confronto non è delegabile a un layer che non possieda il lock, ed è la ragione per cui la firma
dei metodi di servizio cambia: la precondizione entra come parametro, non come stato di thread.

La versione **non entra nei corpi JSON**. Sarebbe lo stesso fatto rappresentato due volte, in due
canali che possono divergere — l'argomento che ADR-004 usò contro `deleted_at` accanto a `status`
e ADR-006 §1 contro la cascata materializzata. L'ETag è il canale.

## 8. Che cosa si rompe

**Dichiarato, e non piccolo.** Ogni chiamata che oggi muta una risorsa esistente senza `If-Match`
smette di funzionare e riceve `428`.

| Cambio | Prima | Dopo |
|---|---|---|
| `PUT`/`POST` di mutazione senza `If-Match` | `200` | **`428`** |
| `PUT` idempotente verso lo stesso progetto archiviato, con ETag stantio | `200` no-op | **`412`** (P2) |
| Risposte `200`/`201` delle mutazioni e delle creazioni | nessun header | header **`ETag`** |
| `GET /api/{risorsa}/{id}` | nessun header | header **`ETag`** |
| `GET /api/tasks/{id}` | **non esiste** (`405`) | `200` + `ETag`, o `404` |

### Una conseguenza che il piano non aveva previsto: quale rifiuto vede il perdente di una corsa

Trovata implementando, dai test di concorrenza di TASK-004 e TASK-007, e non dal progetto.

Due `archive` concorrenti sullo stesso progetto producevano **`200` + `409`**: il secondo si
serializzava su L1, rileggeva `ARCHIVED` e `Project.archive()` sollevava la transizione illegale
di ADR-004 §4. Adesso producono **`200` + `412`**: entrambi i chiamanti hanno letto il progetto
alla stessa versione e l'hanno dichiarata, il commit del vincitore l'ha mossa, e il perdente si
ferma a **P1** prima che `Project.archive()` venga raggiunto. Lo stesso vale per due `deactivate`
concorrenti su un agente.

*Non si perde niente, e va detto perché.* La transizione illegale resta **esattamente** ciò che
riceve un chiamante **aggiornato** che chiede una transizione che lo stato vieta — archiviare un
progetto già archiviato avendone letto lo stato corrente è ancora `409`, ed è esercitato
sequenzialmente da `ProjectLifecycleTest`, `ProjectApiTest`, `AgentLifecycleTest` e
`AgentRegistryApiTest`. Cambia solo quale dei due vede il perdente di una corsa.

*E `412` è il più veritiero dei due.* Quel chiamante non ha chiesto una transizione impossibile:
ne ha chiesta una possibile, contro uno stato che nel frattempo si era mosso, e **non può sapere**
se la vorrebbe ancora sapendo com'è adesso. Dirgli «rileggi» è l'informazione giusta; dirgli
«transizione illegale» sarebbe rispondere a una domanda che non ha posto.

*L'invariante che TD-19 proteggeva regge identica*: **esattamente uno dei due riesce**, e i test
continuano ad asserirlo. Il lock resta ciò che lo garantisce, e §2.3 ne è la prova per mutazione
sull'altra entità: senza la serializzazione il confronto non diventa permissivo, diventa un
fallimento di locking ottimistico — che non è nessuno dei due esiti attesi, e quindi resta rosso.

*Perché obbligatorio e non «onorato quando presente».* Era l'alternativa ovvia e non chiude il
debito. Con `If-Match` facoltativo, TD-28 e TD-30 restano aperti per ogni client che non lo
manda — cioè per tutti quelli che esistono oggi — e il repository si troverebbe a dichiarare
chiuso un difetto che si limita a essere *evitabile*. «Un debito non si chiude perché il codice
sembra diverso» è una riga del charter. Obbligatorio è anche la direzione reversibile: rilassare
una precondizione più tardi non rompe nessuno, introdurla sì.

Nessun client reale esiste ancora: il costo della rottura è oggi il più basso che sarà mai.

## 9. Debito

| ID | Stato | Contenuto |
|---|---|---|
| **TD-28** | **CLOSED** | `PUT /api/projects/{id}` rileva l'intento stantio e risponde `412` |
| **TD-30** | **CLOSED** | Idem per `PUT /api/tasks/{id}/project` |
| **TD-32** | **OPEN — nuovo, MINOR** | L'entity-tag è forte ma deriva dalla versione della riga, non dai byte della rappresentazione: un cambio di forma della risposta senza cambio di stato produrrebbe lo stesso ETag per due rappresentazioni diverse. Nessun effetto su `If-Match`; effetto sulla cache HTTP, che il progetto non usa. Si chiude includendo una versione dello schema di rappresentazione nel token, il giorno in cui una cache esiste |
| **TD-33** | **OPEN — nuovo, MINOR** | I listati non portano ETag, quindi mutare N risorse costa N letture singole. Si chiude con una rappresentazione che porti la versione per elemento — cioè esponendo la versione nel corpo, che §7 rifiuta oggi — oppure con `If-Match` su una sotto-risorsa di collezione. Nessuna delle due è motivata finché non esiste un client che muta in blocco |

## Alternative scartate

| Alternativa | Perché no |
|---|---|
| `If-Match` facoltativo, onorato quando presente | Non chiude TD-28/TD-30: li rende evitabili. §8 |
| Sostituire il protocollo di lock con `@Version` | ADR-006 §4, e resta vero: l'ottimistico non chiude TD-25, perché l'assegnazione non scrive la riga del progetto |
| Affidarsi a `OptimisticLockException` | §2.2: dopo l'attesa sul `FOR UPDATE` l'entità è già alla versione nuova e l'eccezione non arriva mai |
| `ShallowEtagHeaderFilter` | Hash della risposta calcolato dopo la mutazione: non è una precondizione |
| Un timestamp (`updated_at`) come validatore | Risoluzione finita e orologio: due scritture nello stesso istante condividono il token. `tasks` non ha nemmeno la colonna |
| Esporre `version` nel corpo JSON | Lo stesso fatto in due canali che possono divergere. §7 |
| Applicarlo solo alle due rotte del debito | Una precondizione con buchi non è una precondizione. §4 |
| Accettare `If-Match: *` | Un bypass documentato dell'intero protocollo. §5.3 |
| `409` invece di `412` | `412` esiste per questo, e `409` è già il codice dei rifiuti di dominio di ADR-004/005/006: riusarlo renderebbe indistinguibili «lo stato non lo permette» e «non sapevi qual era lo stato» |
| `400` invece di `428` per l'header assente | `428` dice al client **che cosa fare**: rileggi e rimanda con la precondizione. `400` dice solo che ha sbagliato |
