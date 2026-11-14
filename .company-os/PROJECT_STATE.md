# AI Company OS — Project State

> **Fonte primaria dello stato.** Una sessione nuova, senza cronologia di chat, deve poter
> leggere questo file più `AUTONOMOUS_CHARTER.md` e `AUTONOMOUS_LOOP.md` e continuare il loop.

## Project
AI Company OS

## Modalità di lavoro
**Autonomous Project Mode con Human Final Review**, attiva dal 2026-09-14.
Autorità e hard stop: `.company-os/AUTONOMOUS_CHARTER.md`. Procedura: `.company-os/AUTONOMOUS_LOOP.md`.
L'agente definisce, implementa, revisiona e chiude le task senza approvazione intermedia.
`master` è il gate umano finale e non si tocca.

## Status
**PHASE 1 e PHASE 2: ACCETTATE DALLA REVIEW UMANA e INTEGRATE IN `master` il 2026-09-19.**

Il gate del charter §8 è stato attraversato: un umano ha accettato il lavoro e ha autorizzato il
merge. `master` è passato da **`d5ff121`** a **`6dc5989`** con **due fast-forward consecutivi** —
prima `autonomous/phase-1-foundations` (`0a35ac0`), poi `autonomous/phase-2-assignment`
(`6dc5989`) — **41 commit, nessun merge commit, nessuna riscrittura di storia**.

| Fase | Integration branch | Merge in `master` | Suite dopo il merge |
|---|---|---|---|
| **PHASE 1 — Foundations** | `autonomous/phase-1-foundations` (`0a35ac0`) | ✅ fast-forward | **158/158 verdi** |
| **PHASE 2 — Assignment** | `autonomous/phase-2-assignment` (`6dc5989`) | ✅ fast-forward | **219/219 verdi** |

Handoff di review: `docs/handoff/FINAL_HANDOFF_PHASE_1.md` (PHASE 1, conservato e non sostituito)
e `FINAL_HANDOFF.md` (PHASE 2).

Stato corrente **dopo TASK-012**: suite **223 test verdi**, **stream di migrazione a `V8`** (per
la versione del database locale vedi «Stato del sistema»), nessun failure aperto. **`origin` è
configurato dal 2026-09-20, ma nulla è ancora stato pushato** (TD-14). I numeri nella tabella qui
sopra sono quelli **al momento dei merge** e restano com'erano.

**I due integration branch restano dove sono**, fermi ai rispettivi tip: sono i marcatori
storici di che cosa conteneva ciascuna fase, e farli avanzare li renderebbe falsi.

L'obiettivo della fase — *il Company OS sa dire chi lavora su che cosa, e due client non possono
sovrascriversi in silenzio mentre lo dicono* — è **raggiunto in entrambe le metà**, e il criterio
di chiusura di `PHASE_2_PLAN.md` §4 è adesso soddisfatto in **tutte e tre** le condizioni: la
terza chiedeva un `FINAL_HANDOFF` aggiornato, e TASK-011 lo ha scritto.

**Il charter §8 è stato rispettato**: l'agente si è fermato e ha preparato l'handoff; il merge è
stato autorizzato esplicitamente da un umano e solo allora eseguito.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37, seguito — La scheda Documenti con esiti e diari a parte — 2026-11-14

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue. Solo console; nessun ADR nuovo,
> nessun cambio del contratto API.

- **Direttiva** dell'operatore del 2026-11-14: «Date le ultime modifiche alla documentazione di AI-COMPANY-OS, la
  scheda documenti di un progetto organizzala dividendo in sezioni per ogni categoria di documento: come hai diviso
  fasi, tasks e handoffs, aggiungi anche la sezione per gli esiti delle task e per i documenti di diario». Stesso
  branch e working tree; **nessun commit**.
- **Causa**: il control plane elenca in `Documents.tasks` tutto ciò che sta in `tasks/`, e da PHASE 35 (ADR-041 §10)
  e PHASE 37 (ADR-047) lì accanto a `TASK-NNN.md` ci sono anche `TASK-NNN.esito.md` e `TASK-NNN.diario.md`: la sezione
  «Task» li mescolava in ordine alfabetico (diario, esito, task di ogni numero).
- **Fatto** (`frontend/src/views/ProjectDetail.tsx`, `Documents`): sezioni Progetto, Fasi, Task, **Esiti**, **Diari**,
  Handoff. Esiti e diari si riconoscono dai suffissi `.esito.md` e `.diario.md`, gli stessi che scrivono
  `PlanMarkdown.outcomePath` e `diaryPath`; ogni altro file di `tasks/` resta in «Task». Ogni sezione è una `section`
  con il suo nome accessibile. Apertura ed eliminazione invariate (esiti e diari sono sotto `tasks/`, quindi
  eliminabili come prima). Il contratto resta com'è: dividere l'elenco è una scelta di presentazione.
- **Test visti rossi**: `frontend/src/views/ProjectDocuments.test.tsx`, 3 test (l'ordine delle sezioni e i file di
  ciascuna; un esito si apre e si elimina dalla sua sezione; senza esiti né diari le due sezioni dicono «—»). Prima della
  modifica fallivano tutti e tre perché le sezioni Esiti e Diari non esistevano.
- **Verifica**: anteprima temporanea su Vite 5193 con la vera `ProjectDetail` e un server finto (4 task, 2 esiti,
  3 diari, 2 fasi, 2 handoff), poi rimossa con la sua voce in `.claude/launch.json`: sei sezioni nell'ordine atteso
  con i file giusti, intestazioni identiche alle altre, un diario si apre con il pulsante Elimina, nessuno scorrimento
  orizzontale. Nessuna schermata: la finestra dell'app era ridotta a icona e la pagina non si disegnava. Non provata
  sulla console reale dell'operatore (chiede l'accesso).
