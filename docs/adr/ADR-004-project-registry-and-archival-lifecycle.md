# ADR-004 — Project Registry: contenitore di dominio e ciclo di vita per archiviazione

- **Stato**: Accettata
- **Data**: 2026-09-11
- **Task**: TASK-002
- **Supera o modifica**: nessuna ADR precedente. Si appoggia a ADR-002 (schema di proprietà
  di Flyway) e ADR-003 (seed dev in stream separato).

## Contesto

Il Company OS non aveva ancora un dominio proprio: `Agent` e `Task` erano tabelle piatte,
senza un contenitore che le mettesse in relazione con qualcosa. L'architettura target
(`PROJECT_STATE.md`, «Target architecture») prevede che task, agenti, documenti, framework,
template, integrazioni e memoria appartengano tutti a un **progetto**.

TASK-002 introduce quel contenitore. Il vincolo di scope dato dall'utente è che il progetto
sia «**pronto a diventare**» il contenitore: la tabella e il ciclo di vita esistono, ma
nessuna entità esistente viene collegata in questa task.

## Decisioni

### 1. `Project` è un'entità autonoma, senza relazioni in TASK-002

Nessuna colonna `project_id` viene aggiunta a `tasks` o `agents`, e `V2` non tocca le tabelle
esistenti.

*Perché.* Collegare `Task` a `Project` richiede tre decisioni che non sono ancora state
prese: se l'appartenenza sia obbligatoria, cosa fare delle righe già esistenti, e cosa
significhi archiviare un progetto che ha task aperti. Una migrazione che aggiunge una FK
obbligatoria a una tabella già popolata non è reversibile con la stessa facilità di una
`CREATE TABLE`. Il costo di rimandare è una migrazione in più; il costo di anticipare è una
decisione di dominio presa per inerzia.

*Conseguenza.* `V2` è puramente additiva: su un database esistente non può rompere nulla, e
un eventuale ripensamento sulla forma di `Project` si paga con una `V3`, non con una
migrazione di dati.

### 2. L'insieme degli stati è chiuso: `ACTIVE`, `ARCHIVED`

`ProjectStatus` ha due valori, e il database impone lo stesso insieme con un check
constraint `projects_status_check`.

*Perché due guardie e non una.* L'enum protegge il percorso applicativo; il check constraint
protegge tutto il resto — import, script di manutenzione, correzioni fatte a mano in
`psql`. Senza il constraint basta una `UPDATE` per mettere nel database uno stato che il
dominio non sa interpretare, e Hibernate fallirebbe alla lettura, non alla scrittura, cioè
lontano dalla causa.

*Perché non più stati.* `PAUSED`, `COMPLETED`, `DRAFT` sono tutti plausibili e nessuno è
ancora richiesto da un requisito. Allargare l'insieme costa una migrazione e un valore
nell'enum; restringerlo dopo che qualcuno ci ha scritto sopra costa una migrazione di dati.

*Conseguenza.* Aggiungere uno stato **non** è una modifica solo applicativa: richiede una
migrazione che riscriva il constraint. È intenzionale.

### 3. Si archivia, non si cancella

Non esiste un mapping `DELETE` su `/api/projects/{id}`. Un progetto esce dal registro
operativo con `POST /{id}/archive` e rientra con `POST /{id}/restore`.

*Perché.* Il progetto è destinato a diventare la radice di task, documenti e memoria. Una
cancellazione fisica della radice porrebbe, per ogni sottodominio futuro, la domanda «cosa
succede ai figli»: cascata, orfani, o rifiuto. Con l'archiviazione la domanda non si pone —
la riga resta e le referenze restano valide — e la decisione su cosa mostrare viene presa in
lettura, dove è reversibile.

*Perché 405 e non 404.* Il path esiste per altri metodi, quindi Spring risponde `405 Method
Not Allowed` senza bisogno di scrivere un handler. La risposta dice «non per questa via»
invece di «non esiste», che è esattamente il messaggio corretto.

*Conseguenza.* Il database cresce in modo monotono. Non c'è oggi un percorso di cancellazione
definitiva, nemmeno amministrativo: se servirà — per cancellazione di dati personali, per
esempio — sarà una decisione esplicita e documentata, non un endpoint dimenticato.

### 4. Una transizione illegale è `409`, non un no-op

Archiviare un progetto già archiviato restituisce `409 Conflict`. La regola vive
sull'entità (`Project.archive()`), non nel service.

*Perché non idempotente.* Un `archive` ripetuto è quasi sempre un errore del chiamante:
doppio click, retry cieco, stato locale disallineato. Trasformarlo in un no-op silenzioso
nasconde il bug e rende la macchina a stati non verificabile. Il costo è che un client che
vuole «assicurarsi che sia archiviato» deve leggere prima o tollerare il `409`.

