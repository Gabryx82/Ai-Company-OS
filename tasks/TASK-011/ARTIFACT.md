# TASK-011 — Chiusura

**Registro del debito e documentazione operativa.** Livello 6, ultima task di PHASE 2.
Suite **216 → 219**. Nessun cambiamento al codice applicativo, nessuna migrazione.

## 1. Che cosa è cambiato

| File | Cambio |
|---|---|
| `docs/DEBT_REGISTRY.md` | **nuovo.** La mappa dei due spazi di identificatori |
| `docs/audit/TECHNICAL_DEBT.md` | Nota in testa: i suoi `TD-NN` non sono quelli vivi, e cinque collidono |
| `docs/RUNNING.md` | Sezione API riscritta (3 → 18 endpoint); **due istruzioni false corrette** |
| `backend/.../docs/DebtRegistryConsistencyTest.java` | **nuovo.** 3 test |

## 2. La collisione, e perché non si risolve rinumerando

Cinque identificatori significano cose diverse nei due registri: **`TD-14`, `TD-19`, `TD-20`,
`TD-21`, `TD-22`**. `TD-01`…`TD-13` e `TD-15`…`TD-18` coincidono, ed è ciò che rende il difetto
insidioso — chi ne verifica due o tre conclude che il problema non esista.

Il rischio ha una forma precisa: **«`TD-20` è chiuso» è vero in uno spazio e falso nell'altro**, e
niente nel repository diceva quale si stesse usando.

Entrambe le rinumerazioni sono state considerate e scartate:

- **rinumerare i vivi** romperebbe ogni riferimento in ADR, artefatti e messaggi di commit — cioè
  romperebbe la tracciabilità per sistemare un documento;
- **rinumerare l'audit** riscriverebbe ciò che TASK-000 **osservò**. È uno snapshot datato, e uno
  snapshot che si aggiorna non è più uno snapshot.

**La soluzione è additiva**, che è la forma che il progetto dà alle migrazioni: non si riscrive
ciò che c'è, si aggiunge ciò che manca a renderlo interpretabile.

## 3. La decisione più difficile: non dichiarare chiusi i debiti dell'audit

Otto debiti esistono solo nell'audit (`TD-01`, `TD-02`, `TD-03`, `TD-05`, `TD-06`, `TD-09`,
`TD-10`, `TD-16`) e **diversi sono con ogni evidenza risolti**: H2 è stato rimosso, Flyway possiede
lo schema, la validazione Jakarta esiste, i DTO esistono, i test sono 219 dove l'audit contava zero.

Sarebbe stato facile marcarli chiusi, e il registro sarebbe sembrato più utile.

**Non è stato fatto**, e il motivo sta nel charter:

> Un debito non si chiude perché il codice sembra diverso: serve una verifica che fallisca se il
> difetto torna.

«Con ogni evidenza risolto» **è** «il codice sembra diverso». Ognuno di quegli otto richiede di
essere riaperto, riletto e verificato, il che è una task, non una riga di tabella. Il registro dice
**«non rivalutato qui»**, che è vero, e registra la rivalutazione come la prossima task ovvia su
questo materiale.

## 4. `docs/RUNNING.md`: due istruzioni false, non due omissioni

La differenza conta, perché una documentazione incompleta fa perdere tempo e una documentazione
falsa fa sbagliare.

**Prima:** «*La prossima migrazione di schema si chiama `V2__...sql`*». Lo stream è a `V7`. Chi
seguiva quella riga creava una `V2` che **Flyway rifiuta**. Sostituita dalla regola («il numero
successivo alla testa»), dal valore di oggi, e dal **comando per leggerlo** invece di fidarsi del
paragrafo — perché è precisamente un valore scritto a mano che è andato stantio.

