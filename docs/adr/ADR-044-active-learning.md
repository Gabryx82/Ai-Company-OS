# ADR-044 — Apprendimento attivo: la lezione si fa, non si legge

- **Stato**: Implementata (PHASE 37), in attesa della review umana; non committata
- **Data**: 2026-11-10
- **Decisa da**: agente, su direttiva dell'operatore del 2026-11-10 («Rendi l'esperienza utente semplice. Programmare
  deve diventare divertente, entusiasmante e il developer deve imparare in parole semplici il meccanismo alle spalle dei
  progetti che implementa. L'agente deve portarlo a capire ciò che sta costruendo e a lavorare in autonomia quando lui
  non potrà agire. Sia chiaro: il codice resta all'agente AI, ma [l'agente] deve, come già fatto in passato, fornire
  codice e spiegazione riga per riga che l'operatore copierà negli opportuni file. Implementa anche altri metodi di
  apprendimento. Non deve essere un apprendimento passivo.»)
- **Rivede**: ADR-022 §3–§5 (il livello `GUIDED`, e le «domande risposte» che restavano fuori).

## 1. Problema

A livello `GUIDED` l'agente doveva «spiegare, far prevedere, lasciare pezzi da scrivere (`TODO(operatore)`)». Due
limiti: chiedeva all'operatore di inventare del codice, mentre l'operatore vuole che il codice lo scriva l'agente e
portarlo lui nei file, capendolo; e tutto accadeva nella chat dello strumento, dove niente era verificabile né
contabile — ADR-022 §5 aveva lasciato fuori le risposte dell'operatore proprio per questo.

## 2. Decisione

**La lezione è un file con una forma.** L'agente scrive `lessons/<TASK>.lezione.md` nel progetto; la console la porta
all'operatore un atto alla volta e tiene ciò che lui fa.

| Atto | Che cosa fa l'operatore | Come si verifica |
|---|---|---|
| **Previsione** | risponde a una domanda *prima* di vedere il codice | la risposta attesa arriva solo dopo la sua |
| **Passo** | copia il codice del passo nel file indicato, leggendo la spiegazione riga per riga | il control plane apre quel file nella cartella del progetto: il passo è fatto quando contiene ogni riga del codice, nell'ordine |
| **Quiz** | sceglie una risposta | controllata sul server; la soluzione arriva con la risposta giusta |
| **Completa** | scrive ciò che manca al posto di `___` | confronto con la soluzione |
| **Trova l'errore** | tocca la riga sbagliata | confronto con la riga |
| **Spiega a parole tue** | scrive una spiegazione | non è giudicata: si confronta con i punti chiave |
| **Ripasso** | i concetti delle lezioni finite tornano come carte dopo 1, 3, 7, 16 e 35 giorni | «lo sapevo» sposta la carta più in là, «non ancora» la riporta a domani |

## 3. Invarianti

1. **Il codice è dell'agente; nei file lo porta l'operatore.** In modalità «Imparo» l'agente non modifica i file del
   progetto: scrive la lezione. (`AutonomyPolicy`, `LessonFormat.instructions`.)
2. **Ciò che l'operatore deve scoprire non è in ciò che la console riceve.** La vista di una lezione non contiene la
   risposta attesa di una previsione, né l'opzione giusta, la soluzione, la spiegazione o i punti chiave di un esercizio,
   finché quell'atto non è fatto. Il controllo è del server.
3. **Un atto alla volta.** La console apre la tappa successiva solo quando la precedente è fatta: la previsione viene
   prima del codice.
4. **La lezione si legge soltanto, il codice si legge soltanto.** Il servizio non scrive né la lezione né i file del
   progetto; scrive il progresso (`lesson_progress`) e le carte (`learning_concepts`), in V45.
5. **Il file di un passo sta dentro il progetto.** Il percorso che la lezione nomina è scritto da un agente: un
   percorso che esce dalla cartella non viene cercato.
6. **Il formato che si chiede è quello che si legge.** `LessonFormat.GUIDE` è il testo dato agli agenti nel pacchetto
   di consegna; un test legge l'esempio della guida con `LessonParser` e pretende che non abbia nulla da ridire.
