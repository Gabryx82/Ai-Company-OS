# ADR-047 — Il diario degli agenti e le modifiche al codice

- **Stato**: Implementata, in attesa della review umana; non committata
- **Data**: 2026-11-13
- **Decisa da**: agente, su direttiva dell'operatore del 2026-11-13 («Non vedo effettivamente gli agenti lavorare, 0% a
  lavoro […] la task 17 su cui ora sta lavorando un'altra sessione, mi da che è già stata completata in ufficio 3D. Io
  voglio vedere l'agente che inizia a lavorare e me lo segna in ufficio3D, passo passo registra le sue azioni ogni
  agente»; scelta: «Diario completo»; poi «per il codice in tempo reale crea un sistema simile a quello già presente
  nell'app desktop di claude dove posso vedere le modifiche fatte al codice»)
- **Estende**: ADR-021 §4 e ADR-025 §4 (la consegna senza API), ADR-041 §10 (l'esito in un file accanto alla task).

## 1. Che cosa succedeva

Letto in sola lettura dal database dell'operatore: TASK-017 (id 52) START via API alle 10:59:31 UTC, consegnata a
`claude-desktop` alle 11:00:12, **COMPLETE via REVIEW** (ACCEPTED) alle 11:00:38, 26 s dopo, dal pulsante primario
«Accetta e completa» che il pannello dell'orchestratore offre appena una task è in corso. Nessuna task era IN_PROGRESS
(13 OPEN, 17 DONE): l'Ufficio 3D, che conosce solo lo stato del control plane, diceva il vero. Il problema era altrove:

- una sessione esterna (Claude Desktop, Claude Code, Codex…) non riferisce nulla al control plane mentre lavora; l'esito
  arriva solo alla fine, in `TASK-NNN.esito.md` (ADR-041 §10);
- il pulsante che chiude la task era l'azione principale anche quando l'agente non aveva ancora consegnato nulla;
- «Codice in tempo reale» mostrava i file toccati di recente, non che cosa era cambiato in ciascuno.

## 2. Decisione

### 2.1 Il diario dell'agente

Una consegna crea, accanto al documento della task, **`TASK-NNN.diario.md`** (`TASK.diario.md` nell'inbox di una task
senza progetto), se manca: non si riscrive mai, una seconda consegna della stessa task continua lo stesso diario. Il
pacchetto (`## Mentre lavori: il diario`), il prompt completo, la governance dell'inbox e i file di regole dei target
chiedono all'agente di **aggiungere in fondo una riga per passo**, appena comincia e a ogni passo (un file letto, una
decisione, un file creato o modificato, un comando o un test, un problema), con l'ora presa dal sistema:

```
- 2026-11-13T12:04:05+01:00 · Letto tasks/TASK-017.md e la fase 4
```

`DiaryFormat` legge gli elementi di lista; l'ora si accetta nelle forme comuni (ISO-8601 con o senza fuso, spazio al
posto della T, la sola ora del giorno, fra parentesi quadre) seguita da `·`, `—`, `-`, `|` o `:`. Un passo senza ora
prende quella del passo prima (la consegna per i primi), l'ultimo quella del file se più recente, ed è segnato
`approximate`. Si tengono gli ultimi 300 passi, 400 caratteri ciascuno. `WorkspacePaths` ammette `TASK.diario.md` fra i
file scrivibili dell'inbox.

### 2.2 Il diario di ogni agente: `GET /api/journal?agentId=&limit=`

`AgentJournal` riunisce, dal più recente: le consegne («Consegnata a Claude Code (app desktop): la task è in corso»),
le transizioni che consegne e review non dicono già (START via API o RUN, COMPLETE via API, STOP, REOPEN via API), le
review, le esecuzioni del motore AI (in coda, al lavoro, risposta o errore), i passi del diario e l'esito scritto. Una
voce dice agente, task (codice del piano), tipo, testo, chi l'ha fatta, e se l'ora è stimata. Senza `agentId`, tutti gli
agenti con una task; `limit` da 1 a 500 (100 di default). Solo letture; i file si leggono solo per le task consegnate.

### 2.3 Le modifiche al codice: `GET /api/tasks/{id}/changes` e `GET /api/tasks/{id}/changes/file?path=`