**Prima:** «*Dopo la transizione deve contenere **solo** `1 | V1__create_agents_and_tasks.sql`*».
Vero appena dopo TASK-001A, falso da `V2` in poi. Ciò che la verifica deve cercare è l'**assenza**
della riga legacy `1000`, non la presenza di una riga sola.

Entrambe portano una nota che dice **che cosa affermavano prima**: qualcuno può averci agito, e
cancellare l'errore in silenzio gli toglierebbe il modo di capire cosa gli è successo.

**E una cosa che mancava del tutto**, messa per prima perché è quella che un lettore incontra
subito: **ogni mutazione richiede `If-Match`**, quindi un `428` è un header mancante e non un
server rotto. Senza quella riga, il primo `PUT` di chiunque fallisce in modo incomprensibile.

## 5. Perché un test per un problema di documentazione

`DebtRegistryConsistencyTest`, 3 test, e il punto è la stessa frase del charter: un debito non si
chiude perché la prosa sembra diversa. Il difetto **torna** quando esiste un identificatore
dell'audit di cui il registro non rende conto, e questo è ciò che il test asserisce.

**Tre decisioni nel modo in cui è scritto:**

1. **Legge file fuori dal proprio modulo**, cosa che nient'altro nella suite fa. L'accoppiamento è
   dichiarato nel javadoc: i documenti *sono* l'oggetto sotto test.
2. **Trova la radice risalendo** fino a una directory che contiene `.company-os`, invece di
   assumere la working directory di Surefire.
3. **Fallisce quando non trova i file, invece di saltare.** Una guardia sulla documentazione che
   passa perché non ha trovato la documentazione è peggio di nessuna guardia — ed è anche il modo
   in cui l'ha fatta fallire la baseline, il che l'ha verificata.

C'è anche un guard contro la vacuità: se il formato delle intestazioni dell'audit cambia, il set
degli id letti diventa vuoto e `containsAll` passerebbe su qualunque cosa. Un'asserzione
`isNotEmpty` lo impedisce.

## 6. Baseline e verifica

**3 rossi sulla baseline**, per le tre ragioni giuste: due non trovavano
`docs/DEBT_REGISTRY.md` (che non esisteva), il terzo non trovava il puntatore in testa all'audit.

La verifica per mutazione qui è il rosso della baseline stesso: il test è passato da rosso a verde
per effetto esatto dei due file scritti, e ciascuno dei tre asserisce una cosa diversa.

## 7. Adversarial review

Nessun rilievo HIGH o MEDIUM.

**LOW registrati:**

- **L-1** — `DEBT_REGISTRY.md` §5 ripete informazione che sta anche in `PROJECT_STATE.md`. Due
  punti di verità sullo stato dei debiti vivi possono divergere. Mitigato dal fatto che il
  registro rimanda al primo come autoritativo, e che il test copre l'allineamento **con l'audit**,
  che è il lato che collide. L'allineamento con `PROJECT_STATE.md` resta a disciplina.
- **L-2** — Il test conosce le cinque collisioni come costante scritta a mano. È un **rilievo**,
  non un difetto: sono una scoperta, non una derivazione, e una sesta collisione deve obbligare
  qualcuno a venire a dichiararla.
- **L-3** — `RUNNING.md` elenca gli endpoint a mano e nessun test verifica che l'elenco resti
  completo. Un endpoint nuovo può mancare senza che niente diventi rosso. Si chiuderebbe con
  OpenAPI (`TD-03`/M-3 dell'audit), che è una decisione a sé e sostituirebbe il documento invece
  di correggerlo.

## 8. Debito

**Nessun debito nuovo aperto.** Prossimo identificatore libero: **`TD-38`**.

Registrato come prossima task ovvia su questo materiale, e **non fatto**: la rivalutazione degli
otto debiti dell'audit di §3.

## 9. Suite

**219/219 verdi.** `./mvnw -B clean test` → `BUILD SUCCESS`. Schema `V7`, invariato: questa task
non tocca il codice applicativo né le migrazioni.
