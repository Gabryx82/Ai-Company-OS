# TASK-005 — Implementation

Resoconto di quello che è stato fatto. Decisioni in `docs/adr/ADR-007-uniform-error-contract.md`.

## Forma della soluzione

Due classi nuove e due rimozioni. Nessun endpoint, nessuno stato HTTP, nessuna entità, nessun
service, nessuna migrazione.

| File | Ruolo |
|---|---|
| `api/ApiProblem.java` **nuovo** | Enum chiuso: slug, stato, titolo, detail di default. Tiene insieme le tre cose che non devono divergere |
| `api/ApiExceptionHandler.java` **nuovo** | `@RestControllerAdvice` globale che estende `ResponseEntityExceptionHandler` |
| `project/controller/ProjectExceptionHandler.java` **rimosso** | |
| `task/controller/TaskExceptionHandler.java` **rimosso** | |

## Perché estendere `ResponseEntityExceptionHandler`

Metà del contratto mancante non riguardava le nostre eccezioni: JSON illeggibile,
`Content-Type` mancante, metodo non consentito, path variable non convertibile, rotta
inesistente. Sono esattamente le famiglie che TD-20 e TD-27 nominavano, e il framework le conosce
già. Ereditarle tiene l'elenco aggiornato a ogni upgrade invece di costringerci a rincorrerlo.

Override applicati: `handleMethodArgumentNotValid`, `handleHttpMessageNotReadable`,
`handleHttpMediaTypeNotSupported`, `handleHttpRequestMethodNotSupported`, `handleTypeMismatch`,
`handleNoResourceFoundException`.

## Il `type`, che è il punto

Prima, l'unico modo per distinguere due `409` dall'esterno era confrontare la loro frase
inglese. Questo rendeva la frase parte del contratto senza che nessuno lo decidesse, e la
congelava contro qualunque correzione.

Adesso: `urn:ai-company-os:problem:<slug>`, enumerato. **Il `type` è il contratto, il `title` è
la prosa.** Un URN e non un URL perché RFC 9457 non richiede che l'URI si risolva, e puntare a un
dominio che non possediamo asserirebbe documentazione inesistente.

## Test

| File | Copre |
|---|---|
| `ApiErrorContractTest` **nuovo**, 9 test | AC-1…AC-7. Scritto **prima**, visto fallire 9 volte su 9 |
| `ApiProblemCoverageTest` **nuovo**, 4 test | AC-8, AC-10. Sostituisce `TaskExceptionHandlerScopeTest` |
| `UnexpectedFailureContractTest` **nuovo** | AC-9: il `500` non perde niente |
| `TaskExceptionHandlerScopeTest` **rimosso** | Custodiva il confine di un advice che non esiste più |
| `theProjectErrorContractDoesNotLeakIntoTheTaskApi` **rimosso** | Asseriva l'opposto di ciò che è ora vero, con l'asserzione debole di TD-21 |

`ApiProblemCoverageTest` **cambia mestiere** rispetto al test che sostituisce. Quello impediva al
contratto di allargarsi per distrazione; questo impedisce che un'eccezione di dominio resti
**senza** mappatura, perché adesso il rischio è il suo opposto: un `500` scoperto in produzione
invece di un fallimento qui. Scansiona i package di eccezioni e li confronta con le mappature
dichiarate.

## Verifica per mutazione (AC-13)

| Mutazione | Test che diventa rosso |
|---|---|
| Il `500` rimanda `e.getMessage()` | `UnexpectedFailureContractTest` |
| Tolta una mappatura di dominio | `everyDomainExceptionIsMapped` |
| Due problemi con lo stesso slug | `everyProblemHasItsOwnIdentifier` |
| Prefisso URN cambiato in un URL | `everyProblemHasItsOwnIdentifier` |
| Advice ristretto a un solo controller | `ApiErrorContractTest` |

Cinque su cinque.

## Review avversariale del proprio diff

| Gravità | Rilievo | Correzione |
|---|---|---|
| LOW | Il catch-all rilanciava `ErrorResponseException` per «lasciarla alla classe base». Ramo **irraggiungibile** — Spring sceglie l'handler più specifico, e la classe base la dichiara — e comunque rilanciare da dentro un handler non ridistribuisce | Rimosso, e la ragione scritta nel Javadoc |
| LOW | Helper `signature(Method)` inutilizzato in `ApiProblemCoverageTest` | Rimosso |

## Risultato

**136 test verdi** (erano 126). Schema fermo a `V3`. Nessuno stato HTTP cambiato.

Chiusi: **TD-07**, **TD-20**, **TD-21**, **TD-27**, **TD-29**.