- **Notato, innocuo, non fatto**: `GeneratedTextUpgrades.isTaskDocument` esclude `.esito.md` ma non `.diario.md`, quindi
  l'aggiornamento dei testi generati e la prontezza (`ReadinessService`) passano anche sui diari. Non cambia nulla: i
  due schemi sostituiti sono intestazioni di un documento di task («## Prompt compatto», «## Come si consegna») che un
  diario non contiene. Da allineare la prossima volta che si tocca quel file.
- **Suite** (2026-11-14): console **238 su 238** (235 + 3), typecheck pulito. Backend ed engine non toccati.
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37, seguito, in `master` e su `origin` — 2026-11-14

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue: le tre sezioni qui sotto
> (Impara, diario degli agenti, navigazione dell'Ufficio 3D) sono integrate e pushate.

- **Richiesta dell'operatore** del 2026-11-14: «Committa tutto le ultime modifiche sul branch della fase, mergia su
  Master/Origin e infine pusha» (charter §8: merge e push autorizzati esplicitamente).
- **Commit** su `autonomous/phase-37-one-ecosystem`, per area, sopra `27107db`: `76278ed` control plane e contratto
  OpenAPI (ADR-047, pacchetto `journal/`), `f973def` console (diario e modifiche nell'ufficio, navigazione, Impara),
  `ecb36b8` ADR-047, RUNNING, README e stato. Restano fuori, non tracciate, le schermate `.impeccable/review/*.jpg`,
  come nel merge precedente.
- **Suite** prima del merge: Java **949 (0 falliti, 3 saltati: link simbolici su Windows)** in un worktree a parte
  su `ecb36b8` (il backend dell'operatore gira da `backend/`), poi rimosso; console **235 su 235** e typecheck pulito
  sullo stesso codice; engine non toccato dal merge precedente, non rieseguito.
- **Merge**: fast-forward di `master` da `27107db` a `ecb36b8`, senza cambiare branch, nessun merge commit, nessun
  force; `origin/master` non si era mosso. **Push** di `master` e del branch di fase.
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.
- **Prossimo passo**: nessuno in sospeso; si attendono nuove direttive.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37, seguito — La pagina Impara senza scritte sovrapposte — 2026-11-14, integrata in `master` e pushata il 2026-11-14

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue. Solo console; nessun ADR nuovo.

- **Direttiva** dell'operatore del 2026-11-14: «Ho aperto la scheda impara, ci sono scritte sovrapposte da aggiustare e
  rendere più pulito il design della pagina». Stesso branch e working tree; **nessun commit**. Toccati solo
  `frontend/src/views/Learn.tsx`, `Learn.test.tsx` e le regole di Impara in `styles.css` (sezione ADR-044; nel vecchio
  blocco `@media (max-width: 900px)` resta solo la riga di `.eco-report`).
- **Causa** (misurata, non a occhio: ogni riga di testo contro testo e controlli, tolto ciò che uno scroller taglia):
  nella lezione la colonna delle tappe era una griglia dimensionata sul contenuto, con l'etichetta `nowrap` senza
  `min-width: 0`: una tappa dal titolo lungo («Passo 2 — Una richiesta sola, anche quando…») usciva dai 250 px e si
  scriveva sopra la card del passo, in ogni tappa, a 1100 e a 1440 px. Con il pannello File aperto (734 px di pagina in
  una finestra di 1280) le media query sulla finestra tenevano le due colonne e il testo passava sotto la card. A
  telefono il percorso del file del passo allargava la pagina di 48 px.
- **Fatto**: colonna delle tappe `minmax(0, 1fr)`, tappe su due righe al massimo con il nome intero nel tooltip;
  container query sulla larghezza della pagina (`.learn`) e non della finestra; il percorso del file va a capo. Home
  ripulita con le classi che c'erano: le tre cifre con il riquadro di `widget-icon`; «Riprendi da dove eri» sopra il
  titolo della lezione, che diventa il titolo della card; i progetti in `card-head` con «N di M lezioni finite»; righe
  con il titolo su due righe, le barre allineate (a telefono sotto il titolo invece di sparire), «fuori formato» come
  badge, «Rivedi» in grigio e «Continua»/«Inizia» in blu; «← Impara» sopra il titolo come «← Progetti»; lo stato vuoto
  largo come le altre card; la spunta delle lezioni finite leggibile nel tema chiaro.
- **Comportamento corretto, con test visti rossi**: nelle lezioni vere `**grassetto**` e `*corsivo*` comparivano come
  asterischi, e nei concetti finali e sul retro delle carte anche i backtick: `inline()` li rende, mai dentro il codice.
  Nella pagina stretta la tappa corrente restava fuori vista nella barra che scorre di lato: ora la barra scorre fino a
  lei, la pagina no. 4 test nuovi in `Learn.test.tsx`.
- **Verifica**: anteprima temporanea con dati finti presi da una lezione vera (Vite su 5192, rimossa con la sua voce
  in `.claude/launch.json`): nessuna sovrapposizione né scorrimento orizzontale su home, ogni tappa della lezione e
  ripasso a 1600, 1440, 1100, 960, 820, 760, 520 e 320 px, e con il pannello File a 1280 e 1600; temi scuro e chiaro.
  Schermate prima e dopo mostrate all'operatore. Non provata sulla console reale dell'operatore.
- **Suite** (2026-11-14): console **235 su 235**, typecheck pulito.
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37, seguito — Il diario degli agenti e le modifiche al codice — 2026-11-13, integrata in `master` e pushata il 2026-11-14

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue. Decisioni in
> `docs/adr/ADR-047-agents-journal-and-changes.md`.

- **Direttive** dell'operatore del 2026-11-13: «Io voglio vedere l'agente che inizia a lavorare e me lo segna in
  ufficio3D, passo passo regitra le sue azioni ogni agente» (scelta: «Diario completo»); «per il codice in tempo reale
  crea un sistema simile a quello già presente nell'app desktop di claude dove posso vedere le modifiche fatte al
  codice». Stesso branch e working tree; **nessun commit**.
- **Diagnosi** (database dell'operatore, sola lettura): TASK-017 (id 52) consegnata a `claude-desktop` alle 11:00:12 UTC
  e chiusa 26 s dopo da «Accetta e completa» (review ACCEPTED); lo 0% al lavoro era vero (nessuna task IN_PROGRESS).
  Per riprenderla: «Riapri con modifiche» e una nuova consegna. Il database dell'operatore non è stato toccato.
- **Fatto** (ADR-047): la consegna crea `TASK-NNN.diario.md` e chiede all'agente una riga per passo con l'ora; prima di
  aprire lo strumento copia i file di testo della cartella in `<aicos.home>/snapshots/task-<id>/`, legata alla
  consegna. `GET /api/journal`, `GET /api/tasks/{id}/changes`, `GET /api/tasks/{id}/changes/file` (pacchetto
  `backend/.../journal/`). Ufficio 3D: schede «Diario» e «Modifiche al codice» (file A/M/D con +/−, diff unificato con
  i numeri di riga, come l'app desktop di Claude), ultima voce sotto il nome sul cartellino, «Diario dell'ufficio» al
  posto di «Attività recenti». «Accetta e completa» e «Completa» senza esito non sono l'azione principale e chiedono
  conferma (il secondo trovato nella prova dal vivo). `TASK.diario.md` fra i file scrivibili dell'inbox.
- **Trovato dai test e corretto**: una copia indicizzata solo per id della task veniva letta per un'altra task con lo
  stesso id (database rifatto): ora una copia vale solo per la sua consegna (id e istante). La rinomina atomica della
  cartella della copia falliva su Windows: ora la copia si scrive in posto, manifesto per ultimo. `LineDiff.Result`
  pubblico toglieva la nullabilità allo schema `Result` del contratto (omonimia): rinominato `DiffResult`.
- **Smoke dal vivo** (backend 8101 su `aicompany_p47j`, console 5187, home e progetti nello scratchpad, launcher
  spento): consegna a Claude Desktop non aperta; l'agente simulato scrive tre passi, modifica `cart.js`, crea
  `discount.js`, cancella `README.md`, scrive l'esito. Nell'ufficio: cartellino con l'ultimo passo, Diario con le voci e
  le ore, Diario dell'ufficio aggiornato da solo, 4 file cambiati con diff riga per riga (tema scuro e chiaro), i due
  pulsanti di completamento non principali con la conferma. Il diario scritto da un vero Claude Desktop non è provato:
  dipende dall'agente che segue il pacchetto.
- **Suite** (2026-11-13): console **231 su 231**, typecheck pulito (7 test nuovi); backend Java **949 (0 falliti, 3 saltati: i link simbolici)** in un worktree a parte (il backend dell'operatore gira da
  `backend/`), 11 test nuovi; dopo, la cache del «è testo?» in `WorkChanges` verificata con `AgentJournalApiTest`; contratto
  rigenerato (solo aggiunte). Visti rossi: i parser contro uno stub, i pulsanti contro la versione precedente, il passo sul
  cartellino e la vista delle modifiche togliendo la riga che li collega, la copia di un altro database.
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37, seguito — Muoversi nell'Ufficio 3D, e chi lavora davvero — 2026-11-13, integrata in `master` e pushata il 2026-11-14

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue. Solo console; nessun ADR nuovo.

- **Direttiva** dell'operatore del 2026-11-13: «Nella scheda ufficio 3D migliora il sistema di navigazione visuale come
  fatto per la second brain e per ecosistema 3D, inoltre il cartello tag del nome dell'agente nell'ufficio 3D non deve
  muoversi o fare altro mentre muovo la visuale». Branch `autonomous/phase-37-one-ecosystem`, working tree; **nessun commit**.
- **Navigazione** come in Ecosistema 3D: i controlli condivisi di `GraphNav` (avvicina, allontana, inquadra; viste
  panoramica/dall'alto/laterale al posto delle due schede in testa; rotazione automatica; scorciatoie con `?`), percorso
  «Ufficio › agente» con Indietro, tastiera sulla scena (frecce, Maiusc + frecce, `+ −`, `F`, `1 2 3`, `N`/Maiusc + `N`
  tra gli agenti, Invio, Backspace, `R`), zoom verso il puntatore, tasto destro che sposta **sul pavimento**, due dita
  di lato, doppio clic su un agente per raggiungerlo e sul pavimento per inquadrare tutto, la squadra a destra che porta
  la camera alla scrivania. La vista non esce dall'ufficio. Funzioni pure in `views/office/officeCamera.ts`.
- **Cartellini**: erano messi a ogni frame *prima* che OrbitControls muovesse la camera e con le sue matrici vecchie,
  quindi restavano un frame indietro e scivolavano mentre la vista girava; erano agganciati alla testa, che oscilla;
  sotto il puntatore in trascinamento si accendevano e spegnevano; intercettavano rotella e clic. Ora sono etichette
  ferme sopra la sedia, messe dopo il movimento con la camera aggiornata, a pixel interi, senza dissolvenze né hover
  durante un gesto; il puntatore le attraversa (un clic su un cartellino sceglie ancora l'agente).
- **Diagnosi chiesta dall'operatore** («0% al lavoro», «la task 17 risulta già completata»), letta in sola lettura dal
  database: TASK-017 (id 52) START via API alle 10:59:31 UTC, consegnata a `claude-desktop` alle 11:00:12, **COMPLETE
  via REVIEW (ACCEPTED, gabryx82) alle 11:00:38**, 26 s dopo, dal pulsante primario «Accetta e completa» del pannello
  dell'orchestratore, offerto appena la task è in corso. Nessuna task è IN_PROGRESS (13 OPEN, 17 DONE): l'ufficio,
  che conosce solo lo stato del control plane, dice il vero. Una sessione esterna non riferisce nulla al control plane
  (tranne OpenCode seguibile); l'esito arriva solo alla fine, in `TASK-NNN.esito.md`.
- **Diario degli agenti e modifiche al codice**: chiesti a metà di questa sezione, interrotti dal limite d'uso e
  completati: vedi la sezione sopra (ADR-047).
- **Verifica**: anteprima temporanea su dati finti (Vite, tema scuro e chiaro, rimossa): cartellini sopra le teste anche
  durante l'inerzia di un trascinamento, vista dall'alto, «vai a» dalla squadra. Non provata sulla console reale né su
  un touchpad vero.
- **Suite** (2026-11-13): console **224 su 224**, typecheck pulito; 8 test nuovi (5 della camera, 3 della vista), visti
  rossi prima: il cartellino messo con le matrici vecchie sbagliava di 866 px. Backend ed engine non toccati.
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 34–37 in `master` e su `origin` — 2026-11-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue: dove le sezioni di PHASE 34–37
> dicono «nessun commit» o «non committata», descrivono com'era prima di questa chiusura.

- **Richiesta dell'operatore** del 2026-11-13: «Fai il commit sul branch della fase, poi mergia con master/origin e
  infine pusha» (charter §8: merge e push autorizzati esplicitamente).
- **Commit** su `autonomous/phase-37-one-ecosystem`, per area, sopra `6a50288` (PHASE 33): `af05370` control plane e
  contratto OpenAPI (V42–V46), `4d4d850` AI Engine, `c61012f` console, `3b753b5` ADR-041–046, governance, contesto di
  design, RUNNING, README, debito e stato; `0a6a478` la correzione qui sotto. Restano fuori, non tracciate, le
  schermate `.impeccable/review/*.jpg`: mostrano la console dal vivo dell'operatore.
- **Correzione trovata prima del push**: in un checkout pulito di `master` su Windows (`core.autocrlf=true`) i
  template `backend/src/main/resources/workspace-templates/*.md` diventavano CRLF e tre test andavano rossi
  (`HandoffAnywhereApiTest` ×2, `ProjectWorkspaceApiTest`): `@AGENTS.md` e `@./AGENTS.md` non stavano più su una riga
  chiusa da `\n`. Nel working tree erano LF solo perché git non li aveva ancora toccati. Un `.gitattributes`
  (`text eol=lf`) li tiene LF in ogni checkout; i blob erano già LF.
- **Merge**: fast-forward di `master` da `6a50288` a `0a6a478`, nessun merge commit, nessun force; `origin/master`
  non si era mosso. **Push** di `master` e del branch di fase (nuovo su `origin`, tracciato).
- **Suite** sul `master` pulito, in un worktree a parte (il backend dell'operatore gira da `backend/`): Java **938
  (0 falliti, 3 saltati: link simbolici su Windows)**; engine **82**; console **216 su 216**, typecheck pulito.
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.
- **Prossimo passo**: nessuno in sospeso; si attendono nuove direttive.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37, seguito — Due grafi più chiari, e come muoversi dentro — 2026-11-13, integrata in `master` e pushata il 2026-11-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue. Solo console; nessun ADR nuovo:
> la scena resta quella di ADR-037, cambiano aspetto e navigazione.

- **Direttiva** dell'operatore del 2026-11-13: «Rendi più realistico e chiaro, tendente a colori tenui e trasparenti il
  grafo in ecosistema 3D. Fai lo stesso per la visione a grafo della second brain. Migliora anche il sistema di
  navigazione delle due schede». Stesso branch e working tree; **nessun commit**.
- **Aspetto**: una palette pastello condivisa (`CATEGORY` in `ecosystemModel.ts` e `NODE_STYLE` in `SecondBrain.tsx`,
  stessi colori per agente, modello, skill…). 3D: vetro traslucido (`MeshPhysicalMaterial` con clearcoat e bordo
  fresnel) illuminato da un `RoomEnvironment`, bolle trasparenti al posto dei wireframe, archi con alfa per vertice,
  pavimento a griglia polare, sfondo a foschia dipinto dalla scena (un canvas trasparente in `frameloop="demand"`
  tornava vuoto: verificato in headless), tone mapping Neutral, etichette smerigliate che si attenuano fuori dal focus.
  Second Brain: sfere traslucide pre-renderizzate per colore, archi tinti, griglia a puntini, dissolvenze dolci.
- **Navigazione** (componente condiviso `components/GraphNav.tsx`): controlli sulla mappa (avvicina, allontana,
  inquadra; nel 3D viste frontale/alto/laterale e rotazione; nel Second Brain «solo i vicini» e «ridisponi»), percorso
  degli elementi scelti con Indietro, tastiera con pannello delle scorciatoie (`?`), doppio clic sul vuoto per
  inquadrare. 3D: zoom verso il puntatore, camera che continua da dove sta andando. Second Brain: zoom verso il
  puntatore, inquadratura automatica, minimappa cliccabile, ricerca con risultati, collegamenti cliccabili raggruppati,
  vista locale a due passi, posizioni conservate quando cambiano i filtri.
- **Dita** (seconda direttiva dello stesso giorno: «spostarsi sull'asse x tramite le dita, destra e sinistra, in
  entrambe le schede»): due dita a destra o a sinistra sul touchpad spostano la vista lungo x, la mappa segue le dita
  (col mouse, Maiusc + rotella); su e giù restano lo zoom, il pinch del touchpad pure. Nel 3D l'evento è preso prima
  di OrbitControls e sposta camera e bersaglio insieme, 1:1 alla profondità del bersaglio; sul touchscreen le due dita
  di OrbitControls spostavano già. Nel Second Brain due dita sul touchscreen trascinano e ingrandiscono. Lo
  scorrimento laterale non arriva al browser (non torna alla pagina precedente). Funzioni pure in
  `components/graphGestures.ts`.
- **Verifica**: anteprima temporanea su dati finti in Chrome headless (SwiftShader), poi rimossa; la console reale non
  è stata aperta (serve l'accesso dell'operatore). I gesti con le dita sono coperti dai test delle funzioni pure, non
  provati su un touchpad vero.
- **Suite** (2026-11-13): console **216 su 216**, typecheck pulito; 10 test nuovi (2 nel 3D, 4 nel primo
  `SecondBrain.test.tsx`, 4 dei gesti), visti rossi togliendo la funzione che proteggono. Backend ed engine non toccati.
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37, seguito — Un ecosistema che non fa aspettare — 2026-11-12, integrata in `master` e pushata il 2026-11-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue. Decisioni in
> `docs/adr/ADR-046-ecosystem-without-waiting.md`.

- **Direttiva** dell'operatore del 2026-11-12: «L'ecosistema è lento, mostra gli agenti in modo troppo lento. Velocizza
  le integrazioni e l'ecosistema openWebUI, LiteLLM, Ollama». Stesso branch e working tree; **nessun commit**.
- **Misurato prima di cambiare**: la scheda Ecosistema aspettava `openclaw agents list` (28 s; 12–58 s a comando, con o
  senza gateway), di nuovo a ogni scadenza della lista (10 s); il catalogo ricaricava il menu Start con PowerShell ogni
  60 s dentro la richiesta (~2 s) e cercava ogni comando su disco a ogni lettura; Open WebUI ripartiva a ogni visita.
  Open WebUI, LiteLLM, Ollama e il gateway rispondono in 1–40 ms: non erano loro.
- **Fatto**: lettura degli agenti di OpenClaw in background, condivisa da Ecosistema e pagina OpenClaw (stato
  `READING`, `checkedAt`, `refreshing`; contratto additivo); strumenti chiesti insieme; rilevamento con servizi in
  parallelo, menu Start tenuto 10 min e rinnovato dietro, posizioni dei programmi tenute 60 s; riscaldamento all'avvio
  (`aicos.warm-up`); app incorporate tenute caricate. Una lettura iniziata prima di una pubblicazione non la cancella.
- **Smoke dal vivo** (8099/5184, OpenClaw vero in sola lettura): Ecosistema 28 s → **258 ms** dalla navigazione alle
  righe; pagina OpenClaw 12–60 s → **184 ms** (20 agenti); `/api/software` a caldo 0,2–0,37 s → **27–46 ms**; Open
  WebUI in Chat LLM resta la stessa iframe uscendo e rientrando.
- **Non toccato**: i tempi di Ollama sono quelli dei modelli nell'AI Engine (il `keep_alive` per modello c'è già); con
  9–13 GB di RAM libera tenerli caricati più a lungo è una scelta dell'operatore.
- **Suite** (2026-11-12): Java **934 (0 falliti, 2 saltati: i link simbolici)**; console **201 su 201**, typecheck
  pulito; engine non toccato. Viste rosse prima del verde: 6 nel backend, 2 nella console.
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37, seguito — OpenClaw dentro la console, LM Studio fuori — 2026-11-12, integrata in `master` e pushata il 2026-11-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue. Decisioni in
> `docs/adr/ADR-045-openclaw-in-the-console.md`.

- **Direttiva** dell'operatore del 2026-11-12: «elimina LM Studio come voce dato che non è installato sul pc e integra
  l'interfaccia di OpenClaw in AI-COMPANY-OS». Stesso branch `autonomous/phase-37-one-ecosystem`, stesso working tree
  non committato di PHASE 34–37. **Nessun commit**: non è stato chiesto.
- **LM Studio fuori**: tolti `LmStudioConnector`, la voce `lm-studio` di `software.json` e le menzioni nella console;
  la sincronizzazione ha tre strumenti. **V46** toglie la riga dai database già riempiti (quello dell'operatore l'ha, id
  42) se nessun agente la usa. **TD-100 chiuso** senza lavoro. ADR-043 rivista (§2, §4, §7).
- **OpenClaw dentro la console**: la Control UI **non si può incorporare** (`X-Frame-Options: DENY`,
  `frame-ancestors 'none'`, non configurabile; misurato sul gateway dell'operatore). Un proxy che tolga le intestazioni
  è scartato. Nuova pagina **OpenClaw** (barra laterale, accanto a «Chat LLM», e tessera in Integrazioni): gateway e suo
  avvio, agenti col legame all'agente di AI Company OS, conversazione con un agente (sessione `aicos-console`), sessioni
  recenti con il numero della task, «Riprendi» in `openclaw tui`, «Apri la Control UI» con `openclaw dashboard` (si
  accoppia da sé: il token non passa dal control plane). Backend: pacchetto `openclaw/`, rotte `/api/openclaw/**`
  (`POST` solo admin), problema `openclaw-item-not-found`.
- **Lentezza misurata**: un comando di OpenClaw impiega 30–60 s anche con il gateway acceso (`agents list` 58 s,
  `sessions` 57 s): letture in background tenute 30 s, risposte raccolte in background, la pagina chiede ogni 3 s.
- **Console**: il limite delle attese di Testing Library portato a 5 s in `src/test/setup.ts` (un test non toccato era
  fallito una volta a 1,17 s nella suite intera).
- **Suite** (2026-11-12): Java **927 (0 falliti, 2 saltati: i link simbolici)**; console **197 su 197**, typecheck
  pulito; engine non toccato. Mutazioni viste rosse: 6 nel backend, 2 nella console, più V46 rossa prima di scriverla.
- **Smoke dal vivo isolato** (backend 8099, console 5184, database `aicompany_p45`, launcher spento; OpenClaw **vero**
  in sola lettura): V46 applicata, nessun `lm-studio`; la pagina risponde subito e in circa un minuto mostra i **20
  agenti** reali (il predefinito per primo, 17 «da qui») e le **2 sessioni** reali con modello ed etichetta; la tessera
  in Integrazioni porta alla pagina; la scheda Ecosistema non nomina più LM Studio; a 375 px nessuno sforamento.
  Durante lo smoke Docker Desktop era chiuso: riaperto, i tre container dell'operatore sono ripartiti.
- **Non verificato dal vivo**: una conversazione vera con un agente (userebbe l'abbonamento dell'operatore), l'apertura
  della Control UI con `openclaw dashboard` (accoppierebbe un browser come amministratore), `openclaw tui`.
- **Per usarla**: riavviare il backend (applica V46) e ricaricare la console; gateway acceso (`openclaw daemon start`).
- **Debito**: nessuno aperto. Prossimo id libero **TD-101**.

## ▶▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 37 — Un ecosistema solo, e imparare facendo — 2026-11-10/11, integrata in `master` e pushata il 2026-11-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

- **Direttive** dell'operatore del 2026-11-10, in quattro messaggi: integrare OpenClaw (appena installato) nel workflow
  e migliorare creazione e uso di agenti e skill e l'interfaccia; sincronizzare gli agenti («un unico ecosistema»);
  esperienza semplice e apprendimento **attivo** — il codice lo scrive l'agente, con spiegazione riga per riga, e
  l'operatore lo copia nei file; l'agente lavora in autonomia quando l'operatore non può seguire; AI Company OS come
  sistema principale da cui gli agenti arrivano a Open WebUI, LiteLLM, LM Studio e OpenClaw. Decisioni in
  `docs/adr/ADR-043-one-ecosystem-agent-sync.md` e `docs/adr/ADR-044-active-learning.md`.
- **Branch**: `autonomous/phase-37-one-ecosystem`, dalla punta di `master` (`6a50288`) con il working tree di PHASE 34,
  35 e 36 ancora non committato. Il diff com'era prima di PHASE 37 è in
  `%USERPROFILE%\.aicos\backups\phase-37-baseline-20261110-104840\` (diff tracciato, tar dei non tracciati, HEAD).
  **Nessun commit**: non è stato chiesto.
- **OpenClaw nel flusso**: programmi `openclaw`, `openclaw-gateway`, `litellm`, `lm-studio` nel Software Hub; target
  di esecuzione `openclaw` (`openclaw tui --session agent:<id>:aicos-task-<n> --message …`, prompt con la cartella per
  intero perché l'agente gira nel gateway); cartelle di skill di OpenClaw nel Knowledge Hub; voce nella verifica prima
  della consegna.
- **Sincronizzazione degli agenti** (`sync/`, V44 `agent_publications`): un connettore per strumento. OpenClaw con i
  suoi comandi e i tre file dello spazio di lavoro; Open WebUI con la sua API e una chiave dell'operatore
  (`AICOS_OPENWEBUI_API_KEY`); LiteLLM con un blocco marcato nel file del gateway; **LM Studio dichiarato e fermo**
  (TD-100). Stato calcolato dalle impronte; scrive solo l'amministratore, solo su richiesta, solo ciò che ha il prefisso
  `aicos-`; gli agenti che OpenClaw aveva già si portano qui e restano suoi. Console: **Agenti → Ecosistema**.
- **Apprendimento attivo** (`hitl/lesson/`, V45 `lesson_progress`, `learning_concepts`, `task_handoffs.learning_mode`):
  la lezione è un file `lessons/<TASK>.lezione.md` in un formato dato agli agenti nel pacchetto; la consegna chiede
  «Imparo» / «Non posso seguire ora» / «Solo il lavoro»; le regole di `GUIDED` sono riscritte (rivede ADR-022 §3–§5).
  Console: pagina **Impara** (lezioni, lettore un atto alla volta con controllo del passo sul file vero, esercizi,
  ripasso a carte), «Adesso» nella Dashboard.
- **Agenti e skill**: le skill della libreria adatte a un agente, proposte nella sua scheda
  (`GET /api/agents/{id}/resource-suggestions`).
- **Smoke dal vivo isolato** (backend 8097, console 5183, database `aicompany_p37`, CLI vera di OpenClaw su stato
  proprio, Open WebUI 0.11.0 usa e getta): **42 controlli su 42**, dopo tre correzioni trovate solo lì (HTTP/1.1 verso
  Open WebUI; rimozione a metà di OpenClaw; gateway spento = 33 s per comando, ora una sonda da mezzo secondo). Dettaglio
  in ADR-043 §9. **Non verificato**: la sincronizzazione sull'installazione reale di OpenClaw, l'apertura di
  `openclaw tui`, il riavvio del gateway LiteLLM, l'aspetto delle pagine (il browser dell'agente non ha prodotto
  immagini: letti struttura, testi e larghezze a 1440 e 375 px).
- **Per usarla**: riavviare il backend; accendere il gateway di OpenClaw (`openclaw daemon start`); per Open WebUI
  aggiungere `AICOS_OPENWEBUI_API_KEY` a `local.env`; poi Agenti → Ecosistema → «Sincronizza tutto». Dopo una
  sincronizzazione verso LiteLLM, `docker restart litellm-gateway`.
- **Debito**: aperto **TD-100** (LOW) — i preset di LM Studio: il formato non è documentato e LM Studio non è
  installato sulla macchina. Prossimo id libero **TD-101**.
- **Contratto**: additivo (ADR-043 §10, ADR-044 §7). Migrazioni V44 e V45, additive.
- **Suite finali** (2026-11-11): Java **914 (0 falliti, 2 saltati: i link simbolici)**; console **192 su 192**,
  typecheck pulito; engine non toccato (82). Mutazioni viste rosse: 21 (ADR-043 §11, ADR-044 §8).
- **Prossimo passo**: review dell'operatore e decisione su come committare PHASE 34–37 (stessi file; i diff di partenza
  sono nei backup in `%USERPROFILE%\.aicos\backups`). Poi, in ordine di valore: provare la sincronizzazione vera con
  OpenClaw acceso; TD-100 quando LM Studio è installato; misurare se un modello locale tiene il formato della lezione
  (ADR-044 §6).

## ▶▶▶▶▶▶▶▶▶▶▶▶ PHASE 36 — Garanzia dell'harness — 2026-11-08, integrata in `master` e pushata il 2026-11-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

- **Direttiva** dell'operatore del 2026-11-08: ogni agente (AI Engine, Claude Code CLI e app, Codex, OpenCode,
  Antigravity) riceve per ogni task contesto, risorse e skill giuste, e ciò che si configura nella pagina dell'agente
  si salva, si rilegge e si carica; quattro punti (catena campo per campo; lacune note; verifica prima della consegna;
  smoke isolato), ADR, revisione di ADR-023/025/026, stato; fermarsi dopo il punto 1 (fatto: via dell'operatore «Sì,
  correggi»); **nessun commit senza richiesta**; «non rimanere debiti aperti». Decisioni in
  `docs/adr/ADR-042-harness-guarantee.md`.
- **Branch**: `autonomous/phase-36-harness-guarantee`, dalla punta di `master` (`6a50288`) con il working tree di
  PHASE 34 e 35 ancora non committato (stessi file). Il diff di PHASE 34+35 com'era prima di PHASE 36 è salvato fuori dal
  repository in `%USERPROFILE%\.aicos\backups\phase-36-baseline-20261108-163437\` (diff tracciato, tar dei non
  tracciati, HEAD), per separare le tre fasi quando l'operatore decide come committare.
- **Punto 1 — la catena campo per campo**: `HarnessChainApiTest` (28 casi; API → colonna → rilettura → pacchetto per
  `claude-code`, `claude-desktop`, `codex`, `opencode`, `antigravity`, `antigravity-ide`, `manual` → prompt
  registrato della run) trovò 7 difetti, tutti corretti: software dell'agente mai consegnato; politica di contesto e
  descrizione assenti dalla run; capability, dominio e genitore assenti da pacchetto e run; knowledge non pertinente tolta
  dal pacchetto degli strumenti con skill native (ora nominata, lettura facoltativa); modello dichiarato a strumenti che non
  lo usano (ora descritto sul target della consegna); `.mcp.json` non scritto per Claude Code nell'app desktop. Console:
  `Agents.test.tsx` salva ogni campo dalla pagina e lo rilegge.
- **Punto 2 — lacune note**: risorse adottate dal progetto (già in PHASE 34) verificate per ogni target, una volta
  ciascuna; Claude Desktop con pacchetto, `.mcp.json` e prompt anche negli appunti; il prompt mostrato (documento della
  task, pannello, `CONTEXT_MAP.md`, `AGENTS.md`) porta al pacchetto, e se la task non è consegnata l'agente chiede la
  consegna; nei progetti già iniziati i passaggi generati ancora identici si aggiornano alla prima consegna, con copia in
  `.aicos/upgrades/` (`GeneratedTextUpgrades`, rivede ADR-020 W1); copia del Knowledge Hub nella cartella dello strumento
  verificata prima di aprirlo, con «Copia nello strumento»; skill senza file o con frontmatter non valido dette nel
  pacchetto («⚠») e nella verifica (`SkillLibrary.check`).
- **Punto 3 — verifica**: `GET /api/tasks/{id}/readiness?target=&model=` (`ReadinessService`): task, strumento,
  modello, contesto, skill, knowledge, MCP, tool, software, ciascuna voce `OK`/`WARNING`/`BLOCKING`; consegna e run
  rifiutano i bloccanti con `409 delivery-not-ready` prima di scrivere o aprire (senza chiamare l'engine dentro la
  transazione); pannello del Master Orchestrator con «Verifica prima della consegna» e «Prepara e apri» disabilitato da un
  bloccante. Con il launcher spento la consegna prepara tutto, non apre lo strumento e lo dice (`notOpened`; prima
  `503` dopo aver scritto il pacchetto — trovato dallo smoke).
- **Punto 4 — smoke dal vivo isolato** (backend 8095, engine 8096, console 5182, database `aicompany_p36`, home nello
  scratchpad, launcher spento; l'8081 e il `~/.aicos` dell'operatore non toccati): **79 controlli su 79** — ogni campo e
  ogni risorsa in ogni pacchetto dei sette target, MCP nei file giusti, governance, run con `qwen3.5:4b` riuscita con
  tutto nel prompt registrato, una copia dei file di *interfaccia-movieseseries* aggiornata (34 documenti col prompt
  generato aggiornati, 2 riscritti dall'operatore intatti); in console verifica, copia nello strumento e 375 px. Dettaglio
  in ADR-042 §9. **Non verificato**: l'apertura reale degli strumenti (launcher acceso), che aprirebbe sessioni vere
  sull'abbonamento dell'operatore.
- **Dopo la chiusura, segnalato dall'operatore il 2026-11-09** («modelli pronti 1 solo echo default»): Ollama acceso con
  `/api/tags` appeso 12–55 s e in `500` (il suo aggiornamento dei «model recommendations» da `ollama.com` scaduto) e
  l'engine che dopo 3 s lo dava per spento. Corretto nell'engine: con `/api/version` che risponde, i modelli dell'ultimo
  elenco o della cartella di Ollama (`AICOS_ENGINE_OLLAMA_MODELS_DIR`, altrimenti `OLLAMA_MODELS`, altrimenti
  `~/.ollama/models`), detto nel dettaglio (ADR-042 §6.3). **L'engine dell'operatore sull'8090 gira ancora il codice di
  prima: va riavviato** perché la correzione valga.
- **Debito**: TD-97, TD-98, TD-99 chiusi (ADR-042 §7); **nessun debito aperto**. Prossimo id libero **TD-100**.
- **Contratto**: additivo — `GET /api/tasks/{id}/readiness` (`Readiness`, `ReadinessItem`, `ReadinessAction`),
  `HandoffResult.notOpened`, problema `delivery-not-ready`; `ResourceRef.description`/`configuration` annullabili (lo
  erano già nei dati: `ResponseContractTest` lo ha visto). Nessuna migrazione.
- **Suite finali** (2026-11-08): Java **845 (0 falliti, 2 saltati: i link simbolici)**; console **177 su 177** con `npm test` (timeout di 15 s in
  `vite.config.ts`) e typecheck pulito; engine **82** (77 + 5 della correzione su Ollama). Mutazioni viste rosse: 11 del backend, 4 della console
  (ADR-042 §8).
- **Prossimo passo**: review dell'operatore e decisione su come committare PHASE 34, 35 e 36 (stessi file; i diff di
  partenza sono nei due backup in `%USERPROFILE%\.aicos\backups`). I progetti reali si aggiornano alla loro prima
  consegna.

## ▶▶▶▶▶▶▶▶▶▶▶ PHASE 35 — Consumi degli agenti: misurare, poi ridurre — 2026-11-07/08, quattro fasi fatte, integrata in `master` e pushata il 2026-11-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

- **Direttiva** dell'operatore del 2026-11-07: ridurre i consumi degli agenti, soprattutto dei modelli locali
  (Ollama), senza cambiare il comportamento accettato; quattro fasi, **una alla volta, fermandosi dopo ciascuna** per
  riassumere; **nessun commit senza richiesta**. Decisioni in `docs/adr/ADR-041-agent-consumption.md`.
- **Branch**: `autonomous/phase-35-agent-consumption`, creato dalla punta di `master` (`6a50288`) **con il working
  tree di PHASE 34 ancora non committato**: le due fasi toccano gli stessi file (HandoffPackager, ExecutionService,
  RunContext, PlanMarkdown). Il diff di PHASE 34 com'era prima di PHASE 35 è salvato fuori dal repository
  (scratchpad della sessione, `phase34-baseline/`), per poterle separare quando l'operatore decide come committare.
- **Fase 1 — misurare: fatta.** Contratto v1 dell'engine con `metrics` opzionale (durate di Ollama e `num_ctx`
  chiesto); V42 (`task_runs`: `provider_total_ms`, `load_ms`, `prompt_eval_ms`, `eval_ms`, `context_window`;
  `task_handoffs.package_chars`; `task_handoff_reads`); `GET /api/usage/measurements`; scheda «Misure degli agenti»
  nella pagina Consumi e quote. Solo valori misurati: nulli dove il provider non riporta, caratteri mai convertiti in
  token. Debito nuovo: **TD-97** (schemi OpenAPI con lo stesso nome fusi in silenzio), **TD-98** (console più larga dello
  schermo a 375 px, preesistente). Prossimo id libero **TD-99**.
- **Misure di riferimento** (smoke isolato, 2026-11-08: backend 8093, engine 8094, database `aicompany_p35`, home in
  scratchpad; task di piano reale «Progettare la migrazione dei prestiti», Database Specialist con 4 skill + 1 adottata,
  `qwen3.5:9b`, 7,5 GB liberi): run a freddo 5.392 token in / 421 out, 76,2 s (caricamento 19,9 s, lettura del prompt
  18,8 s); run identica subito dopo 42,6 s, lettura del prompt **0,43 s** con lo stesso `prompt_eval_count` — il riuso
  della cache si vede dalla durata, non dai token; prompt di sistema 17.916 caratteri, **91% corpi di skill**. Handoff:
  pacchetto 4.132 caratteri, file da leggere **85.271**, di cui skill **80.220 (94%)**. Dettaglio in ADR-041 §6; lo
  script dello smoke è ripetibile per il «dopo» (scratchpad della sessione, `p35/`).
- **Suite dopo la Fase 1**: Java **767 (0 falliti, 2 saltati: link simbolici)**, console **172**, engine **75**.
  Mutazioni viste rosse 5/5 (ADR-041 §4).
- **Fase 2 — skill pertinenti, caricate quando servono: fatta** (2026-11-08, via dell'operatore «Sì, Fase 2»; rivede
  ADR-026 §3). Run: al più 2 skill/knowledge pertinenti per parole chiave (`Pertinence`), di ciascuna la sezione
  `## Essenziale` o il corpo fino al primo `##` entro `aicos.runs.skill-essential-chars` (1500); prompt di sistema con
  parte stabile prima (ruolo, profilo, harness per nome, adozioni, autonomia) e ordine dell'harness con spareggio.
  Handoff a Claude Code (CLI e app), Codex, OpenCode, Antigravity, Verdent: skill per nome dopo `copyForTarget` del Hub
  (copia allineata → «usa la skill»; mancante/indietro/estranea → detto, copia proposta, file «intanto, se
  pertinente»); knowledge solo pertinenti; nessuna scrittura nelle cartelle delle app. Misure (ADR-041 §6): run a
  freddo 5.392 → 1.283 token, lettura del prompt 13,1 → 3,3 s; handoff a Claude Code, letture obbligatorie 85.271 →
  5.051 caratteri; fra task diverse a caldo nessun guadagno (1,17 → 1,49 s). Le skill della libreria dell'operatore non
  hanno `## Essenziale`: di `sql-modeling` arriva solo la descrizione.
- **Suite dopo la Fase 2**: Java **782 (0 falliti, 2 saltati)**; console e engine non toccati dalla Fase 2.
- **Fase 3 — esito separato dal documento della task: fatta** (2026-11-08, via «Sì, Fase 3»; rivede ADR-021/TD-55).
  Esito completo in `tasks/TASK-NNN.esito.md` (`TASK.esito.md` nell'inbox), riassunto con link nella sezione Esito;
  `TaskOutcomes` legge prima il file, poi la sezione (documenti vecchi validi); pacchetto, prompt completo,
  segnaposto, governance dell'inbox, template `AGENTS.md` e `CONTEXT_MAP.md` aggiornati; testo dell'Inspector.
  Una run legge del documento della task le sezioni di lavoro; su richiesta dell'operatore del 2026-11-08
  («non eliminare… rendile più compatte») implementazione, test e criteri restano, compattati voce per voce (senza i
  «perché») entro `aicos.runs.task-section-chars` (800), sezioni «—» saltate. Misura (ADR-041 §10): sui 37 documenti
  reali di *interfaccia-movieseseries* una run leggeva in media 8.527 caratteri del documento (14 tagliati a 12k), ora
  2.257. Gli `AGENTS.md` dei progetti esistenti non sono riscritti (W1).
- **Suite dopo la Fase 3**: Java **787 (0 falliti, 2 saltati)**, console **172**, engine **75** (non toccato).
- **Fase 4 — Ollama per modello e per ruolo: fatta** (2026-11-08, via «passa a fase 4»). V43 (`llm_models.num_ctx`,
  `keep_alive`, `quantization`, valori per i modelli locali dove nulli); `catalog/models.json` con gli stessi valori;
  contratto v1 dell'engine con `context_window` e `keep_alive` opzionali passati a Ollama; `ModelSettings` li aggiunge a
  run e generazioni di piano; pagina Modelli con colonna e dialogo «Esecuzione» (`PUT /api/catalog/models` con i tre
  campi). Instradamento per ruolo nel catalogo (`catalog/model-routing.json`, `ModelRouter`): compiti brevi →
  `qwen3.5:4b`, pianificazione e codice → `qwen3.5:9b`; vale per la decisione dell'orchestratore e per le run senza
  modello (né chiesto né dell'agente), non tocca il modello di un agente. Run dei modelli LOCAL su un esecutore a un
  thread (`localRunExecutor`, `aicos.runs.local-concurrency`) più un cancello comune con le generazioni di piano
  (`LocalModelGate`). Valori: 9B 16384/5m, 4B e LFM 8192/2m, Gemma 8192/5m, DeepSeek e Llama 4096/1m. ADR-041 §11–§12;
  revisioni di ADR-016 e ADR-034.
- **Sezioni `## Essenziale` nella libreria dell'operatore** (su sua richiesta del 2026-11-08: «scrivila tu, rendi ogni
  skill utilizzabile da qualsiasi LLM locale e non»): aggiunte alle 13 skill e alle 2 knowledge di
  `%USERPROFILE%\.aicos\library` (260–1.347 caratteri, tutte entro i 1.500 di `aicos.runs.skill-essential-chars`), prima
  del primo titolo di secondo livello, frontmatter intatto. Copia dei file prima della modifica in
  `%USERPROFILE%\.aicos\backups\phase-35-essenziale-20261108-123343`. Conseguenze: le copie del Hub nelle cartelle delle
  app risultano «da aggiornare» (si aggiornano da Knowledge Hub → Aggiornamenti); `impeccable`, `emil-design-eng`,
  `frontend-design` e `find-skills`, installate da marketplace, risultano modificate in libreria (un aggiornamento a
  monte chiederà conferma e terrà un backup).
- **Debito**: TD-97 (schemi OpenAPI con lo stesso nome), TD-98 (console più larga dello schermo a 375 px), TD-99 (due
  test della console in timeout a 5 s nella suite intera); prossimo id libero **TD-100**.
- **Suite finali** (2026-11-08): Java **799 (0 falliti, 2 saltati)**; engine **77 passati**; console **173 su 173** con
  `--testTimeout=15000` e typecheck pulito — con il limite predefinito di 5 s un test diverso a ogni giro va in timeout
  (TD-99, file non toccati dalla fase). Misure prima/dopo in ADR-041 §6; punti aperti in §13. **Prossimo passo**: review dell'operatore e decisione su come committare PHASE 34 e PHASE 35.

## ▶▶▶▶▶▶▶▶▶▶ PHASE 34 — Claude Code nell'app desktop come target di consegna — 2026-11-07, integrata in `master` e pushata il 2026-11-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

- **Direttive** dell'operatore del 2026-11-07: ricostruire dove un agente Claude trova skill e informazioni e
  come agisce dopo il prompt; «controlla perché non vengono creati gli handoff alla fine di ogni task»; «correggi»;
  «aggiungi Claude Code app desktop come soluzione per consegnare la task come per la cli».
- **Causa trovata**: il pacchetto di handoff si scrive solo con «Prepara e apri» (`POST /api/tasks/{id}/handoffs`).
  Su *interfaccia-movieseseries* (37 task, 22 chiuse) c'erano solo `PLAN` e `TASK-008`: delle 17 sessioni di Claude
  Code una sola partiva dal pacchetto, le altre da «Esegui TASK-NNN…» incollato a mano — il prompt che il documento
  della task, il pannello e `CONTEXT_MAP.md` mostravano — e dal 3 novembre da Claude Desktop, che non era un target.
- **Fatto**: target `claude-desktop` con delivery `APP_LINK` (`claude://code/new?folder=…&q=…` aperto da
  `explorer.exe`, `LaunchPlan.forLink`); «Come si consegna» al posto di «Prompt compatto» nei documenti di task;
  «Prompt senza consegna» con avvertenza nel pannello; `CLAUDE.md` con `@AGENTS.md`; `AGENTS.md` che mette il
  pacchetto per primo; `CONTEXT_MAP.md` con il prompt della consegna. ADR-025, ADR-020, ADR-021 rivisti; RUNNING.
- **Poi, su richiesta dell'operatore** («sostituisci la riga di CLAUDE.md con @AGENTS.md per i progetti esistenti»;
  «fai in modo che le project_resources arrivino all'agente»; «se claude ha bisogno di claude.md fai in modo che lo
  abbia a disposizione app e cli, stessa cosa per codex antigravity»):
  - dei 47 progetti nel database solo *interfaccia-movieseseries* aveva `CLAUDE.md` e `AGENTS.md`: la riga è
    diventata `@AGENTS.md` (copia del file prima in `%USERPROFILE%\.aicos\backups\phase-34-claude-md`) e ha
    ricevuto `GEMINI.md`; gli altri 46 (MoviesESeries e 45 archiviati) sono cartelle senza governance, che la
    ricevono al primo handoff;
  - le risorse adottate dal progetto arrivano nel pacchetto (con i loro MCP) e nelle run (ADR-026);
  - `GEMINI.md` nella struttura del workspace, e all'handoff `ensureGovernance` scrive i file mancanti di ogni
    strumento anche in una cartella già esistente (ADR-020); `AGENTS.md`, `CLAUDE.md` e `GEMINI.md` alla radice
    di questo repository.
- **Non fatto**: gli `AGENTS.md` dei progetti esistenti non hanno il passo «pacchetto per primo» (W1). Il link di
  Claude Desktop è stato aperto una volta su questa macchina (`explorer.exe` lo accetta); che l'app mostri la
  sessione con cartella e prompt lo conferma l'operatore.

## ▶▶▶▶▶▶▶▶▶▶ PHASE 33 — Consumi di Antigravity, Kimi Code e Copilot; i file dei progetti — 2026-10-11/13, integrata in `master` e pushata il 2026-10-13

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

- **Direttive** dell'operatore del 2026-10-11: «aggiungere nella scheda dei consumi i consumi di Antigravity, GitHub
  Copilot da VS Code e Kimi»; «un file tree explorer, in modo che io possa visualizzare tutti i file, di ogni progetto,
  senza dover aprire gli IDE [...] aprire il file manager sulla sinistra cliccando un bottone»; del 2026-10-12: «un
  pulsante "Apri con" [...] tra quelli del software hub [...] suggerire automaticamente il programma più adatto»,
  «un menu a tendina con rinomina, rimuovi ecc. se clicco con il tasto destro su un file».
- **Branch**: `autonomous/phase-33-usage-and-files`, dalla punta di `master` locale (`3e6e5ec`, PHASE 30–32 e la
  chiusura del registro). **Merge e push autorizzati** dall'operatore il 2026-10-13 («mergia in master e pusha»):
  `master` avanza alla punta del branch (`1bf38b1`) con un **fast-forward** e `origin/master` va da `f4c69b3` a
  `1bf38b1` — con questa fase salgono anche i 38 commit di PHASE 30–32, che erano solo locali. Nessun merge commit,
  nessun force. Prima del push i commit sono stati scorsi in cerca di credenziali: solo valori finti di test e fixture.
  Il branch resta locale, fermo alla sua punta.
- **Consumi (ADR-038)**: Antigravity e Antigravity IDE — le quote per modello che l'app salva nel suo database di stato
  (SQLite, chiave `antigravityUnifiedStateSync.userStatus`, protobuf osservato), in sola lettura, senza mai leggere nome
  ed email dell'account; Kimi Code — `GET https://api.kimi.com/coding/v1/usages` (la richiesta della CLI ufficiale per
  `/usage`) con la chiave **dell'operatore** `AICOS_KIMI_CODE_API_KEY` nel `local.env`, mai quella dell'app; Copilot —
  nessun dato sul PC né API documentata per questo piano (`free_educational_quota`): solo il reset del primo del mese
  alle 00:00 UTC e la pagina ufficiale, coerente con la decisione su TD-50. Nuova dipendenza `sqlite-jdbc` (versione
  di Spring Boot).
- **File dei progetti (ADR-039, rivede ADR-020 W3 in lettura)**: bottone **File** nella barra in alto, pannello a
  sinistra con l'albero (cartelle lette quando si aprono), file aperto al posto della pagina che resta montata;
  `GET /api/projects/{id}/browse`, `…/browse/file`, `…/browse/image`, sola lettura, dentro la cartella (W2), file di
  credenziali elencati ma mai letti, testo UTF-8 fino a 2 MB, solo le immagini come byte.
- **«Apri con» e menu contestuale (ADR-040, direttive del 2026-10-12; rivede ADR-039 §5, ADR-019 I2, ADR-020 W3 in
  scrittura, ADR-035 §5)**: nel visualizzatore «Apri con ‹programma consigliato›» e l'elenco dei programmi del Software
  Hub che aprono file, consigliati per nome, estensione e tipo (testo/binario), ricalcolati a ogni file; nell'albero il
  tasto destro apre Apri, Apri con, Mostra in Esplora risorse (`SHOpenFolderAndSelectItems`), Copia percorso, Rinomina
  (F2), Nuovo file/cartella, Sposta nel Cestino (Canc) — **nel Cestino di Windows, mai eliminato per sempre**; mai sopra
  un nome; struttura del workspace, `.git`, collegamenti e documenti dei record protetti. `file_args`/`file_types` nel
  catalogo (V41) per dieci programmi; un file passa solo a un `.exe`, dentro la cartella del progetto. Due problemi
  nuovi: `workspace-entry-exists`, `workspace-entry-in-use`. Dal vivo: Notepad++ avviato su un file, rinomina, Cestino,
  Esplora risorse con un nome con spazio e virgola (con `explorer /select,` apriva Documenti: corretto).
- **Migrazione**: V40 (allarga `quota_plans_source_check` e `quota_plans_window_check`) e V41 (`software.file_args`,
  `software.file_types`, `software_file_args_check`, i valori per le voci spedite), additive.
- **Contratto**: additivo — `UsageWindowResponse.modelQuotas`, `ModelQuotaResponse`, tre rotte e tre schemi
  (`ProjectDirectoryListing`, `ProjectFileEntry`, `ProjectFileView`); enum allargati (`ANTIGRAVITY_LOCAL`,
  `KIMI_CODE_API`, `PER_MODEL`).
- **Mutazioni** viste rosse: credenziali lette, cartella fuori elencata, BOM conservato, float omesso letto come
  «tutto residuo», reset del modello non usato, `used` non ricavato da `remaining`.
- **Smoke dal vivo** (backend 8091, database `aicompany_p33`, console 5180; i tuoi 8081 e 5173 non toccati): i due
  database reali di Antigravity letti (7 e 14 modelli, azzerati dall'ultimo salvataggio), Copilot con reset al
  1° novembre, Kimi che chiede la chiave; un progetto sulla cartella di AI Company OS sfogliato, `package.json` aperto,
  pannello sovrapposto a 375 px senza scorrimento orizzontale.
- **Suite**: Java **751 (0 falliti; 2 saltati: i test dei link simbolici, che Windows non crea senza privilegi, girano in CI)**, console **168**, engine **73** (non toccato).
- **Da sapere**: un test della Blueprint (`BlueprintView.test.tsx`, «shows the numbers…») è fallito una volta su
  cinque esecuzioni della suite sotto carico, poi verde: attese di un secondo; non toccato da questa fase.
- **Fuori dal repository** (direttive dell'operatore del 2026-10-13, sulla sua macchina): i progetti di AntiGravity,
  ClaudeCode, Codex, Devin, Idea, NetBeans, Pycharm, Verdent, VSCode, Warp e Webstorm `…Projects` spostati in
  `C:\Users\gabry\AI-Company-Projects` (struttura piatta; i doppioni con suffisso d'origine), il contenuto di
  «Progetti Personali» portato su di un livello; le cartelle svuotate nel Cestino. I virtualenv di MoviesESeries e
  PyCharmMiscProject ricreati con gli stessi pacchetti; IntelliJ, PyCharm, WebStorm e NetBeans aprono i nuovi progetti
  in quella cartella e i loro percorsi (recenti, attendibili, interpreti) sono stati riscritti. Nel database `aicompany`
  ogni progetto spostato è un progetto registrato, archiviato come l'operatore ha scelto (47 progetti, ogni cartella
  esistente); i cinque la cui cartella l'operatore ha messo nel Cestino sono stati tolti, con un `DATA_DELETED` nel
  registro di sicurezza.
- **Prossimo passo**: nessuno in sospeso; si attendono nuove direttive.

## ▶▶▶▶▶▶▶▶▶ Registro chiuso e PHASE 30–32 in `master` — 2026-10-11

> Sostituita, dove diverge, dalla sezione qui sopra.

- **Decisione dell'operatore** del 2026-10-11: «consideriamo tutti i debiti chiusi anche se non abbiamo effettuato
  azioni, aggiorna tutti i documenti necessari e mergia tutto in master». Chiusi per decisione, senza modifiche al
  codice: i cinque vincoli esterni **TD-50, TD-74, TD-83, TD-86, TD-87**, e quattro voci che restavano aperte nei
  documenti senza un id, registrate e chiuse lo stesso giorno: **TD-93** (R3 della review di TASK-001, `.env` e
  backend), **TD-94** (R6, tag mobile di PostgreSQL e nomi di Compose fissi), **TD-95** (correzioni mai applicate
  all'audit di TASK-000), **TD-96** (le domande di contratto di PHASE 2 ancora vere). **Nessun debito aperto** in
  nessuno dei due spazi; prossimo id libero **TD-97**. Dettagli: `docs/PHASE32_DECISIONS.md`, `docs/DEBT_REGISTRY.md`.
- **Ecosistema 3D** (ADR-037, direttiva del 2026-10-10): la scheda `#/ecosystem3d` disegna ogni agente e ciò che lo
  definisce come grafo 3D navigabile, letto dalle API esistenti, in sola lettura; three.js e React Three Fiber in un
  chunk caricato a richiesta. Nessuna modifica a control plane, database o contratto. Committata in questa
  chiusura; il test della scheda in `App.test.tsx` aspetta il chunk fino a 10 s (con l'intera suite il
  caricamento di three.js superava il secondo di default).
- **Merge autorizzato** dall'operatore il 2026-10-11 (charter §8): `master` avanza da `f4c69b3` (punta di PHASE 29,
  già su `origin`) alla punta di `autonomous/phase-32-debt-zero` con un **fast-forward** — PHASE 30, PHASE 31,
  PHASE 32, l'Ecosistema 3D e questa chiusura; nessun merge commit, nessun force. Ogni altro branch locale e remoto
  era già contenuto. **Push su `origin` non ancora eseguito**: lo decide l'operatore.
- **Suite** prima del merge: Java **705 (0 falliti, 1 saltato: link simbolici su Windows)**, console **142** (due esecuzioni consecutive), engine **73**; typecheck
  della console pulito.
- **Pulizia dei documenti**: titoli delle fasi già integrate (non più «in attesa di Human Final Review»), sezioni
  storiche «Debito aperto rilevante» e «Domande di contratto» allineate al registro, percorso del file dei segreti in
  `docs/RUNNING.md` (TD-79), handoff `docs/handoff/FINAL_HANDOFF_PHASE_32.md`.
- **Prossimo passo**: il push di `master` su `origin`, quando l'operatore lo autorizza. *Fatto il 2026-10-13, insieme
  a PHASE 33 (sezione sopra).*

## ▶▶▶▶▶▶▶▶ PHASE 32 — Debito zero — COMPLETATA il 2026-10-08, integrata in `master` il 2026-10-11

> Sostituita, dove diverge, dalla sezione qui sopra.

- **Direttiva** dell'operatore (2026-10-07): «non restare debiti aperti alla fine, nel senso che devi
  risolverli tutti» — confermata con una domanda esplicita: **tutti i debiti del registro**, sia
  dello spazio dell'audit sia di quello vivo. Quelli che non dipendono dal codice (vincoli esterni,
  decisioni umane, hard stop del charter §5) si portano all'operatore con una proposta, non si chiudono
  per finta.
- **Branch**: `autonomous/phase-32-debt-zero`, dalla punta di PHASE 31 (`021404c`, che contiene anche le
  correzioni del terminale e dei pannelli). Non pushato; integrato in `master` il 2026-10-11 (sezione sopra).
- **Regola di chiusura** (charter §4): ogni debito chiuso ha un test che fallisce se il difetto torna,
  visto rosso per mutazione.
- **Chiusi**: lo spazio dell'audit intero (TD-01, 02, 03, 05, 06, 09, 10, 14, 15, 16, 19, 20, 21,
  22 — `AuditDebtGuardTest`, `ApiShapeGuardTest`, `RouteCoverageTest`); vivi TD-26, TD-32, TD-33,
  TD-34, TD-35, TD-38, TD-39, TD-41, TD-43, TD-45, TD-46, TD-47, TD-48, TD-49, TD-54, TD-55, TD-57, TD-58,
  TD-53, TD-59, TD-60, TD-61, TD-62, TD-63, TD-65, TD-66, TD-67, TD-68, TD-69, TD-70, TD-71, TD-72, TD-73, TD-75, TD-76,
  TD-77, TD-78, TD-79, TD-80, TD-81, TD-82, TD-84, TD-85, TD-88, TD-89, TD-90. Dettagli in `docs/DEBT_REGISTRY.md`
  e nelle revisioni di ADR-009, ADR-010, ADR-013, ADR-014, ADR-016, ADR-017, ADR-021, ADR-022, ADR-024, ADR-025,
  ADR-027, ADR-029, ADR-030, ADR-031, ADR-033, ADR-034, ADR-035, ADR-036.
- **Decisioni dell'operatore del 2026-10-10** (`docs/PHASE32_DECISIONS.md`): TD-72 sì, una sola forma di eliminazione
  (anteprima `DeletionPreview`, `DELETE` `204`, contratto non additivo); iGPU accesa (`OLLAMA_IGPU_ENABLE=1`, TD-67);
  TD-51 chiuso tenendo `llama3.2:3b`; TD-44 chiuso lasciando le finestre dedicate (i connettori li collega l'operatore);
  TD-53 sì: il token di sessione della console è un cookie `HttpOnly` con token anti-CSRF (`POST /api/auth/session`).
- **Restavano aperti** (`docs/PHASE32_DECISIONS.md`): solo vincoli esterni, riverificati il 2026-10-08: TD-50, TD-74,
  TD-83, TD-86, TD-87. Chiusi per decisione dell'operatore il 2026-10-11 (sezione sopra).
- **Migrazioni nuove**: V31 (transizioni), V32 (livello nelle review), V33 (id a blocchi del Blueprint), V34
  (azioni AI), V35 (ruoli serviti), V36 (voci congedate), V37 (categorie scelte), V38 (porta di OpenCode),
  V39 (connettori installati dal Hub): tutte additive.
- **Note di metodo**: `RouteCoverageTest` usa un intercettore presente solo nei test
  (`support/RouteRecorder`, registrato via `AutoConfiguration.imports` dei test) e
  `junit-platform.properties` per girare per ultimo; con `-Dtest=…` si salta. Le entità prendono
  l'ora da `persistence/DatabaseClock` (microsecondi), mai da `Instant.now()`.
- **Annullare**: una run (`POST /api/runs/{id}/cancel`) e una generazione del piano
  (`POST /api/plan-runs/{id}/cancel`) si fermano; il thread che attende l'engine è interrotto dopo il
  commit e l'AI Engine annulla la chiamata al provider quando il chiamante se ne va (`app/cancellation.py`).
- **Storia e apprendimento**: ogni transizione di una task è registrata (V31, `TaskTransitions` unico
  punto); ogni review porta il livello di Human-in-the-Loop (V32) e `GET /api/projects/{id}/learning`
  suggerisce il livello, senza mai applicarlo.
- **Contratto**: i campi delle risposte sono richiesti e `@Nullable` dove possono essere null (`ResponseShapes`);
  `ResponseContractTest` confronta ogni risposta JSON della suite con `docs/api/openapi.json`. Un record nuovo
  con un campo che può essere null va annotato, o la suite lo segnala con schema, campo e rotta.
- **Suite**: Java **705** verdi (1 saltato preesistente), console **110**, engine **73**.
- **Prossimo passo**: Human Final Review della fase; le decisioni di `docs/PHASE32_DECISIONS.md`. Fatti entrambi il
  2026-10-11 (sezione sopra).

## ▶▶▶▶▶▶▶ PHASE 31 — Il Knowledge Hub come punto unico — COMPLETATA il 2026-10-07, integrata in `master` il 2026-10-11

- **Direttiva** del 2026-10-07: trasformare il Knowledge Hub in un punto unico per le skill e le knowledge di
  tutti gli agenti — quelle della libreria e quelle installate con Claude Desktop, Codex e le altre app,
  sincronizzate, con verifica e installazione degli aggiornamenti, e un catalogo di marketplace online (anche
  community) organizzato come il Mockup Hub, da cui cercare e installare — senza togliere né peggiorare nulla;
  poi: «dividere le skill di Code, Codex (ChatGPT) e ecc. per tipologia». Decisioni in **ADR-036** (estende
  ADR-026 e ADR-023).
- **Branch**: `autonomous/phase-31-knowledge-hub`, dalla punta di PHASE 30 (`4127377`), **non pushato**.
  `master` intoccato.
- **Backend** (`harness/hub`): `SkillLocations` (dove ogni app tiene le skill, chi può scriverci),
  `SkillScanner` (personali, plugin, Codex con `config.toml`, Claude Code con `installed_plugins.json`, Claude
  Desktop per account in uso, istruzioni globali, knowledge items; cache per dimensione e data),
  `Frontmatter` (YAML delle app), `SkillCategories`, `SkillMarketplaces` (skills.sh, ClawHub, repository
  GitHub ufficiali; `HubWeb` con 4 host consentiti), `SkillHubService` (vista unica, import, installazione,
  copie con marcatore `.aicos-hub.json`, pull/push, zip), `SkillUpdates`, `SkillSync` (istantanea ed eventi,
  ogni 5 min), `HubFiles` (staging, backup, nessuna eliminazione). **V30** additiva: `skill_installations`,
  `skill_snapshot`, `skill_sync_events`. Corretto anche l'import da URL di un `SKILL.md` standard senza `key`.
- **Console**: `KnowledgeHub` con riepilogo e cinque schede — Installate (app → tipologia → plugin, filtri di
  categoria e stato), Marketplace, Aggiornamenti, Attività, **Libreria e catalogo** (la vista di PHASE 19,
  invariata); `views/knowledge/*`. Il dettaglio di una skill della libreria ha «Nelle app».
- **Suite**: Java **572** (571 verdi + 1 saltato su Windows, preesistente; erano 552) · console **64** (erano 57) ·
  engine invariato. OpenAPI **solo additivo** (17 percorsi; confrontato con quello di PHASE 30 dopo aver tolto
  collisioni di nomi di schemi e di `operationId`, TD-59). **Mutazioni: 10/10 rosse.**
- **Smoke dal vivo** su `aicompany_p31` (porta 8091, console 5180) con le cartelle reali delle app **in sola
  lettura**: 323 voci delle app + 12 della libreria; ricerca nei marketplace ~1,6 s dopo la correzione (era ~15 s);
  installazione di `algorithmic-art` nella libreria temporanea; sincronizzazione e verifica aggiornamenti senza
  errori. Nessuna scrittura nelle cartelle delle app. Il DB `aicompany` e `%USERPROFILE%\.aicos` non toccati.
  Esito: `docs/handoff/FINAL_HANDOFF_PHASE_31.md`.
- **Correzione dopo la verifica dell'operatore** (stesso giorno, «per Claude non vedo i plugin già installati»):
  Claude Desktop è l'app del **Microsoft Store**, il suo `%APPDATA%\Claude` è virtualizzato e il backend avviato da
  PowerShell lo trovava vuoto (zero voci nella sua istantanea, mentre lo smoke — lanciato da dentro l'app — ne vedeva
  228): ora il Hub legge anche `%LOCALAPPDATA%\Packages\Claude_*\LocalCache\Roaming\Claude`. E non elencava che skill:
  ora **plugin** (anche senza skill, con i componenti), **connettori MCP** (uniti per nome, mai argomenti/env/token/
  file di configurazione) e **memoria** di Claude Code; filtro per tipo nella console; una sede letta per la prima volta
  viene fotografata, non riportata come «installata oggi» (`baseline:<sede>`). Test: `SkillHubApiTest` 12,
  console 65; **7/7 mutazioni rosse** in più. L'operatore deve **riavviare** il suo backend (avviato alle 15:04, senza
  riavvio automatico).
- **Seconda correzione** (stesso giorno: «le skill e i plugin che aggiungo a Claude Desktop e a ChatGPT non si vedono»,
  «da marketplace non posso installare su ChatGPT», «solo skill nel marketplace: voglio plugin, connettori»):
  le skill **caricate** in Claude Desktop (id `skill_01…`, cartella col nome) ora compaiono; le **app dell'account
  ChatGPT** si leggono dalla cache di Codex (16 sul PC); l'app è **ChatGPT · Codex** ed è installabile
  (`~/.codex/skills`); `~/.agents/skills` compare anche nella sua vista («Lette anche da qui»). Il Marketplace ha
  le schede **Skill · Plugin · Connettori**: plugin dei marketplace di Claude Code e di OpenAI, connettori dal
  **registro MCP**, installati con le CLI delle app (`HubInstaller`, `HubCommands`: argomenti validati, nessuna
  shell, guardia per `claude.cmd`; comando dichiarato da un marketplace solo dopo conferma; chiavi mai salvate né
  restituite) o in `claude_desktop_config.json` (con backup). ADR-036 §2b e §6b. Suite: Java **579**, console
  **68**; mutazioni **7/7** backend e **4/4** console. Smoke su `aicompany_p31c` con CLI sostitute che
  stampano soltanto: nessuna installazione reale nelle app.
- **Terza correzione** (stesso giorno: «cambiando scheda il terminale si chiude», «non posso scorrere la chat di
  Claude Code nel terminale», «i pannelli di skill e agenti non sono ridimensionati»): il terminale usava **winpty**
  (default di pty4j, ADR-029 diceva ConPTY): ora **ConPTY** incluso in pty4j, e la rotella arriva a Claude Code a
  schermo intero; la vista Terminale resta montata; **le shell vivono sul server** e sopravvivono a ricaricamento e
  chiusura della scheda (riaggancio con output e modalità, «In background», scadenza 24 h), con l'**ambiente
  dell'utente** (`CreateEnvironmentBlock`) senza i marcatori di una sessione Claude Code genitore — ADR-029 §5,
  chiusi **TD-91** e **TD-92** aperti e risolti nello stesso giorno. Pannelli di dettaglio (agente, skill, risorsa,
  software) e colonna delle app del Hub: altezza della finestra, scroll proprio (`.side-panel`). API: `GET
  /api/terminal/sessions`, `DELETE /api/terminal/sessions/{id}`, ticket con `attach`, problema
  `terminal-not-found` (OpenAPI additivo). Suite: Java **596**, console **72**; mutazioni **7/7** backend e
  **4/4** console. Verificato dal vivo su `aicompany_p32term` (porta 8091, console 5180): stesso PID dopo F5, Claude
  Code ridisegnato con mouse e rotella, passaggio fra due schede del browser, nessun processo residuo.
- **Debiti**: aperti TD-74…TD-90; TD-59 ripetuto ed evitato; TD-91 e TD-92 aperti e chiusi; prossimo libero
  **TD-93**. Direttiva del 2026-10-07: **risolvere tutti i debiti del registro** (in corso).
- **Prossimo passo autonomo**: nessuno — fermo per la Human Final Review
  (`docs/handoff/FINAL_HANDOFF_PHASE_31.md`).

## ▶▶▶▶▶▶ PHASE 30 — Ciò che si crea qui si elimina qui — COMPLETATA il 2026-10-06, integrata in `master` il 2026-10-11

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

- **Direttiva** del 2026-10-06: un sistema coerente, sicuro e riutilizzabile di eliminazione dalla
  console, a partire da due incoerenze (agenti non eliminabili; reference eliminabili solo da Explorer),
  esteso a tutto ciò che la console crea. Decisioni in **ADR-035** (modifica ADR-004 §3/ADR-008 per gli
  agenti e ADR-027 §3 per i file; estende ADR-027).
- **Branch**: `autonomous/phase-30-deletion`, dalla punta di PHASE 29 (`f4c69b3`), **non pushato**.
  `master` intoccato. Commit: `d31f626` (backend), `2e42bdd` (console), `ae4b8fd` (due test che fissavano il
  vecchio contratto), `c5223d3` (correzioni dallo smoke), più i documenti.
- **Agenti**: `DELETE /api/agents/{id}` e `POST /api/agents/bulk-delete` (admin, `If-Match` / versione per
  agente, tutto-o-niente). Task `OPEN`/`DONE` scollegate, run con il nome (`V28`), sottoagenti principali o
  eliminati (`subagents`), lavoro in corso che blocca o torna in coda (`inProgress=STOP`), run in volo che
  blocca sempre. Lock `tasks → agents` (ADR-010 §3). Il set di template si reinstalla
  (`GET /api/agent-templates/plan`), non torna da solo: provato su ciò che esegue un riavvio.
- **File**: `deletion/ManagedFiles` — solo radici calcolate dal server, guardie di percorso, eliminazione
  **a stadi** legata alla transazione (rename nascosto → rimozione al commit, ripristino al rollback); file
  aperto da un altro programma → `409`. Reference (ogni file di `references/`, singolo o selezione),
  documenti senza record che li usi, skill/knowledge creati o importati con il loro file.
- **Anche**: Blueprint intero (admin, nome digitato, mai il codice), budget di un provider, risorse adottate
  da un progetto visibili e scollegabili nella console, spesa e run degli agenti eliminati sotto il loro nome.
- **Pattern comune**: anteprima `DeletionPreview` uguale per ogni cosa (`deletes`, `detaches`, `keeps`,
  `files`, `blockers`, `allowed`), `204`, `409 deletion-blocked` con le frasi dell'anteprima; console:
  `DeleteButton`, `ConfirmDeletion`, `SelectionBar` in `components/Deletion.tsx`. Politica per entità:
  ADR-035 §9.
- **Suite**: Java **549 (548 verdi + 1 saltato su Windows, preesistente; erano 518)** · console **57** · engine **68**.
  OpenAPI **solo additivo** (14 operazioni nuove). **Mutazioni**: 13/13 rosse sul backend (la tredicesima,
  il `412` della rimozione del budget, dopo aver aggiunto il test che mancava); 8 sulla console, 7 rosse e 1
  sopravvissuta (stato locale reso inutile dalla rilettura → codice rimosso).
- **Smoke dal vivo**: backend su un database separato **`aicompany_p30`** (porta 8091, home e workspace
  temporanei, nessun programma avviato) e console sulla porta 5180, guidata nel browser integrato.
  Esito in `docs/handoff/FINAL_HANDOFF_PHASE_30.md`: tutto come atteso, più un comportamento corretto (un file
  o un agente già eliminato altrove ora è detto, non mostrato come errore). Anche il riavvio: niente ricreato.
  Il DB di sviluppo `aicompany` **non è stato toccato**: al primo avvio riceve V26–V29.
- **Aggiornamento del 2026-10-07** (richieste dell'operatore, commit `afd8f17`, `50c03d4`):
  **12 template** — Code Architect, Frontend Developer e Database Specialist anche come template, con la
  configurazione del seed; **Master Orchestrator e Learning Coach su `gemma4:e4b`** (template e **V29**, che
  sposta solo gli agenti ancora sul modello del loro template; addendum ADR-034 §10); **ogni dialog sta nella
  finestra**: contenuto che scorre e azioni sempre visibili — risolve «Da template» che usciva dallo schermo e
  «Assegna la task» che, scelto un agente, non permetteva più di salvare (misurato nel browser a 1280×720,
  800×640 e 375×812). Mutazioni sui test nuovi: 3/3 rosse. Suite dopo l'aggiornamento: Java **552** (0 falliti,
  1 saltato preesistente) · console **57**.
- **Debiti**: aperti TD-68…TD-73; prossimo libero **TD-74**.
- **Prossimo passo autonomo**: nessuno — fermo per la Human Final Review
  (`docs/handoff/FINAL_HANDOFF_PHASE_30.md`).

## ▶▶▶▶▶ PHASE 29 — Upgrade dei modelli locali — COMPLETATA il 2026-10-01, integrata in `master` (`f4c69b3`)

> Sostituita, dove diverge, da PHASE 30 qui sopra.

- **Direttiva** del 2026-09-30: aggiornare in autonomia i modelli Ollama di AI Company OS e di
  3D Omniverse (`%USERPROFILE%\3D Omniverse`, fuori da git), massimizzando l'intelligenza del sistema
  sulla macchina reale, con fallback, smoke test, rollback verificato. Decisioni e misure in **ADR-034**.
- **Branch**: `autonomous/phase-29-local-model-upgrade`, dalla punta di PHASE 28 (`75336fb`), **non
  pushato**. `master` intoccato.
- **Esito**: coder dei seed da `deepseek-coder-v2:16b` a `qwen3.5:9b` (seed V3), Test Engineer da
  `qwen3.5:4b` a `lfm2.5:8b` (template e V27), nuovo pacchetto di `gemma4:e4b`, DeepSeek `DEPRECATED`
  (V27, TD-52 chiuso); AI Engine: `think: false` per le risposte testuali, finestra 16K (4K per i modelli
  con cache pesante), catena di fallback. In 3D Omniverse: ruolo `light` a `qwen3.5:4b`, fallback per
  agente, finestra per agente; lo `script` resta su DeepSeek (misurato migliore sugli script Blender).
  Qwen3.8-27B (10 GB, GSQ) installato come `CANDIDATE`: 0,3–0,9 token/s.
- **Suite**: Java **518** (0 falliti, 1 saltato preesistente) · engine **68** · 3D Omniverse **55**.
  Mutazioni: 3/3 rosse sulle guardie di V27 e seed V3. E2E dal vivo: engine 14/14, control plane 17/17
  sul clone `aicompany_p29` (V25 → V27 all'avvio), smoke degli agenti di 3D Omniverse. Rollback verificato
  nei due sensi: 3D Omniverse su una copia, database sul clone.
- **Database di sviluppo `aicompany`**: alla **V25**, non toccato. Al primo avvio del backend su questo
  branch riceve V26 (PHASE 28), V27 e il seed V3. Il clone `aicompany_p29` resta in Docker.
- **Debiti**: TD-64 aperto e chiuso nella fase; aperti TD-65…TD-67; prossimo libero **TD-68**.
  Handoff: `docs/handoff/FINAL_HANDOFF_PHASE_29.md`.

## ▶▶▶▶ PHASE 28 — Project Blueprint — COMPLETATA il 2026-09-29, integrata in `master` (`f4c69b3`)

> Sostituita, dove diverge, da PHASE 29 qui sopra.

- **Direttiva** del 2026-09-29: per ogni progetto una scheda visuale «Blueprint» a blocchi e porte,
  sincronizzabile con il codice, modificabile, senza mai riscrivere codice in modo implicito.
  Decisioni in **ADR-033** (estende ADR-030, non lo sostituisce: la scheda *Codice* resta).
- **Branch**: `autonomous/phase-28-project-blueprint`, da `autonomous/phase-15-hardening` (`e097aa2`),
  **non pushato** (la direttiva non autorizza push: charter hard stop #4). `master` intoccato.
  Il blocco 15→27 resta in attesa della sua review: questa fase gli sta sopra in linea retta.
- **Modello** (`V26`, additiva, 5 tabelle): `blueprint_nodes` (21 tipi, origine DETECTED/MANUAL,
  stato, `path_state`, struttura JSON, istantanea rilevata, override), `blueprint_edges` (19 relazioni,
  origine DETECTED/MANUAL/PROPOSED, riferimento al codice), `blueprint_layouts` (posizioni separate dai
  nodi), `blueprint_scans`, `blueprint_change_proposals`.
- **API**: 24 operazioni sotto `/api/projects/{id}/blueprint` e `/api/blueprint/vocabulary`
  (`docs/RUNNING.md`), `If-Match` su ogni mutazione di riga esistente, contratto OpenAPI solo additivo.
- **Scanner** a plugin: Java con **AST** (JavaParser 3.28: stereotipi Spring, iniezione, chiamate,
  endpoint con rotte da costanti, DTO, codici di stato, pseudocodice, test ↔ endpoint), TypeScript,
  Python e SQL lessicali. Sul repository stesso: 469 file → 614 blocchi, 2289 relazioni, 0 non risolte.
- **Mai codice scritto**: CREATE_FILE / ADD_DEPENDENCY / CUSTOM producono diff; accettare crea una task
  (governance esistente). Le azioni AI sono task. Test d'invariante: hash dei file identici.
- **Console**: scheda *Blueprint* (React Flow 12, caricata in lazy): canvas con pan/zoom/drag/selezione
  multipla/minimappa, livelli di dettaglio per zoom, ricerca, filtri per livello/famiglia/origine,
  metriche, modalità Lettura/Modifica, Inspector con provenienza dei campi, nuovo blocco, relazioni per
  trascinamento, proposte con diff, visualizzatore di file, auto-layout del server.
- **Suite**: Java **514** (513 verdi + 1 saltato su Windows) · engine **58** · console **41**.
  Mutazioni: **11/11 rosse** (10 backend sulle guardie del Blueprint, 1 console su `If-Match`).
- **Trovato e corretto fuori scope, con test**: `OrchestrationService.model()` andava in 500 con l'AI
  Engine spento (commit `0121c26`).
- **Debiti nuovi**: TD-57…TD-63; prossimo libero **TD-64**.
- **Prossimo passo autonomo**: nessuno — fermo per la Human Final Review (`FINAL_HANDOFF.md`, sezione
  PHASE 28).

## ▶▶▶ Consolidamento — PHASE 15 → PHASE 27 — COMPLETATO il 2026-09-25, integrato in `master` (`f4c69b3`)

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

- **Merge autorizzato**: il 2026-09-25 l'operatore ha accettato PHASE 3–14 («esegui il merge»):
  `master` è passato da `d166870` a **`e9c928c`** con un **fast-forward** (nessun merge commit,
  nessun force). Il charter §8 è stato rispettato: merge eseguito solo su autorizzazione esplicita.
- **Branch del blocco**: `autonomous/phase-15-hardening`, da `e9c928c`, pushato; **`master` resta a
  `e9c928c`** fino alla prossima Human Final Review.
- **Fasi** (numerazione in `.company-os/ROADMAP_V2.md` §5): 15 sicurezza (ADR-024) · 16–18 legame
  Agente → Modello → Provider → Execution Target, handoff senza API OpenAI/Anthropic, UX di
  assegnazione (ADR-025) · 19 skill come file e Knowledge Hub (ADR-026) · 20 eliminazione reale
  (ADR-027) · 21 avvio coordinato dell'ecosistema (ADR-028) · 22 terminale integrato (ADR-029) ·
  23 grafo del codice (ADR-030) · 24 governo dei costi (ADR-031, chiude TD-40) · 25 reference e
  dipartimento 3D (ADR-032) · 26 integrazioni con credenziali: **decisione umana, non iniziata** ·
  27 verifica end-to-end e correzioni.
- **Suite**: **470 Java** (469 verdi + 1 saltato su Windows) + **58 engine** + **27 console**.
  Stream **`V24`** (V20–V24 additive); seed di sviluppo **`V2`**.
- **Smoke dal vivo** (2026-09-25, clone `aicompany_p8`, engine reale, Ollama reale): **31/31**
  controlli, incluse due run DeepSeek Coder V2 (45 s e 80 s) e il percorso Piano → Fase → Task →
  Agente → Modello → Execution Target → Risultato → Review con una skill nel prompt.
- **Mutazioni**: 7 sulle nuove guardie (legame, budget, conferma, ruoli, ticket del terminale, SSRF,
  blocco del login): **tutte rosse**.
- **Segreti**: `%USERPROFILE%\.aicos\local.env` creato sulla macchina dell'operatore (fuori dal
  repository, ACL solo utente). Il DB live `aicompany` è ancora a `V11` e **non è stato toccato**:
  al primo avvio riceverà V12–V24 e l'admin con la password di quel file.
- **Debiti nuovi**: TD-53…TD-56; chiusi TD-40, TD-42; TD-46 chiuso in parte; prossimo libero **TD-57**.

## ▶▶ Riallineamento V2 — PHASE 8 → PHASE 14 — COMPLETATO il 2026-09-24, integrato in `master` il 2026-09-25

> **Leggere prima questa sezione.** Sostituisce, dove diverge, tutto ciò che segue.

Direttiva umana del 2026-09-24: PHASE 3–7 verificate e funzionanti dall'operatore, **ma non da
mergiare**; riallineare il prodotto alla visione completa dell'ecosistema, preservando ciò che
funziona, e proseguire autonomamente. Gap analysis: `docs/realignment/GAP_ANALYSIS_V2.md`. Roadmap:
**`.company-os/ROADMAP_V2.md`** (PHASE 8–21, MU-OS a fine PHASE 11).

| Fase | Contenuto | ADR | Stato |
|---|---|---|---|
| **8** Ecosystem catalogs | Software Hub (rilevamento reale, launcher ad allowlist), provider/modelli, consumi e quote, OpenRouter, porta 8081, nuova shell | 018, 019 | ✅ |
| **9** Project workspace | Profilo progetto, cartella governata, documenti sicuri | 020 | ✅ |
| **10** Planning | `plan.json`, generazione a stadi vincolata da JSON Schema, import da agente esterno, gate HITL | 021, 022 | ✅ |
| **11** Execution | Decisione motivata, handoff a Claude Code/Codex/Antigravity/OpenCode, review, contesto nelle run | 021, 022 | ✅ **MU-OS** |
| **12** Agent ecosystem | Prompt engineering, sottoagenti, harness, Knowledge Hub, template di agenti, Mockup Hub | 023 | ✅ |
| **13** Daily Work | Oggi/domani, riferimenti a task senza copie | — | ✅ |
| **14** Second Brain | Grafo animato dell'ecosistema | — | ✅ |
| 15–21 | Terminale PTY, graph engineering, template hub, integrazioni esterne, costi, 3D, release | — | da fare |

- **Branch**: `autonomous/phase-8-ecosystem-realignment`, da `autonomous/phase-7-operator-console`
  (che resta intatto). **Pushato**; CI su ogni push. **`master` intoccato** (`d166870`).
- **Suite**: **417 Java** (416 verdi + 1 saltato: link simbolici su Windows senza privilegi) + **56 engine** +
  **18 console**. Stream **`V19`** (V12–V19 tutte additive).
- **Live dev DB `aicompany`**: **`V11`** (lo ha migrato l'operatore durante la sua review di PHASE
  3–7 — la riga «V3» più sotto è superata). Al prossimo avvio si applicheranno V12–V19, additive.
  Tutte le verifiche dal vivo di questo blocco sono girate sul clone `aicompany_p8`.
- **Smoke reale MU-OS** (2026-09-24, clone): progetto → workspace → master prompt → piano generato
  da `qwen3.5:9b` in 12,8 min (5 fasi, 17 task) → gate 409 prima dell'approvazione → approvazione →
  decisione → run `qwen3.5:4b` in 226 s con contesto dai file e istruzioni GUIDED rispettate →
  review → DONE.
- **Mutazioni**: 5 sulle guardie critiche; 3 rosse subito, **2 sopravvissute** (test che non
  provavano ciò che dichiaravano) → test aggiunti, entrambe rosse.
- **Debiti nuovi**: TD-41…TD-52 (`docs/DEBT_REGISTRY.md`); prossimo libero **TD-53**.
- **Prossimo passo autonomo**: fermo per la Human Final Review (`FINAL_HANDOFF.md`). Dopo:
  PHASE 15 (terminale PTY integrato), poi l'ordine di `ROADMAP_V2.md`.

## ▶ Blocco autonomo PHASE 3 → PHASE 7 — COMPLETATO il 2026-09-23, integrato in `master` il 2026-09-25

> **Leggere prima questa sezione.** Il resto del file descrive lo stato fino a TASK-012 ed è
> conservato; dove questa sezione e il resto divergono, **vale questa**.

Autorizzazione umana esplicita del 2026-09-23: completare almeno cinque fasi consecutive senza
fermarsi a fine task o fase, **push su `origin` e uso della CI autorizzati**, una sola Human Final
Review alla fine. `master` resta il checkpoint umano e **non viene toccato**. Sequenza e
motivazione: **`.company-os/ROADMAP_PHASES_3_7.md`**.

| Fase | Integration branch | Task | Stato |
|---|---|---|---|
| **PHASE 3 — Security baseline** | `autonomous/phase-3-security` (da `master` `d166870`) | TASK-013, TASK-014 | ✅ **completata** |
| **PHASE 4 — Task lifecycle** | `autonomous/phase-4-task-lifecycle` (da phase-3) | TASK-015, TASK-016 | ✅ **completata** |
| **PHASE 5 — AI Engine** | `autonomous/phase-5-ai-engine` (da phase-4) | TASK-017, TASK-018 | ✅ **completata** |
| **PHASE 6 — Execution** | `autonomous/phase-6-execution` (da phase-5) | TASK-019, TASK-020 | ✅ **completata** |
| **PHASE 7 — Operator console** | `autonomous/phase-7-operator-console` (da phase-6) | TASK-021, TASK-022 | ✅ **completata** |

**Il blocco è chiuso. L'agente è fermo per la Human Final Review: `FINAL_HANDOFF.md`.** L'ultimo
integration branch, `autonomous/phase-7-operator-console`, contiene tutte e cinque le fasi in linea
retta sopra `master` (`d166870`); un solo fast-forward le integrerebbe tutte. **`master` non è stato
toccato.**

**Suite a fine blocco: 394 test verdi** — **329** Java (`cd backend && ./mvnw -B clean test`), **51**
AI Engine (`cd ai-engine && .venv/Scripts/python -m pytest`), **14** console (`cd frontend && npm test`),
tre job CI. **Stream: `V11`** (`V9` priority, `V10` `task_runs`, `V11` `agents.model`).
**Prossimo debito libero: `TD-41`** (`docs/DEBT_REGISTRY.md`). Avvio dell'intero stack:
`.\scripts\start-dev.ps1`.
**Live dev DB: `V3`**, verificato il 2026-09-23 e **non toccato**: gli smoke test girano su un
clone (`aicompany_smoke`, `CREATE DATABASE … TEMPLATE aicompany`).

### PHASE 3 in sintesi
- **TASK-013 — TD-04 chiuso** (ADR-013). Ogni `/api/**` richiede `Authorization: Bearer <token>`;
  token configurati per nome (`aicos.security.api-tokens.<nome>`), il nome è il principal. `401`
  dentro ADR-007 (`urn:ai-company-os:problem:unauthenticated`), identico per token assente e
  sbagliato, **prima** di `404` e `428`. Fail closed all'avvio. Solo `/actuator/health` pubblico.
  Verifica **per riflessione su ogni rotta**. Nessun test esistente modificato: il `MockMvc`
  condiviso porta il token dell'operatore.
- **TASK-014 — TD-11, R5, R7 chiusi.** CORS da origini dichiarate (no `*`), `ETag`/`Location`
  esposti, `If-Match` ammesso, niente *credentialed*; backend `dev` su `127.0.0.1`; `.env.*`
  ignorato.
- **Lezione sull'harness di mutazione**: il primo giro ha dato cinque «rossi» falsi perché il
  wrapper Maven non partiva. L'harness ora rifiuta un verdetto senza output di Surefire.

### PHASE 4 in sintesi
- **TASK-015 — TD-37 chiuso** (ADR-014). `POST /api/tasks/{id}/start|complete|stop|reopen`,
  `If-Match`. `TaskStatus` è il vocabolario, `TaskTransition` la macchina (quattro archi). Regole
  sull'entità: congelato → arco → (solo `start`) agente presente e attivo. Uscire da `IN_PROGRESS`
  non dipende mai dall'agente (D3 regge). Invariante nuovo **`IN_PROGRESS` ⇒ agente**, protetto
  finché TD-35 resta aperto: chi lo chiude deve rifiutare i task `IN_PROGRESS`. Apre **TD-38**.
- **TASK-016 — TD-36 chiuso** (e con esso TD-12). `PUT /api/tasks/{id}` (dettagli soltanto),
  `priority ∈ {LOW, MEDIUM, HIGH}` con censimento (`tasks/TASK-016/CENSUS.md`: solo `HIGH`/`LOW`,
  live DB `LOW`) e **`V9`**, `GET /api/tasks?status=`.
- Lezione: un test di concorrenza a barriera **non** forza l'interleaving (M7 sopravvissuta);
  sostituito da transazione esterna + latch.

### PHASE 5 in sintesi
- **TASK-017** (ADR-015). `ai-engine/`: FastAPI, contratto **v1** (`POST /v1/completions`,
  `GET /v1/models`, `GET /health`), token fra servizi a tempo costante, problem details
  `urn:ai-company-os:engine:problem:*`, correlation id, campi sconosciuti rifiutati, provider
  `echo` deterministico. Bind `127.0.0.1:8090`. Job CI `ai-engine` (Python 3.12).
- **TASK-018**. Provider `ollama` (locale, attivo per default, «non disponibile» se spento) e
  `anthropic` (SDK ufficiale, `claude-opus-5`, `fallbacks: "default"`, **spento senza
  `ANTHROPIC_API_KEY`**). Nessun test raggiunge rete o servizi a pagamento.
- **Smoke reale**: quattro modelli Ollama installati su questa macchina; completion reale con
  `ollama:llama3.2:3b` (13 s). Nessuna chiamata cloud eseguita.
- Il runtime a grafo (LangGraph o alternative) **resta non deciso**, come ADR-001 lo ha rinviato.

### PHASE 7 in sintesi
- **TASK-021** (ADR-017 §2). springdoc 3.1.1; `docs/api/openapi.json` tenuto **byte per byte** al
  codice da `OpenApiContractTest`; `/v3/api-docs` autenticato; `GET /api/engine/models`.
- **TASK-022** (ADR-017). `frontend/`: React 19 + TS + Vite, tipi **generati** dal contratto (la CI
  rifiuta una deriva), board a tre colonne, drawer del task (archi, routing, assegnazione, run con
  scelta del modello, output e prompt), agenti con modello, progetti, modelli dell'engine. `412`
  mai ritentato. `scripts/start-dev.ps1`. README riscritto: **TD-17, TD-18 chiusi**.
- **Verifica dal vivo** nel browser integrato: ha trovato che `/actuator/health` non aveva CORS
  (la console avrebbe segnato «down»); corretto e pinnato. Il login autenticato nel browser **non**
  è stato eseguito dall'agente (nessun token digitato in un campo): è il primo passo della review.

### PHASE 6 in sintesi
- **TASK-019** (ADR-016, `V10`). `POST /api/tasks/{id}/runs` (If-Match del task, `202`),
  `GET /api/runs/{id}`, `GET /api/tasks/{id}/runs`. Lancio col protocollo del task; `OPEN` →
  `IN_PROGRESS` via `START`; una run non finita per task; esecuzione **dopo il commit**, fuori
  transazione, executor limitato; ogni run finisce con un tipo; recupero `interrupted` all'avvio.
  **Una run riuscita non completa il task** (human in the loop). Apre **TD-39**, **TD-40**.
- **TASK-020** (`V11`). **TD-08 chiuso**: `MasterOrchestrator` e `POST /api/orchestrator` rimossi;
  routing lessicale sul registro (`GET /api/tasks/{id}/agent-suggestions`,
  `POST /api/routing/suggestions`); `agents.model`.
- **Primo smoke end-to-end reale**, su clone del DB live: task → agente con
  `ollama:llama3.2:3b` → run `SUCCEEDED` in 24 s con output reale. Ha trovato un falso positivo del
  routing (`post` ↔ `postgresql`), corretto e pinnato.

## Current phase
**Nessuna in corso.** Il blocco PHASE 3–7 è completo (sezione ▶ qui sopra); piani in
`.company-os/PHASE_{3..7}_PLAN.md`, sequenza in `ROADMAP_PHASES_3_7.md`.

*Storico:* PHASE 2 — Assignment (`PHASE_2_PLAN.md`), accettata e in `master` dal 2026-09-19.

## Current task
**Nessuna.** L'ultima è TASK-022. Fermo per la Human Final Review del blocco (`FINAL_HANDOFF.md`).

*Storico, precedente al blocco:* **TASK-012** (2026-09-19) ha affrontato i due debiti che precedevano PHASE 3, su richiesta umana:

- **TD-31 — CHIUSO.** Il cambiamento distruttivo è stato autorizzato esplicitamente da un umano,
  delimitato allo scope documentato. `V8` elimina `agents.active`;
- **TD-14 — CHIUSO il 2026-09-23.** Il remote è configurato
  (`https://github.com/Gabryx82/Ai-Company-OS.git`), `master` è stato pushato senza force e senza
  riscrivere storia (`14f70b3`, `origin/master` allineato, divergenza `0 0`), il workflow `CI` è
  **registrato e attivo** su GitHub, e la run **`35863517006` si è conclusa `success`**: 9 step su
  9 verdi su `ubuntu-latest`, `./mvnw -B clean test` incluso, in 1m26s, con l'artefatto
  `surefire-reports` caricato. **La CI ha girato davvero** — che è la sola evidenza con cui questo
  debito poteva chiudersi. Percorso completo, compresi i quattro fallimenti di autenticazione che
  lo hanno preceduto, in `tasks/TASK-012/EVIDENCE_TD14.md` §7-11.

*(Stato al 2026-09-19. Da allora TD-04 è chiuso da TASK-013 e TD-37 da TASK-015.)* **TD-04** e
**TD-37** restavano aperte: erano i due candidati di PHASE 3.

- **TD-04 — autenticazione.** Già indicato come primo candidato di PHASE 3 da `PHASE_2_PLAN.md`
  §2. Oggi non c'è niente, e ogni endpoint aggiunto è superficie. Costo noto: cambierebbe ogni
  test di API;
- **TD-37 — le transizioni di `status`.** È ciò che sblocca **TD-08** e il Planner: un task sa
  dove sta, di chi è e in che stato è, ma **non può cambiare stato**, e un orchestratore che non
  muove il lavoro non ha niente da orchestrare.

Argomenti raccolti in `tasks/TASK-011/HANDOFF.md`.

## Last completed task

**TASK-012 — TD-31 chiuso, TD-14 bloccato su evidenza** (2026-09-19).

Due debiti indipendenti, due esiti diversi, commit separati. Suite **219 → 223**, schema
**`V7` → `V8`**. Nessun debito nuovo.

### TD-31 — chiuso

`agents.active BOOLEAN` diventa `agents.status VARCHAR(32)` con `agents_status_check`, e la
colonna booleana **viene eliminata**. È la **prima migrazione distruttiva** dello stream, ed era
hard stop #3 del charter: sciolto da una decisione umana esplicita il 2026-09-19, delimitata allo
scope documentato.

**Chiuso perché qualcuno ha deciso, non perché il costo fosse cresciuto.** ADR-008 §2 aveva
*sospeso* questo cambiamento, non lo aveva sbagliato, e ADR-012 lo dice invece di riscrivere la
storia.

| Decisione | Contenuto |
|---|---|
| **Il backfill è una biiezione** | `TRUE`↔`ACTIVE`, `FALSE`↔`INACTIVE`. Totale e iniettiva in entrambe le direzioni; `active` è `NOT NULL` da `V1`, quindi **nessun terzo caso da decidere e nessun mapping da inventare**. Irreversibile è la *forma*, non il contenuto |
| **Il contratto pubblico non cambia di un byte** | `AgentResponse` porta `active` **e** `status` dalla TASK-007: `V8` inverte quale dei due è reale. JSON identico, `?active=` resta booleano |
| **`INACTIVE`, non `ARCHIVED`** | Invariato da ADR-008 §2. TD-31 unifica la **forma** — enum chiuso più `CHECK` — non il vocabolario |
| **Niente indice, niente terzo stato, `active` resta nella risposta** | Fuori scope, elencati in ADR-012 §7 |

**La conseguenza che il piano non aveva previsto**: il dev seed **nomina** la colonna che `V8`
elimina, quindi su un database nuovo sarebbe fallito — e modificarlo cambia il checksum, che
Flyway valida, quindi ogni database già seminato (**compreso quello di questa macchina**) avrebbe
rifiutato di avviarsi in `dev`. `V4` aveva evitato la stessa famiglia di collisione con un
`DEFAULT`, e qui quella via non esiste: un `DEFAULT` non aiuta un `INSERT` che nomina una colonna
scomparsa. Risolto con `repair()` in `DevSeedFlyway.apply` — solo metadati, e **da non copiare
sullo stream dello schema**, dove il fallimento rumoroso è voluto (ADR-012 §5).

**Verificato sui dati reali, non solo sui fixture.** Il database di sviluppo di questa macchina
(a `V3`) è stato **clonato** per non toccarlo, e sul clone è stata avviata l'applicazione: `V1→V8`
applicate, `active` assente, 3 agenti `t` → 3 `'ACTIVE'`, nessuna riga persa, checksum del seed
riparato senza ri-eseguirlo, avvio riuscito (quindi Hibernate `validate` passa), e
`GET /api/agents` che risponde `{"active":true,"status":"ACTIVE"}`. Clone eliminato; **il database
reale è ancora a `V3`**.

**Due mutazioni, entrambe rosse**, con albero verificato pulito prima e dopo ciascuna. La prima
conta: un backfill che scrive `'ACTIVE'` incondizionatamente rende rosso il test — che è il motivo
per cui quel test semina **entrambi** i valori, perché con soli agenti attivi sarebbe passato e la
biiezione sarebbe stata affermata senza essere verificata. La seconda riproduce esattamente il
fallimento che il database reale avrebbe avuto senza `repair()`.

### TD-14 — aperto, e il blocco è un dato, non una decisione

Quattro fonti verificate, tutte negative: **nessun remote** configurato né mai esistito (nessun
`refs/remotes`, nessun `FETCH_HEAD`, `.git/config` senza sezione `[remote]`); **nessun URL, owner
o nome di repository** in alcun file tracciato o non tracciato; **`gh` non installato**; **nessuna
CI** presente in alcuna forma.

Le sole occorrenze di «origin» nel repository sono la parola italiana **«origine»** nella prosa su
CORS e sui lock, più `originalTag` in `Precondition.java`.

**Manca esattamente l'URL del remote**, e non è deducibile. Non è stato scritto nemmeno un
workflow CI: scegliere GitHub Actions presuppone GitHub, e la piattaforma è parte della
destinazione mancante.

Artefatti: `tasks/TASK-012/*`, `docs/adr/ADR-012-agent-lifecycle-unification.md`.

## Task precedenti

**TASK-011 — Registro del debito e documentazione operativa** (2026-09-19).

Livello 6, ultima task di PHASE 2. Suite **216 → 219**. **Nessuna modifica al codice
applicativo, nessuna migrazione**, e nessun debito nuovo: prossimo id libero **`TD-38`**.

**La collisione di identificatori era reale e ha una forma precisa.** Cinque `TD-NN` significano
cose diverse nei due registri — **`TD-14`, `TD-19`, `TD-20`, `TD-21`, `TD-22`** — mentre
`TD-01`…`TD-13` e `TD-15`…`TD-18` coincidono. È questo a renderla insidiosa: chi ne verifica due o
tre conclude che il problema non esista. «`TD-20` è chiuso» era vero in uno spazio e falso
nell'altro, e niente diceva quale si stesse usando.

**Risolta in modo additivo, non rinumerando.** Rinumerare i vivi romperebbe ogni riferimento in
ADR, artefatti e commit; rinumerare l'audit riscriverebbe ciò che TASK-000 **osservò**, e uno
snapshot che si aggiorna non è più uno snapshot. `docs/DEBT_REGISTRY.md` mappa i due spazi per
intero e dichiara autoritativo il vivo; `docs/audit/TECHNICAL_DEBT.md` lo dice sulla prima
schermata.

**La decisione più difficile è stata non dichiarare chiusi otto debiti.** `TD-01`, `TD-02`,
`TD-03`, `TD-05`, `TD-06`, `TD-09`, `TD-10`, `TD-16` esistono solo nell'audit e diversi sono con
ogni evidenza risolti — H2 rimosso, Flyway padrone dello schema, DTO, 219 test dove l'audit
contava zero. **Non marcati chiusi**: «con ogni evidenza risolto» *è* «il codice sembra diverso»,
che il charter vieta come criterio. Il registro dice «non rivalutato qui», che è vero, e la
rivalutazione è registrata come task successiva.

**`docs/RUNNING.md` conteneva due istruzioni false, non due lacune**, ed è la differenza che
conta — una documentazione incompleta fa perdere tempo, una falsa fa sbagliare:

1. «*La prossima migrazione di schema si chiama `V2__...sql`*», con lo stream a **`V7`**: chi la
   seguiva creava una migrazione che **Flyway rifiuta**. Sostituita dalla regola, dal valore di
   oggi e dal **comando per leggerlo**, perché è proprio un valore scritto a mano che è andato
   stantio;
2. «*deve contenere **solo** `1 | V1__create_agents_and_tasks.sql`*», vero appena dopo TASK-001A e
   falso da `V2`. Ciò che si verifica è l'**assenza** della riga legacy `1000`.

Entrambe portano una nota che dice cosa affermavano prima: qualcuno può averci agito.

Documentati **18 endpoint invece di 3**, e messo **per primo** ciò che mancava del tutto: **ogni
mutazione richiede `If-Match`**, quindi un `428` è un header mancante e non un server rotto.

**Un test per un problema di documentazione**, perché il charter chiede una verifica che fallisca
se il difetto torna: `DebtRegistryConsistencyTest` rende rossa la suite se un identificatore
dell'audit smette di comparire nel registro. È l'unico test che legge file fuori dal proprio
modulo, e **fallisce invece di saltare** quando non li trova — una guardia sulla documentazione
che passa perché non ha trovato la documentazione è peggio di nessuna guardia.

Artefatti: `tasks/TASK-011/*`, `docs/DEBT_REGISTRY.md`.

## Task precedenti di PHASE 2

**TASK-010 — Vocabolario chiuso di `Task.status`** (2026-09-19).

Chiude **la metà `status` di TD-12**, apre **TD-36** e **TD-37**. Suite **201 → 216**, schema
**`V7`**. Da oggi un task sa **in che stato è** in un modo che qualcosa può verificare.

**Il censimento ha cambiato la natura della task, e viene prima di tutto il resto.** TASK-009
aveva avvertito che stringere `status` non è additivo se i dati contengono valori fuori
vocabolario, e che in quel caso la domanda tocca gli hard stop #2 e #3. Contare prima è ciò che ha
reso la task eseguibile in autonomia. Cinque fonti — schema, dev seed, fixture, database locale,
storia git — **un solo valore (`OPEN`), zero righe da trasformare, nessun hard stop**.
Riproducibile con i comandi in `tasks/TASK-010/CENSUS.md`.

Tre fatti che il censimento ha trovato e che nessun documento diceva:

1. **il database di sviluppo reale è a `V3`**, non alla testa dello stream: non ha mai visto `V4`,
   `V5` né `V6`. Lo stato lo diceva in modo impreciso, ed è corretto più sotto;
2. **`Task.status` non ha alcun percorso di mutazione** — un solo scrittore, la creazione, e
   nessun ramo del codice che legga il valore per decidere. Questo ha ristretto lo scope: la task
   chiude il **vocabolario**, non il ciclo di vita, e lo dichiara (**TD-37**);
3. **`MigrationStreamTest` era già l'upgrade test di `V6 → V7`**, scritto da TASK-006 per
   migrazioni che allora non esistevano. È un vincolo reale sulla scelta: un vocabolario che
   avesse escluso `OPEN` lo avrebbe reso rosso a ogni coppia.

| Decisione | Contenuto |
|---|---|
| **Il vocabolario è `OPEN`, `IN_PROGRESS`, `DONE`** | `OPEN` preservato **verbatim**. Esclusi e dichiarati `BLOCKED`, `CANCELLED`, `IN_REVIEW`, `DRAFT`, `PAUSED` — plausibili, nessuno richiesto: è testualmente l'argomento di ADR-004 §2 |
| **Un vocabolario non è una macchina a stati** | Nessuna transizione. `DONE` è legale alla creazione, e un test lo pinna, così una task futura non può aggiungere «il lavoro comincia aperto» credendo di fare pulizia |
| **Il campo della request resta `String`** | Tipizzarlo come enum farebbe fallire Jackson → `malformed-request`, «*the request body could not be read*», che è **falso**, e senza `errors`. Contratto di TASK-005 **invariato**: nessun `type` nuovo, `ApiExceptionHandler` intatto |
| **Case-sensitive** | `"open"` è fuori quanto `"banana"`. Scelta **opposta** a ADR-008 sui nomi degli agenti: un nome lo digita una persona, uno stato lo manda un programma |
| **Tre guardie, non due** | ADR-004 §2 ne voleva due; fra client e dominio c'è un livello che i progetti non avevano |
| **`V7`** | Non additiva **per costruzione** — applica un vincolo a dati esistenti — ma additiva **su questi dati**: zero righe lette, scritte o riscritte |

**Quattro mutazioni, tutte rosse, e due hanno insegnato qualcosa.** Albero verificato pulito prima
di ogni mutazione e dopo ogni revert, per la lezione di TASK-009.

- **M2** (tolto il `CHECK`) ha **riprodotto empiricamente** la previsione di ADR-004 §2: un `'open'`
  scritto in SQL grezzo entra in tabella e fa poi fallire **la lettura di ogni task** —
  `No enum constant ... TaskStatus.open` — dentro un `@BeforeEach`, cioè il più lontano possibile
  dalla causa;
- **M4** (`ORDINAL` invece di `STRING`) è più forte di quanto il javadoc dichiarasse: il contesto
  Spring **non parte**, perché `validate` rifiuta un mapping ordinale su colonna `varchar`.

**La review ha trovato un test che prometteva più di quanto verificasse**, e la mutazione è ciò
che lo ha smascherato: `theCheckConstraintDeclaresTheSameSetAsTheEnum` confrontava il vincolo con
una costante, non con l'enum, ed è rimasto **verde** mentre l'enum veniva allargato. Corretto il
**nome**, non il disegno — confrontare ogni guardia con un insieme scritto indipendentemente è più
forte che confrontarle fra loro, perché il confronto diretto passerebbe il giorno in cui qualcuno
cambia entrambe e non decide nessuna delle due.

**Rotture dichiarate**: `status` fuori vocabolario passa da `201` a `400`; `"open"` idem; un
`INSERT`/`UPDATE` SQL fuori vocabolario è rifiutato dal database; `V7` **fallisce** su un database
che contenesse valori fuori vocabolario — comportamento voluto, precedente esatto di `V4` con
`agents_name_unique_idx`, e il test verifica anche che la riga resti **intatta**. **JSON
invariato.**

Artefatti: `tasks/TASK-010/*`, `docs/adr/ADR-011-task-status-closed-vocabulary.md`.

**TASK-009 — Assegnazione `Task` → `Agent`** (2026-09-17).

Risolve **TD-13** (numerazione dell'audit), apre **TD-34** e **TD-35**. Suite **179 → 201**, schema
**`V6`**. Da oggi il sistema sa dire **chi lavora su che cosa**.

**Le tre domande di dominio, e dove il dominio ha divergito.** Nessuna risposta copiata da
`Task` → `Project`:

| # | Domanda | Risposta |
|---|---|---|
| **D1** | Assegnare a un agente disattivato? | **`409`** — stesso esito, **argomento diverso**: non contenimento ma responsabilità, un'obbligazione che nessuno può assolvere |
| **D2** | Cambiare agente a un task in un progetto archiviato? | **`409`**. Prima verifica della frase generale di ADR-006 §2, e regge — ma «per costruzione» **non è automatico**: vale perché la guardia è stata estratta in `Task.requireNotFrozen()` e il percorso nuovo la chiama |
| **D3** | Disattivare un agente fa qualcosa ai suoi task? | **Nessuna scrittura sui figli, e la regola derivata è l'OPPOSTA di quella dei progetti.** Un task il cui agente è inattivo **non è congelato**: congelarlo lo intrappolerebbe con chi non può eseguirlo, proprio quando serve riassegnarlo |

**Il lock graph è stato ridimostrato, non esteso.** **L5′**: `tasks` → `projects` → `agents`. Tre
archi, ordinamento topologico, e i tre archi **assenti** verificati uno per uno — perché è quella
la metà che può essere sbagliata. Risultato:

> L'aciclicità è **una conseguenza** di ADR-006 §1 e di **D3**, non una proprietà indipendente.

L'alternativa scartata (D3bis: far fallire `deactivate` finché ha lavoro) avrebbe introdotto
`agents → tasks` e **chiuso un ciclo**. Il dominio e la concorrenza rispondono la stessa cosa.

**Una sola versione.** `agent_id` è una colonna di `tasks`: assegnare consuma l'ETag del task, e
chi cambia il progetto invalida il tag di chi sta per cambiare l'agente — `200` e `412`, non due
`200`. Nessun versionamento separato dell'associazione. `P3` nell'altra direzione: assegnare **non**
muove `agents.version`.

**Quattro mutazioni**, tutte rosse — ma **due hanno dovuto essere riparate prima di provare
qualcosa**, ed è la parte che vale la pena leggere (`tasks/TASK-009/ARTIFACT.md` §5 e §7):

- quella su P4 fermava la build invece di raggiungere il proprio test. Una mutazione che non
  compila non è una mutazione;
- quella sul lock del progetto **non sapeva revertirsi** — sostituiva un blocco con la stringa
  vuota — e ha lasciato l'albero mutato. Ne sono seguite **due diagnosi sicure e sbagliate** di
  un'implementazione che era corretta, la seconda costruita su un log SQL vero ma raccolto da un
  albero corrotto.

La lezione è registrata perché tornerà: quando un test fallisce su codice che si crede pulito, la
prima ipotesi da verificare è **che l'albero sia pulito davvero** — non che il test sia instabile,
non che il framework si comporti in modo esotico. L'harness adesso si rifiuta di indovinare.

Corretto lungo la strada e tenuto: `awaitOrFail` distinto da `awaitAtMost` nei test di concorrenza
(un'attesa limitata dove serve un requisito produce sia rossi falsi sia **verdi falsi**), anche in
`PreconditionConcurrencyTest`, che aveva ereditato il difetto da TASK-008.

Nessuna rottura di contratto: tutto additivo.

Artefatti: `tasks/TASK-009/*`, `docs/adr/ADR-010-task-agent-assignment.md`.

**TASK-008 — Optimistic concurrency nel contratto HTTP** (2026-09-16).

Chiude **TD-28** e **TD-30**, apre **TD-32** e **TD-33**. Suite **158 → 179**, schema **`V5`**.

I lock di ADR-006 serializzano ma non rilevano. Adesso il sistema fa entrambe le cose, con due
meccanismi che **non si sostituiscono**: L0–L7 è consistenza interna, `ETag`/`If-Match` è intento
stantio del client. Il lock rende **atomico** il confronto, il confronto rende **visibile** la
staleness.

| Decisione | Contenuto |
|---|---|
| **`@Version` è il contatore, non il rilevatore** | Dopo l'attesa su `PESSIMISTIC_WRITE` l'entità è caricata **già alla versione nuova**, quindi `OptimisticLockException` non arriva mai. **Nessun handler per essa**, e non va aggiunto: sarebbe un `412` che non scatta |
| Protocollo **P0–P4** | `If-Match` obbligatorio su ogni mutazione di risorsa esistente; confronto **dentro la transazione, dopo il lock, prima delle regole**; la versione conta la propria riga; clausola di chiusura ereditaria |
| `428` / `412` / `400` | Assente / stantio / illeggibile o `*`. Tre `type` nuovi, dentro ADR-007 senza eccezioni |
| Percorso canonico | **`GET /api/tasks/{id}`**, introdotto perché non esisteva. `ETag` anche su creazioni e mutazioni. Mai sui listati (TD-33), mai nei corpi JSON |
| `V5` additiva | `version BIGINT NOT NULL DEFAULT 0` su tutte e tre le tabelle. Il `DEFAULT` è la lezione di `V4` |
| Tutte e tre le risorse | Non solo le due rotte del debito: una precondizione con buchi non è una precondizione (L7 è il precedente) |
| **P4 eseguibile** | `PreconditionCoverageTest` pinna l'insieme dei write path: aggiungerne uno obbliga a decidere sulla precondizione |

**Rotture dichiarate.** Ogni mutazione senza `If-Match` passa da `200` a `428` — la rottura più
grande della fase, e deliberata: con `If-Match` facoltativo il debito non si chiude, si rende
*evitabile*. Il `PUT` idempotente con tag stantio passa da `200` a `412`. `DELETE /api/tasks/{id}`
da `404` a `405`.

**Una rottura che il piano non aveva previsto**, trovata dai test di concorrenza di TASK-004 e
TASK-007: il **perdente di due transizioni concorrenti vede `412` dove vedeva `409`**. La
transizione illegale resta ciò che riceve un chiamante **aggiornato**, esercitata sequenzialmente.
ADR-009 §8.

**La verifica per mutazione ha smentito due affermazioni dei documenti**, e sono state corrette, non
difese:

1. spostare il confronto **sotto** le regole **non** rende rosso il caso idempotente —
   `Task.assignTo` esce presto da **sé**, non dal service. Il test di I-3 non discriminava niente.
   Ciò che la posizione decide è **quale rifiuto** riceve un chiamante stantio, e c'è un test nuovo
   che lo asserisce;
2. spostarlo **sopra** il lock non perde la riga: Hibernate solleva `StaleObjectStateException`.
   Perde la **risposta** — `500` invece di `412`. Registrato in ADR-009 §2.3 come rete, non come
   meccanismo.

Artefatti: `tasks/TASK-008/*`, `docs/adr/ADR-009-optimistic-concurrency-http-contract.md`.

## Task di PHASE 1 (dettaglio)

**TASK-007 — Agent Registry** (2026-09-14).

`Agent` era la tabella più vecchia e la meno curata: cinque colonne, nessun timestamp, nessuna
unicità, nessuna validazione, un solo `GET`. Adesso è un registro allo stesso standard di
`Project`: `POST`, `GET [?active=]`, `GET /{id}`, `PUT /{id}`, `activate`, `deactivate`, nessun
`DELETE`.

Le decisioni di ADR-004 sono state **applicate, non ridiscusse**. Una sola divergenza, e
dichiarata: il ciclo di vita resta su `boolean active` invece dell'enum chiuso di `Project`,
perché unificarlo richiederebbe di eliminare una colonna — migrazione irreversibile che il
charter mette dietro una decisione umana quando un'alternativa ragionevole esiste, e esiste.
**TD-31.** La divergenza è nascosta al client e non al registro: `AgentResponse` espone
`status` **derivato**, e un test asserisce che non sia una colonna.

`V4` è additiva, e ha richiesto una cosa che il piano non aveva previsto: un `DEFAULT now()` sui
timestamp, perché il seed di sviluppo è una migrazione già applicata che inserisce senza
fornirli, e modificarla ne cambierebbe il checksum. **Trovato dalla suite, non dal progetto.**

Il protocollo di lock si applica perché **L7 dice che si applica**: due `deactivate` concorrenti
sono un `200` e un `409`, cioè il difetto di TD-19 che arriva già chiuso sulla terza entità.

Artefatti: `tasks/TASK-007/*`, `docs/adr/ADR-008-agent-registry.md`.

**TASK-006 — Migration Test Coverage** (2026-09-14).

Chiude **TD-22** e **TD-23**. Un solo file di test toccato, nessun cambiamento
all'applicazione.

TD-22 chiedeva il test incrementale `V1 → V2`. È stato scritto invece un test su **ogni coppia
consecutiva** che Flyway risolve: porta un database a `N`, ci scrive righe, migra a `N+1` e
verifica le tre cose che una migrazione può distruggere senza fallire — una riga sparisce, una
tabella sparisce, viene applicato più del previsto. Il test specifico avrebbe chiuso il buco di
ieri riaprendo quello di domani; leggere le coppie dallo stream copre `V4` il giorno in cui
esiste, senza che nessuno se ne ricordi.

TD-23 mantiene la decisione — l'elenco delle tabelle è dichiarato a mano, perché una migrazione
che crea una tabella è un fatto che qualcuno deve affermare — e perde la frase che descriveva il
test come capace di adattarsi da solo. Il difetto era la spiegazione, non l'elenco.

Verifica per mutazione: una `V3` che cancella righe, e una che elimina `agents`, rendono rosso
il test.

Artefatti: `tasks/TASK-006/*`.

**TASK-005 — Uniform Error Contract** (2026-09-14).

Chiude **TD-07**, e con lui **TD-20**, **TD-21**, **TD-27** e **TD-29**. L'API parlava tre
dialetti di errore; adesso ne parla uno.

| Decisione | Contenuto |
|---|---|
| Un advice globale | `ApiExceptionHandler`, che estende `ResponseEntityExceptionHandler`: le eccezioni che Spring solleva prima del nostro codice entrano nel contratto senza essere rincorse a ogni upgrade. Erano esattamente TD-20 e TD-27 |
| **`type` stabile** | `urn:ai-company-os:problem:<slug>`, enumerato in `ApiProblem`. **Il `type` è il contratto, il `title` è la prosa**: prima l'unico modo di distinguere due `409` era confrontarne la frase inglese |
| Validazione | `errors` con l'elenco dei campi, **ovunque** |
| Catch-all | `500` con `detail` fisso e stack loggato per intero: nessun nome di tabella, SQL o percorso raggiunge il chiamante |
| Locking | Prende quella forma. **Nessuna policy**: niente timeout, retry o `503`. ADR-006 §7 resta valida, e TD-29 era la forma, non la policy |
| I due advice di modulo | Rimossi: un contratto con due punti di definizione dipende da una precedenza che nessuno ha scelto |

**Rottura dichiarata**: cambiano i corpi di errore di `/api/agents` e delle risposte
preesistenti di `/api/tasks`. **Nessuno stato HTTP cambia.**

Artefatti: `tasks/TASK-005/*`, `docs/adr/ADR-007-uniform-error-contract.md`.

**TASK-004 — Archival Consistency & Project Lock Protocol** (2026-09-14).

Chiude l'incoerenza che ADR-005 §4 aveva lasciato dichiarata: l'archiviazione di un progetto
rifiutava lavoro nuovo ma non proteggeva il lavoro che conteneva — un task usciva da un
progetto archiviato con un `200`.

| Decisione | Contenuto |
|---|---|
| Consistenza **derivata** | `archive`/`restore` scrivono **una sola riga**, la propria. Zero `UPDATE` su `tasks`. Cambia la regola, non il dato. `restore` è l'inverso esatto **per costruzione**: non c'è niente da ricordare |
| Congelamento | Un task il cui progetto è `ARCHIVED` non si sposta → `409`. Letture invariate. `PUT` verso lo **stesso** progetto archiviato → `200` no-op |
| Protocollo **L0–L7** | La riga del task esclusiva **prima** di leggerne l'associazione (L0); le righe dei progetti da cui la decisione dipende, condivise (L2–L4); ordine globale `tasks` → `projects` per id crescente (L5); le letture non bloccano (L6); il protocollo è universale (L7) |
| Nessuna migrazione | Schema fermo a **`V3`**. Nessuna colonna, nessun vincolo, nessun `@Version` |
| Contratto invariato | `TaskResponse` identico a TASK-003 |

**Unico cambio di contratto osservabile**: `PUT /api/tasks/{id}/project` passa da `200` a `409`
quando **sposta** un task fuori da un progetto archiviato.

Un test ha riprodotto, prima che il codice esistesse, un bypass della regola per **staleness**
che i soli lock sui progetti non intercettano — lo scrittore stantio tiene `{A, B}` e l'archive
tocca `C`, insiemi disgiunti. Da lì **L0**, approvato il 2026-09-14.

Artefatti: `tasks/TASK-004/*`, `docs/adr/ADR-006-archival-consistency-and-project-serialization.md`.

- **TASK-003 — Task → Project Association Foundation** (merged in `master`, 2026-09-12).
  Prima relazione persistente: `Task` → `Project`, chiave esterna da `V3`, mapping JPA
  unidirezionale, API di assegnazione e listato per progetto. ADR-005.
- **TASK-002 — Core Domain Model & Project Registry Foundation** (merged in `master`,
  2026-09-11). `Project`, tabella da `V2`, ciclo di vita esplicito, archiviazione al posto
  della cancellazione. ADR-004.
- **TASK-001 / TASK-001A — Reproducible Persistence Foundation** (merged in `master`).
  PostgreSQL, schema di proprietà di Flyway, seed dev in stream separato. ADR-001, ADR-002,
  ADR-003.

## Stato del sistema

- Database: **PostgreSQL 17** via `docker-compose.yml`, volume `aicompany_postgres_data`.
- Schema: di proprietà di **Flyway**, e **due numeri diversi che non vanno confusi**:

  | | Valore | Che cos'è | Come si verifica |
  |---|---|---|---|
  | **Migration stream** (il codice) | **`V8`** | La migrazione più alta che esiste in `backend/src/main/resources/db/migration`. La prossima da scrivere è `V9` | `ls backend/src/main/resources/db/migration` |
  | **Live dev DB** (il volume locale) | **`V3`** | Le migrazioni realmente applicate al database di sviluppo `aicompany_postgres_data`. **Non ha mai visto `V4`, `V5`, `V6`, `V7`** | `docker exec aicompany-postgres psql -U aicompany -d aicompany -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank;"` |

  Sono **indipendenti per costruzione**: il primo è una proprietà del repository, il secondo di
  un'installazione. Ogni installazione ha il proprio, e «schema a `V8`» **senza qualificatore
  significa lo stream**, mai un database. Scoperto dal censimento di TASK-010, che trovò lo stato
  che dichiarava `V6` per un database che era — ed è tuttora — a `V3`. Riverificato da TASK-012 il
  2026-09-19: stream `V8`, dev DB `V1,V2,V3`.

  Al primo avvio in profilo `dev` le **cinque** migrazioni mancanti si applicheranno in ordine.
  **Verificato su un clone del database reale** da TASK-012, compresa `V8`, che è distruttiva.
  `V7` passerà, perché l'unica riga di `tasks` ha `status = 'OPEN'`. **Non serve
  `docker compose down -v`.** Hibernate in `validate`.
- Tabelle: `agents`, `tasks`, `projects`. `tasks.project_id` nullable con FK senza `ON DELETE`.
  Da `V4`: `agents.created_at` / `updated_at` (`TIMESTAMPTZ`, `NOT NULL`, `DEFAULT now()`) e
  indice unico `agents_name_unique_idx` su `lower(name)`.
  Da `V5`: `version BIGINT NOT NULL DEFAULT 0` su **tutte e tre** le tabelle.
  Da `V6`: `tasks.agent_id` nullable con FK senza `ON DELETE`, e `tasks_agent_id_idx`.
  Da `V8`: `agents.status VARCHAR(32) NOT NULL` con `agents_status_check`, e **`agents.active`
  eliminata** — la **prima migrazione distruttiva** dello stream, autorizzata da una decisione
  umana (ADR-012). Backfill biiettivo, zero righe perse.
  Da `V7`: `tasks_status_check CHECK (status IN ('OPEN','IN_PROGRESS','DONE'))` — la prima
  migrazione dello stream che **non è additiva per costruzione**, perché applica un vincolo a dati
  esistenti. Additiva su questi dati: zero righe lette, scritte o riscritte (`tasks/TASK-010/CENSUS.md`).
- **Due registri di dominio** con ciclo di vita esplicito, e **dalla stessa forma** da `V8`:
  `Project` (enum `ACTIVE`/`ARCHIVED`) e `Agent` (enum `ACTIVE`/`INACTIVE`), entrambi con un
  `CHECK` nel database. La divergenza booleano/enum era **TD-31**, chiusa da TASK-012.
  `AgentResponse` continua a esporre **sia** `active` **sia** `status`, e `?active=` resta un
  booleano: è contratto pubblico, e `V8` ha invertito quale dei due è derivato, non la risposta.
- **`Task.status` ha un vocabolario chiuso**: `OPEN`, `IN_PROGRESS`, `DONE` (`TaskStatus`), imposto
  in **tre** punti — vincolo Jakarta sulla request, `@Enumerated(STRING)` sull'entità,
  `tasks_status_check` nel database. **È un vocabolario, non una macchina a stati**: nessuna regola
  di transizione esiste, e `DONE` è legale alla creazione (ADR-011 §3). **Non esiste alcun percorso
  che muti lo `status` di un task esistente** → TD-37. `Task.priority` resta una stringa libera →
  TD-36.
- Seed di sviluppo: stream Flyway separato (`db/dev/V1`), profilo `dev` (ADR-003).
- Profili: `dev` (default), `test` (Testcontainers), `prod`.
- **Concorrenza**: protocollo di lock L0–L7 su `tasks` e `projects` (ADR-006 §4). Lock
  bloccanti ordinari: nessun `lock_timeout`, nessuna policy di retry, nessun `503`.
- Contratto di errore: **uno solo** (ADR-007). Ogni risposta di errore è un `ProblemDetail`
  con `type` stabile `urn:ai-company-os:problem:<slug>`, enumerato in `ApiProblem`. Un advice
  globale, `ApiExceptionHandler`. Nessuna policy di timeout o retry.
- **Due relazioni**: `Task` → `Project` e `Task` → `Agent`, entrambe unidirezionali, entrambe
  nullable, entrambe con FK senza `ON DELETE`. Un task sa dove sta e di chi è.
- **Concorrenza**: protocollo di lock **L0–L7** con ordine globale **L5′** su tre classi di righe,
  `tasks` → `projects` → `agents` (ADR-006 §4, ADR-010 §3). L'aciclicità è **dimostrata** e
  **condizionata**: regge finché `archive`/`restore` non scrivono task e finché il ciclo di vita
  di un agente non dipende dai suoi task.
- **Concorrenza ottimistica nel contratto HTTP**: protocollo **P0–P4** (ADR-009). `If-Match`
  obbligatorio su ogni mutazione di risorsa esistente, confrontato **dentro la transazione, dopo
  il lock esclusivo, prima delle regole**. `@Version` è il contatore, non il rilevatore: nessun
  handler per `OptimisticLockException`, e non va aggiunto.
- **A fine blocco PHASE 3–7: 329 test Java, 51 engine, 14 console.** Le righe di questa sezione
  descrivono il sistema fino a TASK-012; per ciò che il blocco ha aggiunto — sicurezza, ciclo di
  vita, engine, run, console — vale la sezione ▶ in cima e gli ADR-013…017.
- Test (fino a TASK-012): **223** (erano 109 in `master`, 158 a fine PHASE 1, 201 dopo TASK-009, 216 dopo
  TASK-010, 219 dopo TASK-011). `./mvnw -B clean test` → BUILD SUCCESS, **in locale e in CI**.
- H2 rimosso.

## Stato Git (verificato il 2026-09-20)

- **Remote**: `origin` → `https://github.com/Gabryx82/Ai-Company-OS.git`, configurato il
  2026-09-20 su autorizzazione umana esplicita, **pushato il 2026-09-23**. Repository pubblico,
  di proprietà di `Gabryx82`, default branch `master`.
- **CI attiva**: GitHub Actions, `.github/workflows/ci.yml`, esegue `./mvnw -B clean test` a ogni
  push e pull request. Prima run verde: `35863517006`.

- **`master`**: **`6dc5989`**. Era `d5ff121`; ci sono arrivati **41 commit** con due fast-forward
  consecutivi, autorizzati dalla review umana del 2026-09-19.
  - `d5ff121` → `0a35ac0` (PHASE 1), poi `0a35ac0` → `6dc5989` (PHASE 2);
  - **nessun merge commit prodotto**: `git log --merges d5ff121..master` è vuoto. L'unico merge
    commit della storia, `e8d0286`, è di TASK-001 (2026-09-11) ed era già in `master`;
  - `d5ff121` resta raggiungibile: **nessuna storia riscritta**.
- **Integration branch di PHASE 2**: `autonomous/phase-2-assignment`, creato da
  `autonomous/phase-1-foundations` (`0a35ac0`). Contiene il piano di fase, TASK-008, TASK-009 e
  TASK-010 e TASK-011. **HEAD: l'ultimo commit di questo branch** — deliberatamente non scritto come hash,
  perché il commit che aggiorna questa sezione sposta l'HEAD che la sezione dichiara, e TASK-004
  dovette correggerlo una volta (`1ddeee3`). L'ultimo commit di *lavoro* di TASK-010 è `81d350d`;
  quelli successivi su questo branch sono aggiornamenti di stato.
- **Integration branch di PHASE 1**: `autonomous/phase-1-foundations`, HEAD **`0a35ac0`**, fermo.
- Branch di lavoro integrati in fast-forward: `task-004-archival-consistency`,
  `task-005-uniform-error-contract`, `task-006-migration-test-coverage`,
  `task-007-agent-registry`, `task-008-optimistic-concurrency`,
  `task-009-task-agent-assignment`, `task-010-task-status-vocabulary`,
  `task-011-debt-registry-and-docs`.
- **`master` è pushato su `origin`** dal 2026-09-23 (`14f70b3`), senza force e senza riscrivere
  storia. `origin/master` allineato a HEAD, upstream impostato, ed è il **default branch** del
  repository remoto. Gli integration branch e i branch di task **non** sono stati pushati: restano
  marcatori locali.
- Storia lineare, mai riscritta.

Commit di TASK-010, ora nell'integration branch:

| Hash | Contenuto |
|---|---|
| `472b6dd` | `docs(task-010)` — il censimento, **prima** di qualunque decisione |
| `33e3160` | `test(task)` — la stringa libera riprodotta, **6 deliberatamente rossi** |
| `4b275ce` | `feat(task)` — enum, vincolo, `V7`; le tre guardie |
| `05f8b1d` | `test(task)` — i due rilievi MEDIUM della review, corretti |
| `81d350d` | `docs(task-010)` — chiusura, e tre correzioni a ciò che il repository diceva di sé |

Commit di TASK-004, ora nell'integration branch:

| Hash | Contenuto |
|---|---|
| `f3730af` | `docs(task-004)` — scope approvato |
| `add5cd9` | `test(project)` — le due race, deliberatamente rosse sulla baseline |
| `430b001` | `test(project)` — il bypass per staleness, riprodotto |
| `934a954` | `docs(governance)` — Autonomous Project Mode |
| `6d0f23d` | `docs(task-004)` — L0, ordine globale, TD-30 ristretto |
| `736a22d` | `feat(project,task)` — implementazione |
| `77c2071` | `test(project,task)` — protocollo, regola, ragionamento |
| `b1274d1` | `docs(task-004)` — chiusura, artefatti, stato per una sessione fredda |
| `1ddeee3` | `docs(project-state)` — correzione dell'integration HEAD |

Commit di TASK-005:

| Hash | Contenuto |
|---|---|
| `40c3578` | `test(api)` — i tre dialetti, 9 test deliberatamente rossi |
| `2f77f47` | `feat(api)` — il contratto unico |
| `d3faeae` | `docs(task-005)` — artefatti e review del proprio diff |

Branch conservati: `task-000-audit`, `task-001-persistence-foundation`,
`task-002-project-registry-foundation`, `task-003-task-project-association`,
`task-004-archival-consistency`.

## Decisioni architetturali

- **ADR-001** — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato. *Accettata*.
- **ADR-002** — PostgreSQL con schema di proprietà di Flyway. *Accettata, parzialmente superata da ADR-003*.
- **ADR-003** — Il seed di sviluppo è uno stream Flyway separato. *Accettata*.
- **ADR-004** — Project Registry: stati chiusi imposti due volte, si archivia invece di cancellare, transizione illegale → `409`, unicità del nome nel database, un archiviato non è modificabile (§8). *Accettata*.
- **ADR-005** — Relazione `Task` → `Project`: `project_id` nullable, i task preesistenti non si migrano, associare a un `ARCHIVED` è `409`, relazione unidirezionale, FK senza `ON DELETE`. *Accettata*.
- **ADR-008** — Agent Registry: le decisioni di ADR-004 si applicano identiche; il ciclo di vita resta su `boolean active` invece dell'enum, perché unificarlo richiederebbe una migrazione irreversibile che il charter mette dietro una decisione umana (TD-31); `status` è **derivato** nel contratto, così il client vede un vocabolario solo e il database un solo stato; `V4` additiva; nessuna relazione con `Project` né con `Task`; il protocollo di lock si applica per L7. *Accettata e implementata*.
- **ADR-007** — Un solo contratto di errore per tutta l'API: advice globale che estende `ResponseEntityExceptionHandler`, `type` stabile e enumerato (`urn:ai-company-os:problem:<slug>`) come parte machine-readable del contratto, `errors` ovunque, catch-all con `detail` fisso e stack loggato, nessuna policy di timeout o retry. **Supera ADR-004 §6 e ADR-005 §8**. *Accettata e implementata*.
- **ADR-010** — Assegnazione `Task` → `Agent`: le tre domande di dominio risolte senza copiarle dalla relazione col progetto (D1 stesso esito e argomento diverso, D2 coincide, **D3 diverge** — un agente disattivato non congela i suoi task); il lock graph **ridimostrato** su tre classi con **L5′** `tasks` → `projects` → `agents` e i tre archi assenti verificati; l'aciclicità come **conseguenza** di ADR-006 §1 e D3; una sola versione, quella del task; `V6` additiva. *Accettata e implementata*.
- **ADR-009** — Concorrenza ottimistica nel contratto HTTP: due meccanismi distinti e complementari (lock = consistenza interna, `ETag`/`If-Match` = intento stantio); `@Version` è un contatore persistente e **non** il rilevatore, perché dopo l'attesa su `PESSIMISTIC_WRITE` l'entità è già alla versione nuova; protocollo **P0–P4**; `If-Match` obbligatorio su tutte e tre le risorse; `428`/`412`/`400`; `GET /api/tasks/{id}` introdotto come percorso canonico dell'ETag; `V5` additiva. **Completa ADR-006 §8.** *Accettata e implementata*.
- **ADR-011** — Vocabolario chiuso di `Task.status`: il censimento **prima** della decisione (cinque fonti, un solo valore, zero righe da trasformare); `OPEN`/`IN_PROGRESS`/`DONE`, con gli esclusi dichiarati per l'argomento di ADR-004 §2; **un vocabolario non è una macchina a stati** e nessuna transizione viene introdotta; il campo della request resta `String` perché tipizzarlo come enum produrrebbe un `malformed-request` che afferma il falso e perde il nome del campo; confronto **case-sensitive**, scelta opposta a ADR-008 sui nomi e per un criterio dichiarato; **tre guardie** invece delle due di ADR-004 §2; `V7` additiva sui dati ma non per costruzione, con il fallimento su valori fuori vocabolario come rischio **dichiarato ed eseguito**. **Non tocca ADR-007.** *Accettata e implementata*.
- **ADR-012** — Unificazione del ciclo di vita di `Agent`: **supera ADR-008 §2**, che aveva *sospeso* il cambiamento in attesa di una decisione umana, arrivata il 2026-09-19; backfill **biiettivo** (`TRUE`↔`ACTIVE`, `FALSE`↔`INACTIVE`, nessun terzo caso perché `active` è `NOT NULL` da `V1`), quindi irreversibile è la forma e non il contenuto; **il contratto pubblico non cambia di un byte** — `AgentResponse` portava già entrambi i campi e `V8` inverte quale è derivato; `INACTIVE` e non `ARCHIVED`, perché TD-31 unifica la *forma* e non il vocabolario; `repair()` sul solo stream del seed, con le tre condizioni che lo rendono accettabile lì e in nessun altro posto; fuori scope dichiarati: togliere `active` dalla risposta, rinominare `?active=`, indice, terzo stato. *Accettata e implementata*.
- **ADR-013** — Autenticazione: bearer token per nome, stateless, deny by default, `401` dentro ADR-007, fail closed. *Accettata e implementata* (TASK-013; CORS in TASK-014).
- **ADR-014** — Ciclo di vita del task: quattro archi (`start`, `complete`, `stop`, `reopen`) in `TaskTransition`, regole sull'entità, solo `start` guarda l'agente. *Accettata e implementata* (TASK-015).
- **ADR-015** — AI Engine: model gateway Python senza stato, contratto v1, token fra servizi, provider echo/Ollama/Anthropic (spento senza chiave). Il runtime a grafo resta rinviato. *Accettata e implementata* (TASK-017/018).
- **ADR-016** — Run: lancio col protocollo del task, esecuzione dopo il commit e fuori transazione, ogni run finisce con un tipo, recupero all'avvio, una run non completa il task. *Accettata e implementata* (TASK-019/020).
- **ADR-017** — Console dell'operatore: client del contratto, tipi generati e tenuti al codice due volte, `412` mai ritentato. *Accettata e implementata* (TASK-021/022).
- **ADR-006** — Coerenza archiviazione → task **derivata** (nessuna scrittura sui figli, `restore` inverso per costruzione), congelamento in scrittura con letture aperte, `PUT` idempotente `200` no-op, protocollo di lock **L0–L7** con ordine globale `tasks` → `projects`, nessuna migrazione, contratto invariato, nessun `503`. *Accettata e **implementata**.*

## Prossimo passo autonomo

**Nessuno fino alla Human Final Review del blocco PHASE 3–7** (`FINAL_HANDOFF.md`). Dopo, se
accettato: il prossimo blocco parte dal livello 3 di `AUTONOMOUS_LOOP.md` §4 — **TD-40** (contabilità
e limiti di costo delle run a consumo) va chiuso prima di abilitare `ANTHROPIC_API_KEY` in modo non
presidiato; poi le candidate in `FINAL_HANDOFF.md` §8.

*Storico (2026-09-19):* PHASE 2 chiusa, charter §8 rispettato, `FINAL_HANDOFF.md` di allora
conservato in `docs/handoff/FINAL_HANDOFF_PHASE_2.md`.

Quando PHASE 3 comincerà, la sua prima decisione è quale dei due candidati aprire — **TD-04**
(autenticazione) o **TD-37** (le transizioni di `status`) — e non è una decisione da prendere
dentro la fase precedente.

**TD-08**, la sostituzione di `MasterOrchestrator`, resta sbloccato ma non ancora eseguibile: un
task adesso sa dove sta, di chi è e in che stato è — ma non può **cambiare** stato (TD-37), e un
orchestratore che non muove il lavoro attraverso gli stati non ha niente da orchestrare. TD-37 è
il prerequisito rimasto.

Il piano completo della fase sta in **`.company-os/PHASE_2_PLAN.md`**. In sintesi:

| # | Task | Livello | Stato |
|---|---|---|---|
| **TASK-008** | Optimistic concurrency (`ETag`/`If-Match`). Chiude TD-28, TD-30 | 3 | **completata** 2026-09-16 |
| **TASK-009** | Relazione `Task` → `Agent`. Risolve TD-13 | 4 | **completata** 2026-09-17 |
| **TASK-010** | Vocabolario chiuso di `Task.status`. Chiude la metà `status` di TD-12 | 5 | **completata** 2026-09-19 |
| **TASK-011** | Registro del debito disambiguato; `docs/RUNNING.md` reso di nuovo vero | 6 | **completata** 2026-09-19 |

Fuori da PHASE 2 e dichiarato tale: **TD-14** (CI: richiede un remote, hard stop #4), **TD-31**
(elimina una colonna, hard stop #3), **TD-04** (autenticazione: primo candidato di PHASE 3).

Il merge di PHASE 1 in `master` resta il gesto con cui un umano accetta il lavoro
(`AUTONOMOUS_CHARTER.md` §8), e nessun agente lo esegue.

## Debito aperto rilevante

**Al 2026-10-11 nessun debito è aperto**: l'elenco completo, con lo stato di ciascuno, è in
**`docs/DEBT_REGISTRY.md`**. Ciò che segue in questa sezione è lo storico di PHASE 1–2, conservato com'era: i debiti
che vi compaiono come aperti sono tutti chiusi.

> ⚠️ **Due spazi di identificatori.** `docs/audit/TECHNICAL_DEBT.md` (TASK-000) e la numerazione
> qui sotto usano lo stesso formato `TD-NN` per debiti diversi: **`TD-14`, `TD-19`, `TD-20`,
> `TD-21`, `TD-22` collidono**. Questa numerazione è **autoritativa**, il prossimo id libero è
> **`TD-97`**, e la mappa completa è in **`docs/DEBT_REGISTRY.md`** — da leggere prima di chiudere
> o citare un `TD-NN`.

**Chiusi da TASK-004**: TD-19 (componente ciclo di vita), **TD-24**, **TD-25**.
**Chiusi da TASK-005**: **TD-07**, **TD-20**, **TD-21**, **TD-27**, **TD-29**.
**Chiusi da TASK-006**: **TD-22**, **TD-23**.
**TASK-007** non chiude nulla e **apre TD-31**.
**Chiusi da TASK-008**: **TD-28**, **TD-30**. **TASK-008 apre TD-32 e TD-33.**
**Risolto da TASK-009**: **TD-13** dell'audit (`Agent` e `Task` non si conoscevano).
**TASK-009 apre TD-34 e TD-35.**
**Chiuso a metà da TASK-010**: **TD-12** — la metà `status`. **TASK-010 apre TD-36 e TD-37.**
**TASK-011 non chiude e non apre nulla**: disambigua i due spazi (`docs/DEBT_REGISTRY.md`) e
corregge `docs/RUNNING.md`. Registrata e **non fatta**: la rivalutazione degli otto debiti che
esistono solo nell'audit.
**Chiuso da TASK-012**: **TD-31**. **TASK-012 non apre nulla**, e lascia **TD-14 aperto e
bloccato** su un dato mancante (`tasks/TASK-012/EVIDENCE_TD14.md`).

### Alto valore

| ID | Contenuto |
|---|---|
| **TD-31** | **CHIUSO** il 2026-09-19 da TASK-012. `V8` elimina `agents.active`; decisione umana esplicita, backfill biiettivo, contratto pubblico invariato. ADR-012 |
| **TD-14** | **CHIUSO 2026-09-23.** CI su GitHub Actions, verde alla prima run (`35863517006`). Con 223 test e invarianti di concorrenza, era l'unico debito il cui costo cresceva a ogni task |

### Nuovi (TASK-010)

| ID | Contenuto |
|---|---|
| **TD-37** | *(nuovo)* Nessun percorso muta lo `status` di un task esistente: creato `OPEN`, resta `OPEN`. Il vocabolario è chiuso, **il ciclo di vita non è percorribile**. Si chiude con la task delle transizioni, dove le regole di transizione sono la domanda centrale e non un effetto collaterale — ed è precisamente la domanda che ADR-011 §3 ha evitato di rispondere per inerzia |
| **TD-36** | *(nuovo, MINOR)* `Task.priority` resta una stringa libera senza vincolo DB. Stesso difetto di TD-12 e stessa forma di soluzione; **non combinato di proposito**, perché è una normalizzazione adiacente e non la stessa. Se verrà chiuso, il censimento va **rifatto su quel campo**: non si eredita quello di `status` |

### Nuovi, minori (TASK-008, TASK-009)

| ID | Contenuto |
|---|---|
| **TD-34** | Un task può puntare a un agente inattivo — stato **legale e necessario** per ADR-010 D3 — e non c'è modo di chiedere «i task fermi su agenti inattivi» senza incrociare due liste lato client. Si chiude con un filtro su `GET /api/tasks`, il giorno in cui un client lo pone |
| **TD-35** | Nessun `DELETE` dell'associazione con l'agente: un task assegnato non torna «non assegnato», si riassegna soltanto. Gemello di quello che ADR-005 §5 lasciò aperto sul progetto, con più pressione |
| **TD-32** | L'entity-tag è forte ma deriva dalla versione della riga, non dai byte della rappresentazione: un cambio di forma della risposta senza cambio di stato darebbe lo stesso ETag a due rappresentazioni diverse. Nessun effetto su `If-Match`; effetto sulla cache HTTP, che il progetto non usa |
| **TD-33** | I listati non portano ETag, quindi mutare N risorse costa N letture singole. Non motivato finché non esiste un client che muta in blocco |

### Qualità dei test e migrazioni

| ID | Contenuto |
|---|---|
| **TD-26** | Il path lazy non è esercitato fuori transazione. **Parzialmente coperto** da TASK-004: un test pinna che leggere `projectId` non inizializzi il proxy |

### Preesistente

TD-04 sicurezza, TD-08 `MasterOrchestrator`, TD-12/TD-13 dominio, TD-15 Lombok inutilizzato,
TD-17/TD-18 `README.md`.

**TD-11 (CORS) — superficie cambiata.** Il `@CrossOrigin` senza origine su `AgentController` è
stato **rimosso** da TASK-007: era già TD-11, ma quel controller adesso non legge soltanto, e
riportarlo avrebbe aperto creazione, modifica e transizioni a qualunque origine come effetto
collaterale. Non chiude TD-11 — la policy CORS resta una decisione da prendere — ma la superficie
oggi è più stretta di quanto il debito descrivesse.

Rilievi della review TASK-001: **R5** (`server.address` non vincolato a loopback) e **R7**
(`.gitignore` non copre `.env.*`) chiusi da TASK-014; **R3** (`.env` non configura il processo Maven)
e **R6** (tag immagine mobile) chiusi per decisione dell'operatore il 2026-10-11 come **TD-93** e
**TD-94**. Correzioni documentali ai file `docs/audit/*` di TASK-000: chiuse per decisione come
**TD-95** (l'audit resta la fotografia di TASK-000). `docs/RUNNING.md` documenta `/api/projects` e
gli endpoint di TASK-003.

## Failure aperti

**Nessuno.** Java 705 (0 falliti, 1 saltato: link simbolici su Windows), console 142/142, engine 73/73 (2026-10-11).

## Domande di contratto (chiuse il 2026-10-11)

Scritte in PHASE 2, «da decidere insieme, quando esisterà un client reale che le pone». Le risolte: filtro
per stato su `GET /api/tasks`, transizioni (TD-37), TD-34, TD-35. Le altre restano scelte di contratto, chiuse
per decisione dell'operatore il 2026-10-11 come **TD-96**. L'elenco com'era:

- nessun modo di sapere in anticipo che un task è congelato: lo si scopre dal `409`;
- nessun `DELETE /api/tasks/{id}/project`: `NULL → non NULL` resta a senso unico;
- nessun filtro «task senza progetto» su `GET /api/tasks`;
- `archive` non idempotente; `GET /api/projects` senza filtro include gli archiviati;
- nessun `DELETE /api/tasks/{id}/agent`, e nessun filtro «task su agenti inattivi» (TD-34, TD-35);
- assegnare in anticipo a un agente temporaneamente spento: rifiutato oggi, richiederebbe semantica
  di coda (ADR-010 D1);
- nessun filtro «task per stato» su `GET /api/tasks`, mentre `GET /api/projects?status=` esiste;
- nessun modo di **cambiare** lo stato di un task: si sceglie alla creazione e resta (TD-37);
- nessuna paginazione; nessuno slug pubblico stabile.

## Working principles
- **Autonomous execution con Human Final Review** (`AUTONOMOUS_CHARTER.md`).
- Persistent project artifacts instead of long chat histories.
- Minimal context loading.
- Invarianti prima del codice; rosso prima di verde; mutazione su ciò che è portante.
- Never rewrite or discard existing working code without evidence and justification.
- Un debito non si chiude perché il codice sembra diverso.

## Target architecture
- Project Registry ✅ *fondazione (TASK-002), relazione con i task (TASK-003), coerenza di archiviazione e concorrenza (TASK-004)*
- Agent Registry ✅ *fondazione (TASK-007), relazione con i task (TASK-009)*
- Task lifecycle ✅ *vocabolario chiuso (TASK-010), transizioni (TASK-015), dettagli e priorità (TASK-016)*
- Security baseline ✅ *bearer token (TASK-013), CORS (TASK-014); utenti/RBAC non ancora*
- Execution ✅ *run di un agente su un task attraverso l'AI Engine (TASK-019), routing sul registro (TASK-020)*
- Operator console ✅ *React/TS, tipi generati dal contratto (TASK-021, TASK-022)*
- Skills / Rules / Subagents / Tools / MCP Registry
- Model Gateway and local/cloud routing 🟡 *AI Engine con echo, Ollama, Anthropic (TASK-017/018); routing di modello per agente (TASK-020); quote e costi → TD-40*
- Context / Prompt / Harness / Loop / Graph Engineering
- Visual Code Architecture Graph, Knowledge Vault, Memory Graph
- Planner
- GitHub / GitLab, ClickUp, Google Drive / Gmail, software adapters
- Template Hub, Framework Explorer, External AI workspaces
- Voice Interaction Layer, Payments / quota monitoring, 3D Omniverse integration

## Immediate goal
**Al 2026-10-11**: tutto integrato in `master`, nessun debito aperto; resta il push di `master` su `origin`
(sezione in cima). Lo storico che segue è com'era:

**Blocco PHASE 3–7 completato, da accettare.** Il Company OS è utilizzabile dal vivo: un operatore
autenticato crea un task, lo assegna all'agente suggerito, lo fa eseguire da un modello locale
attraverso l'AI Engine, ne legge l'esito e lo chiude — da API o dalla console.

*Storico:* PHASE 1 e PHASE 2 in `master`, TASK-012: 223 test verdi, schema `V8`.

PHASE 3 **non è iniziata** e la sua prima decisione non è stata presa. `master` è adesso la linea
principale del progetto: da qui in avanti il gate del charter §8 si applica alla fase successiva,
non a quella conclusa.
