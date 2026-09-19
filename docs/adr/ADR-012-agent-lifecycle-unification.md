# ADR-012 — Unificazione del ciclo di vita di `Agent`: da `boolean` a enum chiuso

- **Stato**: **Accettata e implementata.** Il cambiamento distruttivo è stato **autorizzato
  esplicitamente da un umano** il 2026-09-19, delimitato allo scope di TD-31.
- **Data**: 2026-09-19
- **Debito**: **TD-31** (registro vivo), aperto da TASK-007
- **Rapporto con le precedenti**: **supera ADR-008 §2**, che decise di *non* fare questo
  cambiamento. Non lo contraddice: ADR-008 §2 lo rimandava esplicitamente «quando la decisione
  umana ci sarà», e adesso c'è. Applica ad `Agent` le due guardie di **ADR-004 §2**.

## 0. Perché questa ADR esiste, dato che ADR-008 §2 aveva già deciso

ADR-008 §2 decise di **non** unificare, e la motivazione non era tecnica:

> Unificare significa portare `active` a `status VARCHAR(32)` con backfill e poi **eliminare la
> colonna `active`**. Il backfill sarebbe senza perdita — un booleano verso due valori è una
> biiezione — ma la rimozione di una colonna è una migrazione irreversibile, e
> `AUTONOMOUS_CHARTER.md` la mette dietro una decisione umana **quando esiste un'alternativa
> ragionevole**.

Quella decisione umana è stata presa il 2026-09-19. **Niente altro è cambiato**: non un
requisito nuovo, non un difetto scoperto, non un'analisi diversa. ADR-008 §2 aveva ragione allora
e ha ragione adesso; era una decisione *sospesa*, non sbagliata, e questa ADR la scioglie.

Va detto perché conta per chi legge il registro: **TD-31 non è stato chiuso perché il costo è
aumentato.** Il costo di non decidere era ed è basso. È stato chiuso perché qualcuno ha deciso.

## 1. La decisione

`agents.active BOOLEAN NOT NULL` diventa `agents.status VARCHAR(32) NOT NULL`, con
`agents_status_check CHECK (status IN ('ACTIVE','INACTIVE'))`, e **`active` viene eliminata**.
Migrazione **`V8`**.

Nell'applicazione: `AgentStatus` enum, `@Enumerated(EnumType.STRING)` sull'entità.

## 2. Perché il backfill non perde niente, e come lo si verifica

L'argomento è quello che ADR-008 §2 aveva già scritto, ed è verificabile invece che plausibile:

```
active = TRUE   <->  status = 'ACTIVE'
active = FALSE  <->  status = 'INACTIVE'
```

Due valori verso due valori, **totale e iniettiva in entrambe le direzioni**. Nessuna riga resta
senza immagine, nessuna coppia di righe collassa. `active` è `NOT NULL` da `V1`, quindi non esiste
un terzo caso da decidere — non c'è un `NULL` che significhi «non si sa», e quindi **non c'è
nessun mapping da inventare**.

Questo è ciò che distingue `V8` dal caso che il charter mette dietro l'hard stop #2 (*eliminazione
irreversibile di dati*): l'informazione non viene persa, viene **riscritta in un'altra
rappresentazione**. Ciò che è irreversibile è la *forma*, non il contenuto.

**Verificato, non affermato.** `MigrationStreamTest.agentLifecycleIsUnifiedOnAPopulatedV7Database`
semina **entrambi** i valori prima della migrazione e asserisce entrambe le immagini dopo. Un test
con soli agenti attivi sarebbe passato anche contro una migrazione che scrive `'ACTIVE'`
incondizionatamente — ed è esattamente la mutazione con cui il test è stato messo alla prova
(`tasks/TASK-012/ARTIFACT.md`).