*Perché sull'entità.* Se la regola stesse nel service, ogni nuovo punto di ingresso —
un'altra API, un importer, un agente — dovrebbe ricordarsi di riapplicarla. Sull'entità è
impossibile aggirarla, perché `status` non ha un setter.

### 5. Il nome è unico, senza distinzione tra maiuscole e minuscole, e l'unicità è del database

`CREATE UNIQUE INDEX projects_name_unique_idx ON projects (lower(name))`.

*Perché nel database.* Il controllo `existsByNameIgnoreCase` nel service serve a produrre un
`409` leggibile nel caso ordinario, ma non è una garanzia: due richieste concorrenti possono
superarlo entrambe. L'indice è ciò che effettivamente impedisce il duplicato; il service
traduce la `DataIntegrityViolationException` risultante nello stesso `409`, così il contratto
dell'API è identico nei due percorsi.

*Perché case-insensitive.* Un registro con «Company OS» e «company os» affiancati è un
registro rotto, indipendentemente da quale sia quello giusto.

*Cosa è stato rimandato.* Nessuno slug pubblico stabile. Il nome è oggi anche la chiave
naturale, e rinominare un progetto ne cambia l'identità leggibile. Uno slug immutabile
separato dal nome visualizzato è la soluzione corretta quando serviranno URL condivisibili o
riferimenti incrociati; oggi non servono.

### 6. Il contratto di errore è limitato al modulo `project`

`ProjectExceptionHandler` è annotato `@RestControllerAdvice(assignableTypes =
ProjectController.class)`.

*Perché non globale.* Un advice globale cambierebbe le risposte che gli endpoint `agents` e
`tasks` già producono, cioè un cambio di contratto osservabile su API preesistenti, fuori
dallo scope di questa task. Un contratto di errore uniforme per tutta l'API è una decisione a
sé — tracciata come debito **TD-07** — e va presa deliberatamente, non introdotta di
straforo insieme a un modulo nuovo.

*Conseguenza.* Per un periodo l'API ha due forme di errore: `ProblemDetail` sotto
`/api/projects`, il default di Spring altrove. È una disomogeneità nota e verificata da un
test (`theProjectErrorContractDoesNotLeakIntoTheTaskApi`), non una svista.

### 7. `createdAt` / `updatedAt` in `TIMESTAMP WITH TIME ZONE`, valorizzati dall'entità

*Perché con fuso.* `Instant` su una colonna `timestamp without time zone` perde l'offset in
silenzio: il valore riletto resta plausibile ed è sbagliato. Un test asserisce il tipo della
colonna, perché la modalità `validate` di Hibernate non è affidabile su questa distinzione.

*Perché dall'entità e non da `DEFAULT now()`.* Con `@PrePersist` / `@PreUpdate` l'oggetto in
memoria ha gli stessi valori della riga subito dopo il salvataggio, senza un giro di lettura.
Il compromesso è che i timestamp vengono dall'orologio dell'applicazione, non da quello del
database: con più istanze e orologi non sincronizzati l'ordinamento per `created_at` può
essere leggermente incoerente. Irrilevante con un solo processo; da rivedere prima di
scalare orizzontalmente.

## Alternative scartate

| Alternativa | Perché no |
|---|---|
| `Task.project_id` già in TASK-002 | Richiede decisioni di dominio non prese e una migrazione di dati su una tabella popolata |
| Cancellazione fisica con cascata | Rende la cancellazione della radice un problema per ogni sottodominio futuro |
| `deleted_at` nullable (soft delete) | Esprime lo stesso stato in due modi, `status` e un timestamp, che possono divergere |
| `archive` idempotente (`200` sempre) | Nasconde un errore del chiamante e rende la macchina a stati non verificabile |
| Advice globale su tutta l'API | Cambia il contratto di endpoint fuori scope |
| Identificatore `UUID` | Rompe la coerenza con `agents` e `tasks` senza un requisito che lo giustifichi |

## Conseguenze operative

- `V2` si applica su un database già a `V1` senza toccare i dati esistenti: verificato sul
  volume di sviluppo reale `aicompany_postgres_data`.
- Il numero di versione della fixture di test che rappresenta «la prossima migrazione» è
  passato da `V2` a `V900`, perché `V2` è ora una migrazione reale. Il vincolo — sopra la
  testa dello stream di schema e sotto `1000` — è documentato nella fixture stessa.
- I test che asserivano l'elenco delle versioni applicate ora lo leggono da Flyway invece di
  scriverlo a mano, così le prossime migrazioni non li rompono.
