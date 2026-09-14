# ADR-007 — Un solo contratto di errore per tutta l'API, con un identificatore stabile

- **Stato**: **Accettata** — decisa autonomamente il 2026-09-14 in Autonomous Project Mode.
- **Task**: TASK-005
- **Rapporto con le precedenti**: **supera ADR-004 §6 e ADR-005 §8**, che limitavano
  deliberatamente il contratto di errore al proprio modulo. Entrambe dichiaravano quella
  limitazione come temporanea e la tracciavano come **TD-07**. Questa ADR la scioglie.

## Contesto

L'API parla tre dialetti di errore:

| Dove | Forma |
|---|---|
| `/api/projects` | `ProblemDetail` (ADR-004 §6) |
| `/api/tasks`, solo le risposte di errore introdotte da TASK-003 e TASK-004 | `ProblemDetail` (ADR-005 §8) |
| `/api/agents`, e ogni altro errore ovunque — validazione, JSON malformato, `Content-Type` mancante, path variable non numerica, `405`, `500` | il default di Spring |

Non è una svista: ogni passo l'ha scelto, per non cambiare il contratto di endpoint fuori
scope, e ogni passo l'ha registrato. Il costo però cresce da solo. `POST /api/projects {}` e
`POST /api/tasks {}` falliscono per la stessa ragione e rispondono con due corpi diversi;
`GET /api/projects/999/tasks` e `GET /api/projects/abc/tasks` rispondono con due dialetti sulla
stessa rotta (TD-27). Ogni endpoint nuovo deve scegliere un dialetto o aggiungerne un quarto, e
ogni pezzo della target architecture che arriva — Agent Registry, Planner, Model Gateway — è
superficie nuova che eredita la scelta.

## Decisioni

### 1. Un advice globale, che estende `ResponseEntityExceptionHandler`

`ApiExceptionHandler` è `@RestControllerAdvice` senza `assignableTypes`, ed estende
`ResponseEntityExceptionHandler`.

*Perché estenderlo e non scriverlo da zero.* Metà di TD-07 non riguarda le nostre eccezioni: è
quello che Spring solleva prima che il nostro codice venga eseguito — JSON malformato,
`Content-Type` mancante, metodo non consentito, path variable non convertibile, handler
inesistente. `ResponseEntityExceptionHandler` li gestisce già tutti e in Spring 6 li rende come
`ProblemDetail`. Riscriverli significherebbe rincorrere un elenco che cresce con il framework.
Estenderlo li porta dentro il contratto e lascia a noi solo la parte di dominio.

*Conseguenza: TD-20 e TD-27 si chiudono qui*, perché erano esattamente quelle due famiglie.

### 2. Ogni problema ha un `type` stabile, ed è quello il contratto

Ogni risposta di errore porta `type`, un URN nella forma:

```
urn:ai-company-os:problem:<slug>
```

per esempio `urn:ai-company-os:problem:project-not-found`,
`urn:ai-company-os:problem:archived-project-is-immutable`.

*Perché serve.* Oggi l'unica cosa che distingue due `409` è `title`, cioè una stringa in inglese
pensata per un essere umano. Un client che deve reagire diversamente ai due casi non ha altra
scelta che confrontare quella stringa — e da quel momento il testo non si può più correggere
senza rompere qualcuno. Il `type` separa le due cose: **il `type` è il contratto, il `title` è
la prosa.** Si può riscrivere un titolo; non si cambia un `type` senza dichiararlo.

*Perché un URN e non un URL.* RFC 9457 consente qualunque URI e non richiede che sia
dereferenziabile. Un `https://…` verso un dominio che non possediamo asserisce una
documentazione che non esiste e che qualcun altro potrebbe un giorno servire. L'URN dice
esattamente quello che è: un identificatore. Se un giorno ci sarà documentazione pubblica, il
passaggio a `https://` sarà una decisione a sé, con la sua nota di rottura.

*Enumerati, non liberi.* I `type` vivono in un enum `ApiProblem`, che tiene insieme slug, stato
HTTP e titolo. Inventarne uno al volo in un handler non è possibile: aggiungerne uno è un gesto
deliberato in un posto solo, e un test ne asserisce l'insieme esatto.

### 3. Gli errori di validazione elencano i campi, ovunque

`400` di validazione porta la proprietà `errors`: mappa `campo → messaggio`. Era il
comportamento di `/api/projects` (ADR-004 §6) e diventa quello di tutta l'API.

*Cambio di contratto osservabile.* `POST /api/tasks {}` rispondeva con il corpo di default di
Spring — `{timestamp, status, error, path}` — e ora risponde `ProblemDetail` con `errors`. È
una rottura per un client che leggesse `$.timestamp`, ed è **precisamente il lavoro di questa
task**: TD-07 non si chiude senza cambiare quei corpi. Dichiarata qui invece di essere scoperta.

### 4. Un catch-all, che non dice niente di interno

`Exception` non gestita → `500` con `type: urn:ai-company-os:problem:internal-error`, `detail`
fisso e generico. L'eccezione viene **loggata per intero**, con il suo stack.