Git non basta: dei 47 progetti dell'operatore solo 8 sono repository, «Interfaccia MoviesESeries» no. **A ogni
consegna, prima che lo strumento si apra**, `WorkChanges` copia i file di testo della cartella in
`<aicos.home>/snapshots/task-<id>/<copia>/` (con il manifesto per ultimo: una copia senza è incompleta e non si legge),
saltando ciò che salta il grafo del codice (`node_modules`, `.git`, `target`, `dist`, `.aicos`…), i binari, i file oltre
512 KB e il diario; al massimo 4000 file e 64 MB, oltre si dice «confronto parziale». Salvata la consegna, la copia vi
si lega con un marcatore `handoff-<id>-<millis>` e le copie precedenti della task vanno via. Le modifiche sono la
cartella adesso contro la copia della **consegna più recente**: file aggiunti, modificati, eliminati, con le righe in
più e in meno, il più recente per primo; il file scelto riga per riga (`LineDiff`: Myers dopo le righe comuni ai due
capi, blocchi con 3 righe di contesto come un diff unificato; oltre 2000 modifiche, o 20000 righe, il confronto non si
cerca oltre). Un file che non è cambiato, o un percorso che esce dalla cartella, è 404. Nella cartella non si scrive mai.
Se un file è testo si ricorda per versione (percorso, dimensione, data): la cartella guardata ogni pochi secondi non
si rilegge per intero, solo ciò che è nuovo o cambiato.

Una copia è letta **solo per la consegna a cui è legata**: con un database rifatto gli id delle task tornano, e una
copia rimasta di un'altra task con lo stesso id veniva presa per buona (visto nei test). E le copie non si rinominano:
su Windows la rinomina di una cartella appena scritta falliva.

### 2.4 Nell'Ufficio 3D

- Il pannello dell'agente si apre su **Diario**: le sue voci, la più recente in alto, i passi del diario detti tali,
  l'ora stimata con «≈». **Modifiche al codice** mostra file e diff come l'app desktop di Claude (lettera A/M/D, `+N −M`,
  il diff con i numeri di riga vecchi e nuovi); segue l'ultimo file toccato finché l'operatore non ne sceglie uno.
  Senza una copia (una task consegnata prima di questa versione, o un server precedente) restano i file toccati di
  recente, con la nota che le modifiche riga per riga arrivano dalla prossima consegna.
- Sul cartellino dell'agente, sotto il nome, la sua ultima voce della mezz'ora, che entra con una breve dissolvenza
  (niente con «riduci movimento»); il cartellino resta fermo come in PHASE 37 (navigazione).
- **Diario dell'ufficio** al posto di «Attività recenti»: le ultime dodici voci di tutti; il nome porta all'agente.
- Diario e modifiche si rileggono ogni 4 e 3 secondi mentre la scheda è visibile; una risposta uguale non ridisegna.

### 2.5 «Accetta e completa»

Finché la task non ha un esito (`/api/tasks/{id}/outcomes` vuoto) il pulsante **non è l'azione principale** e **chiede
conferma**, dicendo che la task si chiude anche se lo strumento ci sta ancora lavorando; una riga ricorda di seguire il
diario nell'ufficio. Con l'esito torna principale e completa subito. Lo stesso vale per **«Completa»** in testa al
pannello della task (la transizione diretta, trovata nella prova dal vivo: era anch'essa il pulsante principale). Il
backend non cambia: l'operatore può sempre completare.

## 3. Che cosa resta fuori

- Il diario dipende dall'agente che segue le istruzioni del pacchetto: un agente che non lo scrive mostra solo le voci
  del control plane e le modifiche ai file, che non dipendono da lui.
- Le esecuzioni del motore AI non fanno copie: non scrivono nella cartella.
- Le copie occupano disco in `<aicos.home>/snapshots`, una per task (l'ultima consegna); non si cancellano con la task.

## 4. Contratto

Additivo: `GET /api/journal`, `GET /api/tasks/{id}/changes`, `GET /api/tasks/{id}/changes/file`; schemi `JournalEntry`,
`TaskChanges`, `ChangedFile`, `FileDiff`, `DiffHunk`, `DiffLine`. Proprietà `aicos.snapshots.root` (default
`${aicos.home}/snapshots`).

## 5. Test

`DiaryFormatTest` (4), `LineDiffTest` (5), `AgentJournalApiTest` (2: la consegna, i passi, l'esito e la review nel
diario; le modifiche file per file e riga per riga, una copia di un altro database ignorata, una nuova consegna che
riparte da zero), `HandoffAnywhereApiTest` (il diario dell'inbox); nella console `OfficeJournal.test.tsx` (4) e tre test
del pannello della task. Visti rossi prima del verde: i parser contro uno stub, il pulsante contro la versione
precedente, il passo sul cartellino e la vista delle modifiche togliendo la riga che li collega.