**Verificato anche sui dati reali.** Il database di sviluppo di questa macchina è stato clonato e
migrato: 3 agenti `active = t` → 3 agenti `'ACTIVE'`, nessuna riga persa, l'applicazione si avvia
(quindi Hibernate `validate` passa) e l'API risponde. Il database originale non è stato toccato.

## 3. Perché `INACTIVE` e non `ARCHIVED`

Invariato da ADR-008 §2: un progetto archiviato è **messo via**, un agente disattivato è
**spento**. Usare la stessa parola per due significati diversi sarebbe coerenza formale contro il
senso.

**Quello che TD-31 unifica è la *forma* della rappresentazione — un enum chiuso con un `CHECK` —
non il vocabolario.** Chi legge «unificare il ciclo di vita» e si aspetta gli stessi due nomi di
`ProjectStatus` sta leggendo TD-31 più largo di com'è scritto.

Non è nemmeno un vocabolario nuovo: `AgentResponse.status` pubblica `ACTIVE`/`INACTIVE` dalla
TASK-007. `V8` ha reso **vero nel database** ciò che l'API diceva già.

## 4. Il contratto pubblico non cambia di un byte, e la derivazione si inverte

Questa è la parte che rende il cambiamento sicuro, e va letta prima di «semplificare».

`AgentResponse` ha **due** campi di ciclo di vita, `active` e `status`, e li ha da TASK-007. Ciò
che `V8` cambia è **quale dei due è reale**:

| | Prima di `V8` | Dopo `V8` |
|---|---|---|
| Nel database | `active BOOLEAN` | `status VARCHAR(32)` + `CHECK` |
| Nella risposta | `active` reale, `status` **derivato** | `status` reale, `active` **derivato** |
| JSON osservabile | `{"active":true,"status":"ACTIVE"}` | `{"active":true,"status":"ACTIVE"}` |

**Un client non può accorgersi che la migrazione è avvenuta**, ed è il punto: TD-31 era una
divergenza *dentro il database*, non una promessa ai chiamanti, e chiuderla non deve costare
niente a nessuno.

*Perché `active` resta nella risposta.* Per la ragione per cui ci fu messo: toglierlo romperebbe
ogni client esistente per una ridenominazione, e **aggiungere un campo è reversibile mentre
toglierne uno non lo è**. Eliminarlo è una decisione a sé che nessuno ha preso, e **non è dentro
TD-31**.

*Perché `?active=true|false` resta un booleano.* Stesso argomento: è contratto pubblico. Il
servizio traduce il booleano in `AgentStatus`, e il punto di incontro fra i due vocabolari è lì —
un posto solo.

*Perché `Agent.isActive()` sopravvive come lettore derivato.* Perché è ciò che le regole altrove
chiedono davvero: `Task.assignTo` vuole sapere se il lavoro può essere assegnato, non quale dei
due nomi la colonna contiene. Rinominarlo avrebbe propagato un cambiamento in call site che TD-31
non riguarda — cioè avrebbe allargato lo scope.

## 5. Conseguenza non prevista: il dev seed nomina la colonna che `V8` elimina

Trovata implementando, non progettando, ed è la parte di questa ADR che vale la pena leggere.

`db/dev/V1__dev_seed_agents.sql` scriveva `INSERT INTO agents (..., active)`. Su un database
**nuovo** lo stream dello schema arriva a `V8` *prima* che il seed parta, quindi quella colonna non
esiste più e **il seed fallisce**. Un seed che non parte non è un seed.

`V4` aveva incontrato una collisione della stessa famiglia e l'aveva risolta con un `DEFAULT`,
proprio per **non** toccare un file già applicato. **Quella via qui non esiste**: un `DEFAULT` non
aiuta un `INSERT` che *nomina* una colonna scomparsa.

Modificare il file cambia il suo checksum, e Flyway valida i checksum a ogni `migrate`: ogni
database che aveva già seminato — **compreso quello di sviluppo di questa macchina** — rifiuterebbe
di avviarsi in profilo `dev`.