7. **Una lezione fuori formato non è un errore.** Ciò che il lettore non sa collocare lo lascia fuori e lo dice
   all'operatore; la lezione resta utilizzabile.
8. **«Giusto al primo colpo» lo decide il primo colpo**, e un atto fatto non torna da fare.

## 4. Quando l'operatore non può seguire

La consegna di una task chiede «Come vuoi lavorarci?»:

- **Imparo** (`TEACH`) — l'agente scrive la lezione e lascia i file all'operatore. È il solito per un progetto `GUIDED`.
- **Non posso seguire ora** (`RECAP`) — l'agente implementa da solo, senza aspettare risposte, e lascia la **lezione di
  ripasso**: i passi ripercorrono il codice già scritto (`(già scritto)`), con previsione ed esercizi. È il solito per
  `SUPERVISED`; in un progetto `GUIDED` sostituisce, per quella consegna, le regole del livello.
- **Solo il lavoro** (`NONE`) — nessuna lezione. È il solito per `DELEGATED` e `FINAL_REVIEW`.

Il livello del progetto resta una scelta dell'operatore (ADR-022 §4); la modalità è di una consegna, ed è registrata
(`task_handoffs.learning_mode`).

## 5. Dove si vede

- **Impara** (nuova voce di navigazione): i giorni di fila, le lezioni di ogni progetto con il loro avanzamento, le
  carte da ripassare; il lettore di una lezione; il ripasso.
- **Dashboard**: «Adesso» — le carte dovute, la lezione in corso, la task a cui tornare.
- **Metriche del progetto** (`GET /api/projects/{id}/learning`): lezioni, passi scritti, esercizi fatti e giusti al
  primo colpo, previsioni — i dati che ADR-022 §5 dichiarava inesistenti.

## 6. Fuori da questa fase

- **Le run dell'AI Engine non scrivono file**: a `GUIDED` un modello locale risponde seguendo le regole nuove, ma la
  lezione interattiva la producono gli strumenti che scrivono nella cartella (Claude Code, Codex, OpenCode, OpenClaw…).
  Generare una lezione con un modello locale e salvarla dalla console è un passo possibile, non fatto: va prima misurato
  se un modello da 4–9 miliardi di parametri tiene il formato.
- **Nessun giudizio automatico delle spiegazioni**: il confronto con i punti chiave lo fa l'operatore.
- **I pezzi `TODO(operatore)`** non sono più chiesti agli agenti; il loro conteggio resta per i progetti che ne hanno.

## 7. Contratto e schema

Additivo. `GET /api/learning`, `GET /api/projects/{id}/lessons`, `GET /api/projects/{id}/lessons/view`,
`POST …/lessons/prediction`, `POST …/lessons/step`, `POST …/lessons/exercise`, `GET /api/learning/review`,
`GET|POST /api/learning/review/{cardId}`; `HandoffRequest.learning`; `Learning.lessons`; problema `lesson-not-found`.
V45: `lesson_progress`, `learning_concepts`, `task_handoffs.learning_mode`.

## 8. Test

`LessonParserTest` (4 — il primo legge l'esempio di `LessonFormat.GUIDE` e ha trovato due volte la guida in difetto:
un «Passo 2» vuoto e una soluzione senza numero di riga), `LessonApiTest` (10), `LearningHandoffApiTest` (5); nella
console `Learn.test.tsx` (7) e un caso in `OrchestratorPanel.test.tsx`.

Mutazioni viste rosse (9): le risposte tenute fuori dalla vista; l'ordine delle righe di un passo; il percorso del
file dentro la cartella del progetto; «giusto al primo colpo» deciso dal primo colpo; le carte solo a lezione finita;
le regole della consegna «non posso seguire»; la modalità predefinita di un progetto `GUIDED`; solo un file di
`lessons/` è una lezione; la tappa che resta dopo un atto appena fatto (trovata provando la console dal vivo:
riaprendo una lezione a metà l'esito di un passo spariva subito).

Smoke dal vivo: una lezione nel progetto di prova, la previsione, un passo prima assente, poi scritto con un'altra
indentazione e trovato, un quiz sbagliato e poi giusto; nella console la ripresa dalla tappa giusta e il controllo di un
passo contro il file vero (ADR-043 §9 per l'ambiente).