*Perché un catch-all.* Senza, un errore imprevisto sfugge al contratto proprio nel momento in
cui il client ne ha più bisogno: quando non sa cosa è successo. Con, ogni risposta dell'API ha
la stessa forma, sempre.

*Perché il `detail` è fisso.* Il messaggio di un'eccezione interna contiene nomi di tabelle,
frammenti di SQL, percorsi. Rimandarlo al chiamante è un canale di informazione che non abbiamo
deciso di aprire. Il `detail` generico e il log completo mettono l'informazione dove serve.

*Rischio dichiarato.* Un catch-all può mascherare un bug facendolo sembrare gestito. È il motivo
per cui logga a `ERROR` con lo stack completo: la riga di log è il posto dove il difetto resta
visibile.

### 5. I fallimenti infrastrutturali di locking hanno una forma, non una policy

`PessimisticLockingFailureException` e simili passano dal catch-all: `500`, stessa forma di
tutto il resto.

*Perché questo chiude TD-29 senza contraddire ADR-006 §7.* ADR-006 ha rifiutato **una policy** —
`lock_timeout`, `Retry-After`, un `503` e un contratto di ritentativo — e ha registrato come
debito la sola **assenza di una forma**. La forma è quello che questa task dà. Nessun timeout
viene introdotto, nessun retry viene promesso, nessun codice di stato nuovo entra nel
vocabolario. TD-29 si chiude per quello che era: un problema di forma dentro TD-07.

### 6. I due advice di modulo spariscono

`ProjectExceptionHandler` e `TaskExceptionHandler` vengono rimossi. Le loro eccezioni sono
mappate dall'advice globale.

*Perché rimuoverli e non lasciarli accanto.* Due advice che potrebbero gestire la stessa
eccezione rendono la risposta dipendente da una precedenza che nessuno ha scelto. Un contratto
uniforme con due punti di definizione non è uniforme, è fortunato.

*Il test che li custodiva cambia mestiere.* `TaskExceptionHandlerScopeTest` asseriva l'insieme
esatto delle eccezioni gestite *per impedire che il contratto si allargasse per distrazione*.
Quel rischio non esiste più — adesso si allarga per decisione — e il test diventa il suo
opposto: **ogni eccezione di dominio dichiarata deve avere una mappatura**, così aggiungerne una
senza decidere che aspetto abbia diventa un fallimento. Anche
`theProjectErrorContractDoesNotLeakIntoTheTaskApi` viene rimosso: asseriva che il contratto
*non* arrivasse ai task, ed è ora vero il contrario. Con lui se ne va **TD-21**, che era la
debolezza di quella asserzione.

### 7. Che cosa **non** cambia

- Nessun codice di stato cambia. Solo i **corpi**.
- Nessuna migrazione, nessuno schema, nessuna entità, nessun service.
- Nessuna policy di timeout o di retry (§5).
- Nessuna internazionalizzazione dei messaggi: restano in inglese, e il `type` è ciò su cui un
  client si appoggia (§2).

## Alternative scartate

| Alternativa | Perché no |
|---|---|
| Lasciare i tre dialetti | Peggiora da solo: ogni endpoint nuovo eredita la scelta o ne aggiunge una quarta |
| `spring.mvc.problemdetails.enabled=true` e basta | Uniforma la forma ma non dà né `type` stabili né `errors`, e lascia le eccezioni di dominio fuori |
| Advice globale scritto da zero, senza `ResponseEntityExceptionHandler` | Costringe a rincorrere l'elenco delle eccezioni del framework a ogni upgrade |
| `type` come URL su un dominio che non possediamo | Asserisce una documentazione inesistente, su un dominio che qualcun altro può servire |
| Nessun `type`, e i client confrontano `title` | Congela il testo inglese dentro il contratto: non si può più correggere una frase |
| `type` come stringa libera per handler | Nessuno garantisce che due handler non usino lo stesso slug per cose diverse |
| Nessun catch-all | L'unica risposta fuori contratto sarebbe quella che il client capisce meno |
| Catch-all che rimanda il messaggio dell'eccezione | Espone tabelle, SQL e percorsi a chiunque chiami |
| `503` per i fallimenti di locking | Reintrodurrebbe la policy che ADR-006 §7 ha rifiutato |
| Mantenere i due advice di modulo accanto a quello globale | Un contratto con due punti di definizione dipende da una precedenza che nessuno ha scelto |

## Conseguenze operative

- **Rottura dichiarata**: i corpi di errore di `/api/agents` e delle risposte preesistenti di
  `/api/tasks` cambiano. Nessuno stato HTTP cambia.
- **Chiude TD-07**, e con lui **TD-20**, **TD-27**, **TD-29** e **TD-21**.
- L'insieme dei `type` è enumerato e asserito da un test: aggiungerne uno è deliberato.
- Un'eccezione di dominio senza mappatura diventa un fallimento di test, non un `500`
  scoperto in produzione.
- Il catch-all logga a `ERROR` con lo stack: è lì che un difetto mascherato resta visibile.