**Deciso**: `DevSeedFlyway.apply` esegue `repair()` prima di `migrate()`. Realinea i checksum
registrati; tocca **solo metadati**, nessuna tabella, colonna o riga applicativa, e non ri-esegue
una migrazione già applicata.

*Quello che costa, e perché è accettabile qui e in nessun altro posto.* `repair()` accetta anche
una modifica che nessuno intendeva fare: scambia un fallimento rumoroso con un'accettazione
silenziosa. È un pessimo scambio sullo **stream dello schema** — che la produzione esegue, e dove
una migrazione che cambia sotto un database applicato è esattamente l'incidente che la validazione
esiste per intercettare. Per questo la chiamata **non** è lì e **non va copiata lì**.

Su questo stream valgono tre cose insieme, e servono tutte e tre: è **solo dev**
(`DevSeedFlywayConfiguration` lo lega al profilo), porta **dati dimostrativi** e non dati di
schema o di riferimento, e la sua unica istruzione è **idempotente** per il proprio
`WHERE NOT EXISTS`. Il caso peggiore di una modifica non notata è: righe dimostrative diverse sulla
macchina di uno sviluppatore.

**Verificato**: `MigrationStreamTest.anAlreadySeededDatabaseSurvivesTheSeedBeingEdited` scrive un
checksum stantio e asserisce che `apply` sopravviva senza ri-eseguire né duplicare nulla. E il
clone del database reale, che *aveva* il checksum vecchio, si è avviato.

## 6. Conseguenza derivata: il seed è valido solo alla testa dello stream

Prima di `V8` il seed funzionava a **qualunque** versione, perché `active` esisteva da `V1`.
Adesso funziona solo da `V8` in poi.

Nel prodotto non cambia niente: `DevSeedFlywayConfiguration` applica il seed **dopo** l'intero
stream dello schema, sempre. Cambiava invece per i test, che usavano il seed per popolare agenti a
versioni intermedie — e adesso scrivono direttamente, chiedendo a `information_schema` quale
colonna esista a quella versione (`MigrationStreamTest.insertAgentAt`).

È un accoppiamento che esisteva già e che nessuno aveva dovuto nominare. Adesso è nominato.

## 7. Che cosa **non** è stato fatto, deliberatamente

| Escluso | Perché |
|---|---|
| Togliere `active` da `AgentResponse` | Contratto pubblico. Rottura per una ridenominazione, e non è TD-31 (§4) |
| Cambiare `?active=` in `?status=` | Stesso motivo |
| Rinominare `Agent.isActive()` | Propagherebbe un cambiamento in call site che TD-31 non riguarda |
| `CREATE INDEX agents_status_idx` | Non c'era un indice su `active`: aggiungerne uno sarebbe un cambiamento di prestazioni infilato dentro un cambiamento di rappresentazione. `projects_status_idx` esiste, `agents_status_idx` no, e TD-31 parla del booleano contro l'enum |
| Un terzo stato | Non richiesto da nessun requisito. ADR-004 §2: allargare costa una migrazione, ed è intenzionale |
| Usare `ARCHIVED` | §3 |
| `repair()` sullo stream dello schema | §5. Là il fallimento rumoroso è il comportamento voluto |

## 8. Conseguenze operative

- Lo schema passa a **`V8`**. Il test sulle coppie consecutive di TASK-006 copre `V7 → V8`
  **senza che nessuno aggiunga un caso**.
- **`V8` è la prima migrazione distruttiva dello stream.** Un database che la esegue non torna a
  `V7` eseguendo SQL al contrario.
- Un database con uno `status` fuori vocabolario è impossibile da `V8` in poi, per costruzione.
- `TABLES_AT_HEAD` non cambia: `V8` non crea tabelle.
- Il contratto HTTP, gli ETag, il protocollo di lock e le precondizioni **non sono toccati**:
  `agents.version` resta la colonna di `V5`, e nessun percorso di scrittura cambia forma.
- **TD-31 chiuso.**
