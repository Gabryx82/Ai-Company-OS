# ADR-041 — Consumi degli agenti: prima si misurano, poi si riducono

- **Stato**: Implementata (PHASE 35, quattro fasi), in attesa della review umana; non committata
- **Data**: 2026-11-07
- **Decisa da**: agente, su direttiva dell'operatore del 2026-11-07 («ridurre i consumi degli agenti, soprattutto dei
  modelli locali (Ollama), senza cambiare il comportamento già accettato»; «prima misurare»)
- **Estende**: ADR-016 (run), ADR-021 (Master Orchestrator), ADR-025 (handoff), ADR-031 (costi). Le Fasi 2 e 3
  rivedranno ADR-026 §3 e ADR-021 (TD-55).

## 1. Problema

Un agente consuma in due posti. Nelle **run dell'AI Engine** il control plane manda a Ollama un prompt di sistema che
contiene il corpo di ogni skill e knowledge dell'agente e del progetto (fino a 4000 caratteri ciascuna, ADR-026 §3), e
nel messaggio i documenti della task e della fase (fino a 12.000 e 6.000 caratteri, ADR-021 §6). Negli **handoff** il
pacchetto chiede all'agente di leggere per intero la governance, la task, la fase, la mappa del contesto e il file di
ogni skill. Nessuno dei due consumi era misurato: la run teneva i token e la latenza dell'engine, l'handoff niente.
Ottimizzare senza un prima significherebbe dichiarare un risparmio che nessuno ha visto.

## 2. Fase 1 — che cosa si misura

**Solo valori misurati, mai stime presentate come misure.** Un valore che il provider non riporta resta nullo — mai
zero, mai riempito. La dimensione di un prompt o di un pacchetto si conta in **caratteri** (code point) su ciò che è
stato inviato o scritto, e **non si converte in token**: sarebbe una stima.

### 2.1 Run dell'AI Engine

| Misura | Da dove | Colonna (`task_runs`, V42) |
|---|---|---|
| Modello che ha risposto | l'engine (`model`, anche quando un ripiego risponde al posto del chiesto) | `served_model` (già c'era) |
| Token in ingresso e in uscita | Ollama: `prompt_eval_count`, `eval_count` | `input_tokens`, `output_tokens` (già c'erano) |
| Durata totale | Ollama: `total_duration` | `provider_total_ms` |
| Caricamento del modello | Ollama: `load_duration` | `load_ms` |
| Lettura del prompt, generazione | Ollama: `prompt_eval_duration`, `eval_duration` | `prompt_eval_ms`, `eval_ms` |
| Finestra di contesto | il `num_ctx` che l'engine ha chiesto a Ollama — **un'impostazione, non una misura**, e la console lo dice | `context_window` |
| Latenza vista dall'engine | l'engine, attorno alla chiamata al provider | `latency_ms` (già c'era) |
| Caratteri del prompt di sistema e del messaggio | il control plane, su ciò che ha inviato | calcolati dai prompt salvati verbatim (ADR-016 §5), nessuna colonna |

Il contratto v1 dell'engine guadagna un campo **opzionale** `metrics` nella risposta (compatibile per la sua regola):
le quattro durate in millisecondi e la finestra chiesta; `null` per un provider che non misura nulla (l'echo,
Anthropic, OpenRouter). Una durata che Ollama non manda, o che non è una durata (negativa, testo), resta `null`.

**Da sapere su `prompt_eval_count`, misurato (§6).** Su Ollama 0.35.1 resta il numero di token dell'intero prompt
anche quando Ollama riusa dalla cache il prefisso della richiesta precedente: due run identiche consecutive hanno dato
5.392 token entrambe, mentre la lettura del prompt è scesa da 18,8 s a 0,43 s. Il riuso della cache **si vede dalla
durata di lettura del prompt (`prompt_eval_ms`), non dal conteggio dei token** — ed è la misura che la Fase 2 userà per
il prefisso stabile. Accanto ai token resta la dimensione del prompt in caratteri.

Le misure si scrivono solo con la transizione che chiude la run (`succeed` o `failSpent`): una risposta che arriva a
una run già annullata non riscrive né l'esito né le misure (ADR-016, «una run finita non si riscrive»).

### 2.2 Handoff

`task_handoffs.package_chars`: i caratteri del pacchetto `.aicos/handoffs/<TASK>-<target>.md` scritto.
`task_handoff_reads` (V42): una riga per ogni file che il pacchetto chiede di leggere, **nell'ordine del pacchetto** —
`GOVERNANCE` (`AGENTS.md`), `TASK`, `PHASE`, `CONTEXT_MAP` (non obbligatoria: «solo se serve»), poi il file di ogni
`SKILL` e `KNOWLEDGE` che la sezione delle skill dice di «leggere e applicare» — con i caratteri del file in quel
momento, o `null` se il file non c'era. Il file di una skill si misura intero, frontmatter compreso: è ciò che l'agente
apre. «Solo i file che la task nomina» non è un elenco che il pacchetto conosca: non si misura, e la console non lo
somma.

**Una sola lista.** I file che il testo del pacchetto nomina e quelli che si misurano vengono dallo stesso elenco
(`HandoffPackager.contextReads` e `reads`): il testo non può chiedere un file che la misura non vede, né il contrario.
Il testo del pacchetto è rimasto identico byte per byte.

Le righe vanno con il loro handoff (`ON DELETE CASCADE`): eliminare una task elimina handoff e misure come prima. Gli
handoff scritti prima di V42 non hanno misure, e la console li dice «non misurato».

### 2.3 Dove si leggono

`GET /api/usage/measurements?from=&to=` (giorni inclusi, il mese corrente per default), in sola lettura:

- `runs` — le ultime 50 run a cui l'engine ha risposto, con tutte le misure;
- `byModel`, `byAgent` — esecuzioni, quante hanno le durate (`timedRuns`), token totali, **medie calcolate solo sulle
  run che hanno il valore** (`null` se nessuna), token al secondo in generazione (token generati sul tempo di
  generazione delle run che misurano entrambi), finestre chieste, caratteri medi dei prompt;
- `handoffs` — gli ultimi 50, con i caratteri del pacchetto, dei file obbligatori e di quelli «se serve», i file
  mancanti, e ogni file.

Nella console, pagina **Consumi e quote**, la scheda «Misure degli agenti» con le viste Modelli, Agenti, Esecuzioni,
Handoff. Una tabella larga scorre dentro la sua scheda, non la pagina.

### 2.4 Misure di riferimento («prima»)

Raccolte con uno smoke dal vivo isolato su una task reale di prova, prima delle Fasi 2–4: §6.

## 3. Alternative scartate (Fase 1)

- **Stimare i token di un pacchetto dai caratteri** (≈ 4 caratteri per token): sarebbe una stima presentata accanto a
  misure, proprio ciò che la direttiva esclude. I caratteri restano caratteri.
- **Le letture dell'handoff come testo (JSON) in una colonna**: più semplice, ma non si interroga e non si vincola.
  Una tabella figlia con il tipo di file vincolato dal database.
- **Usare la latenza dell'engine come «durata totale»**: è un'altra misura (include la rete fra engine e Ollama);
  restano entrambe, ciascuna col suo nome.
- **Raggruppare per modello chiesto** (come i costi, ADR-031): per i consumi conta il modello che ha risposto davvero,
  anche quando un ripiego ha preso il posto del chiesto.

## 4. Verifica (Fase 1)

- Engine: `test_ollama_reports_its_own_timings_and_the_window_it_was_asked_for`,
  `test_a_timing_ollama_does_not_report_stays_unknown`, forma del contratto con `metrics` nullo per l'echo.
- Control plane: `HttpEngineClientTest` (misure lette, una mancante resta ignota), `AgentMeasuresApiTest` (run con le
  misure, raggruppate per modello e per agente con medie sulle sole run misurate; pacchetto e file letti misurati
  sul disco; handoff precedente «non misurato»), `OrchestratorApiTest` (documento di fase mancante registrato come
  mancante), `TaskRunApiTest` (una risposta tardiva non scrive le misure su una run annullata), migrazioni V42.
- Console: `MeasuresCard.test.tsx` (per modello con «—» dove non c'è misura, per esecuzione, per handoff con file
  mancante).
- Guardie del repository che il cambiamento ha fatto scattare, e corretto: `ApiShapeGuardTest` (un componente JDBC non
  sta in un package `repository`), `SchemaMigrationTest` e `MigrationStreamTest` (la tabella nuova negli elenchi).
- **Mutazioni viste rosse (5 su 5)**: misure scritte su una run annullata; somma dei caratteri per un handoff non
  misurato; file mancante contato come zero; finestra non per modello nell'engine; durata non misurata mostrata come
  «0 ms» nella console.

## 5. Fuori dalla Fase 1

Le run di pianificazione (`plan_runs`) e le azioni AI del Blueprint chiamano lo stesso engine ma hanno i loro record:
non sono misurate qui. Il pacchetto di pianificazione (`GET /api/projects/{id}/plan/handoff`) non è un handoff di task
e non è misurato.

## 6. Misure prima/dopo

**Come.** Smoke dal vivo isolato (backend 8093, engine 8094, database `aicompany_p35`, home, libreria e cartelle dei
progetti temporanee; il backend 8081, l'engine 8090 e la console 5173 dell'operatore non toccati). Una task reale di
prova di un piano: «Progettare la migrazione dei prestiti» (migrazione Flyway V1 per libri, soci e prestiti, PostgreSQL
17), assegnata al Database Specialist del seed, con quattro skill della libreria dell'operatore (`sql-modeling`,
`sql-postgres-style`, `tdd-workflow`, `code-review`, copiate) e una adottata dal progetto (`clean-code-java`); due run
consecutive con `ollama:qwen3.5:9b` (Q4_K_M), poi un handoff `manual`. Lo script è ripetibile per il «dopo».
Macchina: Ollama 0.35.1, iGPU accesa, `num_ctx` 16384, **7,5 GB di RAM libera** (il desktop dell'operatore aperto): le
durate dipendono dalla RAM libera, token e caratteri no.

**Prima (Fase 1, 2026-11-08).**

| Run | Token in | Token out | Totale | Caricamento | Lettura prompt | Generazione | Prompt di sistema | Messaggio |
|---|---|---|---|---|---|---|---|---|
| 1 (a freddo) | 5.392 | 421 | 76,2 s | 19,9 s | 18,8 s | 37,3 s | 17.916 car. | 2.929 car. |
| 2 (subito dopo, stesso prompt) | 5.392 | 469 | 42,6 s | 0,19 s | 0,43 s | 41,2 s | 17.916 car. | 2.929 car. |

Generazione: 11,3 token/s sulle due run. Il prompt di sistema è per il 91% il corpo delle cinque skill (16.363 caratteri su
17.916: fino a 4000 ciascuna, tagliati alla cieca); la seconda run mostra che Ollama riusa l'intero prefisso quando il prompt non
cambia (lettura 44 volte più breve) e che il modello resta caricato fra due run vicine.

| Handoff `manual` | Caratteri |
|---|---|
| Pacchetto | 4.132 |
| Da leggere (obbligatori) | **85.271** — `AGENTS.md` 2.396, task 1.592, fase 1.063, e le cinque skill **80.220** (`sql-modeling` 19.148, `sql-postgres-style` 276, `tdd-workflow` 23.861, `code-review` 26.535, `clean-code-java` 10.400) |
| Se serve (`CONTEXT_MAP.md`) | 1.471 |

Il 94% di ciò che il pacchetto chiede di leggere sono file di skill letti per intero.

**Dopo la Fase 2 (2026-11-08, 02:00).** Stesso smoke, stessa task, **10,7 GB liberi**; prima col jar della Fase 1 e
subito dopo con quello della Fase 2, entrambe le volte con il modello scaricato all'inizio. A = la task di prova;
B = una seconda task dello stesso agente nello stesso progetto, fuori piano («Indici per le ricerche sui prestiti»),
lanciata subito dopo A. Le durate di generazione dipendono da quanti token il modello scrive (405–621 per A): si
confrontano le fasi di lettura.

| | Fase 1 | Fase 2 |
|---|---|---|
| A a freddo — token in ingresso | 5.392 | **1.283** (−76%) |
| A — prompt di sistema | 17.916 car. | **1.899 car.** (−89%) |
| A a freddo — lettura del prompt | 13,1 s | **3,3 s** |
| A a freddo — caricamento del modello | 15,0 s | 15,4 s |
| A a freddo — totale (token scritti) | 66,9 s (586) | 44,9 s (405) |
| A ripetuta subito — lettura del prompt | 0,43 s | 0,39 s |
| B subito dopo A — token in ingresso | 4.506 | 397 |
| B subito dopo A — lettura del prompt | **1,17 s** | **1,49 s** |

Le due skill scelte per la task di prova sono `sql-modeling` e `sql-postgres-style`. Delle skill della libreria
dell'operatore nessuna ha una sezione `## Essenziale`, e il corpo prima del primo `##` è titolo e premessa: di
`sql-modeling` arriva solo la riga della descrizione. **B non migliora**: col layout della Fase 1 il suo prompt di
sistema era identico a quello di A fino in fondo (tutte le skill, sempre) e Ollama lo riprendeva intero dalla cache;
ora la parte che dipende dalla task va riletta. Il guadagno della Fase 2 nelle run è a freddo, e nella dimensione del
contesto, non fra task diverse a caldo.

| Handoff | Fase 1 | Fase 2 |
|---|---|---|
| Claude Code — pacchetto | 4.186 car. | 4.470 car. |
| Claude Code — da leggere (obbligatori) | 85.271 car. | **5.051 car.** (−94%) |
| Claude Code — se serve / se pertinente | 1.471 car. | 11.871 car. (`CONTEXT_MAP.md` e `clean-code-java`, non copiata) |
| Claude Code — skill caricate per nome | 0 | 4 (copiate dal Hub nella cartella dello smoke) |
| `manual` (controllo: non cambia) | 4.132 / 85.271 / 1.471 | 4.132 / 85.271 / 1.471 |

Per Claude Code le misure sono prese sul file del pacchetto scritto (stesse definizioni di §2.2, verificate sul
pacchetto `manual` registrato: identiche), perché nello smoke il launcher è spento e l'handoff si ferma all'apertura
del terminale, dopo aver scritto il pacchetto.

**Dopo le Fasi 3–4 e le sezioni `## Essenziale` (2026-11-08, 12:40–13:00).** Stesso smoke, jar della Fase 4. La
macchina era sotto pressione: 8,9 GB liberi all'avvio e **1,8 GB** con il 9B caricato (impegno 48,4 su 51,5 GB),
quindi il primo caricamento è durato 142 s e la generazione è scesa a 3,9 token/s. Le durate di questa sessione non si
confrontano con le precedenti; token e caratteri sì. Per non caricare due modelli insieme con quel margine lo script è
stato fermato e ripreso caricando un modello alla volta (il codice non lo impone: §13).

| Run A (task di prova) | Fase 1 | Fase 2 | Fasi 3–4 + Essenziale |
|---|---|---|---|
| Token in ingresso | 5.392 | 1.283 | **1.378** |
| Prompt di sistema | 17.916 car. | 1.899 car. | 3.355 car. (le due skill con la loro sezione Essenziale) |
| Messaggio | 2.929 car. | 2.929 car. | **1.934 car.** (il documento della task senza esito, implementazione e test compattati) |
| Finestra chiesta | 16384 (default dell'engine) | 16384 | 16384 (dal catalogo) |
| Ripetuta subito: lettura del prompt | 0,43 s | 0,39 s | 0,55 s |

| Run B (seconda task, subito dopo A) | Fase 1 | Fase 2 | Fasi 3–4 + Essenziale |
|---|---|---|---|
| Token in ingresso | 4.506 | 397 | 761 |

**Instradamento per ruolo, dal vivo.** Una task breve («Riassumi in tre righe le regole dei prestiti…») assegnata a un
agente senza modello: la decisione del Master Orchestrator è `ollama:qwen3.5:4b`, «Modello attivo del ruolo FAST
richiesto dalla task — regola del catalogo «compiti brevi: classificazione, riassunti, controlli» (riassum)», e la run
ci va. Confronto con la stessa task sul 9B, entrambe a freddo, una alla volta:

| Task breve | `qwen3.5:4b` (instradata) | `qwen3.5:9b` |
|---|---|---|
| Token in / out | 180 / 89 | 180 / 99 |
| Totale | 35,1 s | 38,5 s |
| Caricamento | 25,9 s | 23,9 s |
| Generazione | 6,9 s (**13,0 token/s**) | 13,1 s (7,6 token/s) |
| Finestra chiesta | 8192 | 16384 |
| In memoria dopo la risposta (`/api/ps`) | **2 minuti** (scaduto alle 12:57:38) | 5 minuti (fino alle 13:03:21) |
| Memoria del modello | 3,4 GB | 6,1 GB |

A freddo il caricamento domina e il totale cambia poco; il modello piccolo genera quasi al doppio della velocità,
occupa meno della metà della memoria e la lascia dopo due minuti. Il `keep_alive` del catalogo arriva davvero a Ollama
(il suo default sarebbe 5 minuti per entrambi).

| Handoff a Claude Code | Fase 1 | Fase 2 e dopo |
|---|---|---|
| Pacchetto | 4.186 car. | 4.626 car. |
| Da leggere, obbligatori | 85.271 car. (90.003 con le skill di oggi, più lunghe) | **5.051 car.** |
| Skill caricate per nome dallo strumento | 0 | 4 (copie del Hub allineate) |
| Se pertinente / se serve | 1.471 car. | 12.868 car. (`CONTEXT_MAP.md` e la skill del progetto non copiata) |

Con le copie del Hub **indietro rispetto alla libreria** (la libreria dello smoke era stata ricopiata con le sezioni
Essenziale) il pacchetto lo ha detto per ogni skill e ha dato il file «intanto, se pertinente» — il caso «da
aggiornare» verificato dal vivo; aggiornate le copie dal Hub, le quattro skill sono tornate a passare per nome.

**Documenti di task reali** (Fase 3, 37 documenti di *interfaccia-movieseseries*): una run ne leggeva in media 8.527
caratteri, ora 2.257.

## 7. Fase 2 — run dell'AI Engine: al più due skill pertinenti, prefisso stabile (rivede ADR-026 §3)

**Quali.** Fra le skill e le knowledge con un file — dell'agente, poi quelle adottate dal progetto — il control plane
sceglie **al più 2** pertinenti alla task, con una regola deterministica in un posto solo (`Pertinence`):

- le parole della task (titolo e descrizione) contro quelle della voce: una parola trovata nella chiave, nel nome o nei
  tag vale **2**, una trovata solo nella descrizione vale **1**; pertinente **da 2 in su** (una parola del nome, o due
  della descrizione);
- parole in minuscolo, senza accenti, di almeno tre lettere, senza parole funzione italiane e inglesi; due parole
  combaciano se uguali o se, da cinque lettere in su, differiscono solo nelle ultime due (`migrazione`/`migrazioni`,
  `migration`/`migrations`; non `testo`/`test`, non `righe`/`riga`);
- a parità di punteggio vince l'ordine dei candidati: la stessa task dà sempre la stessa scelta.

**Che cosa.** Di ciascuna, la parte essenziale (`Essentials`): la sezione **`## Essenziale`** se il file ne ha una,
altrimenti il corpo fino al primo titolo di secondo livello, senza il titolo; se è vuota, la descrizione. Entro
`aicos.runs.skill-essential-chars` (1500 per default): oltre, si taglia all'ultimo paragrafo che entra — poi all'ultima
riga, poi all'ultima parola — e il taglio è segnato `[…]`. **Mai i primi N caratteri presi alla cieca**, com'era
(4000 per voce).

**Dove.** Il prompt di sistema ha una **parte stabile** — ruolo, profilo, l'intero harness **per nome**, ciò che il
progetto ha adottato, le regole di autonomia — sempre nello stesso testo e nello stesso ordine per ogni task
dell'agente in quel progetto; poi la parte che dipende dalla task, che comincia con «Relevant to this task…». L'ordine
dell'harness ora ha uno spareggio (`ORDER BY created_at, resource_id`): due risorse collegate nella stessa transazione
uscivano in un ordine non garantito, e il prefisso sarebbe cambiato da una run all'altra.

**La regressione resta.** Un agente senza profilo e senza harness, su una task fuori piano, manda il prompt di PHASE 6
byte per byte (`aRunOfATaskOutsideAPlanSendsExactlyThePromptOfPhase6`, verde).

## 8. Fase 2 — handoff: le skill che lo strumento carica da sé

**Quali strumenti.** Quelli che il Knowledge Hub sa che leggono una cartella personale di skill dove il Hub scrive
(`SkillLocations.App.targets` e `Location.readers`): Claude Code (CLI, e app desktop — target `claude-desktop`, che
legge `~/.claude/skills` come la CLI: aggiunto al Hub), Codex (`~/.codex/skills`, `~/.agents/skills`), OpenCode (anche
`~/.claude/skills` e `~/.agents/skills`), Antigravity e Antigravity IDE (`~/.gemini/antigravity/skills`), e **Verdent**
(`~/.verdent/skills`), che risponde allo stesso criterio. Gli altri — Junie, Continue, Devin, Kimi, ChatGPT, Gemini,
manuale — ricevono il pacchetto **come prima**.

**Che cosa dice il pacchetto**, skill per skill, dopo aver chiesto al Hub dove sta la copia (`copyForTarget`, in sola
lettura):

| Copia del Hub nella cartella dello strumento | Il pacchetto | File della libreria |
|---|---|---|
| allineata (`IN_SYNC`) o modificata nell'app (`MODIFIED`) | «usa la skill `<nome>` se pertinente: <app> la carica dalla sua cartella» | non chiesto |
| assente (`NONE`) | «non è nella cartella di <app>: copiala dal Knowledge Hub (Libreria → <skill> → Nelle app → <app>). Intanto, se è pertinente, leggi `<file>`» | facoltativo |
| più vecchia della libreria (`OUTDATED`, `CONFLICT`) | «non è allineata alla libreria: aggiornala dal Knowledge Hub. Intanto, se è pertinente, leggi `<file>`» | facoltativo |
| cartella con quel nome non scritta dal Hub (`FOREIGN`) | «c'è una skill `<chiave>` che non viene dal Knowledge Hub: resta com'è. Intanto, se è pertinente, leggi `<file>`» | facoltativo |

**L'handoff non scrive nulla nelle cartelle delle app.** La copia si propone e la fa il Hub, con il suo marcatore
(ADR-036 §5); una cartella senza marcatore non si tocca.

**Knowledge.** Non hanno una cartella nativa nelle app: per gli strumenti che caricano le skill da sé restano indicate
col percorso («leggilo e applicalo», obbligatorie), **ma solo quelle pertinenti alla task**, con la stessa regola delle
run (§7, senza il limite di due); le altre non compaiono.

**Misure.** Le letture registrate (§2.2) seguono il testo, dallo stesso `LibraryUse`: una skill caricata dallo
strumento non è una lettura, una proposta con il file «intanto» è una lettura facoltativa.

## 9. Alternative scartate (Fase 2) e verifica

- **Un modello che sceglie le skill**: costa una chiamata per run e non è verificabile; la regola a parole chiave sì.
- **Embedding e similarità**: un indice da tenere allineato e un modello in più in RAM, su una macchina che ne ha poca
  (ADR-034); rinviato finché la regola non si dimostra insufficiente sulle misure.
- **I primi N caratteri di ogni skill** (com'era): taglia a metà frase e manda soprattutto titolo e premessa.
- **L'handoff che copia da sé le skill mancanti**: scriverebbe nelle cartelle personali dell'operatore senza che lui lo
  chieda; il Hub ha già il gesto, il marcatore e il backup.
- **Leggere la cartella dello strumento senza il Hub**: duplicherebbe lo stato delle copie che il Hub già calcola.

Verifica: `PertinenceTest` (9: pesi, plurali e prefissi corti, accenti e parole funzione, soglia e limite, parità,
sezione essenziale, ripiego sulla descrizione, tagli), `PertinentSkillsInRunsApiTest` (3: solo le pertinenti e solo la
parte essenziale; parte stabile identica fra task diverse, adozioni comprese, e prompt senza skill uguale al prefisso;
ordine dell'harness con lo stesso istante), `HandoffSkillsApiTest` (3: copia usata per nome, mancante proposta,
estranea non toccata, knowledge solo pertinente, letture che seguono; copia indietro rispetto alla libreria; target che
non carica skill come prima). Mutazioni viste rosse: skill pertinenti prima delle adozioni; prefisso minimo 4; filtro
delle knowledge tolto; marcatore ignorato; letture facoltative registrate come obbligatorie. L'ordine dell'harness è
stato visto rosso prima dello spareggio.

## 10. Fase 3 — l'esito separato dal documento della task (rivede ADR-021, TD-55)

**Il problema, misurato.** L'agente scriveva l'esito nella sezione `## Esito` del documento della task, giro dopo giro,
e una run dell'engine includeva il documento intero (fino a 12.000 caratteri). Sui 37 documenti di task del progetto
*interfaccia-movieseseries* dell'operatore (letti in sola lettura, contati con la stessa regola): le sezioni Esito
sommano **283.192 caratteri** (la più lunga 36.191); una run ne avrebbe letti in media **8.527 per documento**, e 14
documenti su 37 superavano il limite e venivano tagliati.

**Decisione.**

- L'esito completo va in **`tasks/TASK-NNN.esito.md`**, accanto al documento (`PlanMarkdown.outcomePath`); per una
  task senza progetto `TASK.esito.md` accanto a `TASK.md`. Nella sezione **Esito** del documento resta un riassunto di
  poche righe con il link `[TASK-NNN.esito.md](TASK-NNN.esito.md)`. Lo dicono il pacchetto («Alla fine»), il prompt
  completo, il segnaposto del documento di piano e di quello fuori piano, la governance dell'inbox, il template di
  `AGENTS.md` (nuova riga in «Dove sta cosa», passo 5) e quello di `CONTEXT_MAP.md` (riga «Esito»).
- **`TaskOutcomes` legge prima il file**; se manca o è vuoto, la sezione Esito del documento come prima: i documenti
  già scritti, con l'esito dentro, continuano a funzionare. La sorgente lo dice («file tasks/TASK-001.esito.md» o
  «sezione Esito di tasks/TASK-001.md»), e così la console e il Blueprint.
- **Una run dell'engine legge del documento della task le sue sezioni di lavoro** (`PlanMarkdown.forRun`): titolo,
  Obiettivo, Scope, Come implementarla, Contratti/API/schema, File da leggere, Test richiesti, Criteri di
  completamento — non Perché esiste, Software e tool (una run non ne ha), la consegna, le Note, e soprattutto non
  l'Esito e le sezioni che gli agenti vi aggiungono dopo. Una sezione con il solo segnaposto «—» non si manda. Le tre
  sezioni di indicazioni (implementazione, test, criteri) arrivano **più leggere**: voce per voce (le righe di un
  elenco, le frasi di un paragrafo) cade la spiegazione causale («…, perché …», «…, because …»: resta l'istruzione), e
  oltre `aicos.runs.task-section-chars` (800) si tengono le voci che entrano, nell'ordine — la prima sempre, una voce
  non si spezza — con il taglio segnato «[…]». Un documento scritto a mano, senza quelle sezioni, si legge fino alla
  sua sezione Esito. Il documento della fase arriva come prima.
- **W1–W4 (ADR-020)**: `tasks/*.esito.md` è testo sotto `tasks/`, un documento scrivibile per W3 (già lo era; ora un
  test lo dice); `TASK.esito.md` dell'inbox lo scrive l'agente e il control plane lo legge soltanto. Gli `AGENTS.md`
  dei progetti esistenti non si riscrivono (W1): il passo nuovo arriva ai progetti nuovi, e a ogni agente con il
  pacchetto della consegna.

**Misura.** Sugli stessi 37 documenti una run legge in media **2.257 caratteri** (il più lungo 3.160) invece di
8.527 (−74%), e nessun documento arriva al limite. Per confronto, con le stesse regole: tutte le sezioni di lavoro
senza compattare 2.602; limite 600 2.074; solo obiettivo, scope e file (la prima versione di questa fase) 708. Sulla
task di prova dello smoke (documento appena generato, Esito ancora segnaposto) 1.592 → 597 caratteri.

**Perché non solo obiettivo, scope e file.** La prima versione della fase toglieva anche implementazione, test e
criteri (708 caratteri in media). L'operatore ha chiesto il 2026-11-08 di non eliminarli se aiutano l'agente, ma di
renderli più compatti. Misurato: in quei documenti «Come implementarla» pesa in media 1.225 caratteri, «Test
richiesti» 435, «Criteri di completamento» 373, contro 7.591 dell'Esito; e per una run, che non può aprire file, sono
le indicazioni della task scritte apposta dal pianificatore. Restano, compattate; la lista delle sezioni è una
costante in un posto solo (`PlanMarkdown.FOR_RUN`, `COMPACTED`).

**Alternative scartate.** Tagliare la sezione Esito a N caratteri (resterebbe la storia, solo più corta); spostare
l'esito in un campo del database (l'agente esterno non ha un'API: scrive file, ADR-021 §4); tenere nel documento
l'esito più recente e archiviare i vecchi (la regola su che cosa sia «recente» la deciderebbe l'agente).

**Verifica.** `HandoffAnywhereApiTest` (il file letto per primo, il ripiego sulla sezione con un file vuoto, l'inbox con
`TASK.esito.md`; e il test di PHASE 32 — esito nella sezione — verde com'era), `OrchestratorApiTest` (la run porta
obiettivo, scope e file, non la storia dell'esito né l'implementazione; il segnaposto del piano nomina il file),
`PlanMarkdownTest` (percorso dell'esito, sezioni di lavoro nell'ordine, compattazione voce per voce, limite con la
prima voce intera, sezioni vuote, documento senza sezioni), `ProjectWorkspaceApiTest` (template), `WorkspacePathsTest`
(W3). Mutazioni viste rosse: file ignorato; file vuoto preso per esito; documento intero nella run; spiegazione
causale tenuta; sezioni «—» mandate; limite ignorato. Una prima asserzione («la run non contiene Spring Initializr»)
era verde per la ragione sbagliata — il piano di prova non aveva quell'implementazione — e ora il test la scrive.

## 11. Fase 4 — come Ollama esegue ogni modello, nel catalogo

**Decisione.** Il catalogo dei modelli (`catalog/models.json`, `llm_models`, V43) dice per ogni modello locale:
`num_ctx` (la finestra chiesta a Ollama), `keep_alive` (quanto Ollama tiene il modello caricato dopo una risposta) e la
**quantizzazione consigliata** (informativa: la decide il file del modello). Il control plane li aggiunge a ogni
richiesta (`ModelSettings`, per le run e per le generazioni di piano); il contratto v1 dell'engine guadagna due campi
opzionali, `context_window` e `keep_alive` (validato: `0`, `-1` o un numero con `s`, `m`, `h`), che l'engine passa a
Ollama (`options.num_ctx`, `keep_alive`). Senza, l'engine usa i suoi default come prima. L'operatore li corregge dalla
pagina **Modelli** («Esecuzione → Modifica», `PUT /api/catalog/models` con i tre campi, «assente lascia com'è»).

**Per modello, non per ruolo.** Un `num_ctx` diverso fra due richieste allo stesso modello costringe Ollama a
ricaricarlo: `qwen3.5:9b`, che pianifica e scrive codice, ha un solo valore.

| Modello | `num_ctx` | `keep_alive` | Quantizzazione | Perché |
|---|---|---|---|---|
| `qwen3.5:9b` | 16384 | 5m | Q4_K_M | pianifica anche: le generazioni di piano hanno sommato fino a 8.794 token sulle loro chiamate; da 4K a 16K la sua cache costa solo 0,15–0,4 GB (ADR-034 §3.3); resta caricato fra run vicine dello stesso agente |
| `qwen3.5:4b`, `lfm2.5:8b` | 8192 | 2m | Q4_K_M | compiti brevi; una run dopo la Fase 2 sta in ~2.000 token; lasciano la RAM presto |
| `gemma4:e4b` | 8192 | 5m | Q4_K_M | orchestratore e coach (ADR-034 §10) |
| `deepseek-coder-v2:16b`, `llama3.2:3b` | 4096 | 1m | Q4_0, Q4_K_M | deprecati; cache pesante a 16K (+3,2 e +1,3 GB) |
| `qwen3.8-gsq:27b-iq3xxs` | 4096 | 1m | IQ3_XXS | candidato che non entra in RAM |

`lfm2.5:8b` resta sulla CPU (`num_gpu: 0`, l'impostazione dell'engine di ADR-034 TD-67, invariata).

## 12. Fase 4 — instradamento per ruolo e una run locale alla volta

**La regola nel catalogo.** `catalog/model-routing.json` dice quale ruolo chiede una task per cui nessuno ha scelto un
modello: per ogni ruolo le radici di parola che lo chiamano (all'inizio di una parola) — CODER il lavoro di codice
(le radici che prima stavano nel codice, `CODE_WORK`, meno `class`, che prendeva «classifica», più `controller` e
`sql`), PLANNER pianificazione e architettura, FAST i **compiti brevi: classificazione, riassunti, controlli** — vince
il ruolo con più radici trovate, e a parità il ruolo più pesante (prima la qualità); nessuna, GENERAL. Poi il modello
`ACTIVE` del ruolo che l'engine serve (il suo ruolo prima di chi lo serve anche); per FAST il più leggero
(`prefer: smallest`); senza, i ruoli di ripiego del catalogo (PLANNER, GENERAL, FAST). Risultato con il catalogo di
oggi: **compiti brevi → `qwen3.5:4b`; pianificazione e codice → `qwen3.5:9b`**; il resto → `qwen3.5:9b`.

`ModelRouter` applica la regola in un posto solo, per la decisione del Master Orchestrator (che ora dice anche quale
regola ha deciso) e per le run: **una run senza modello** — né chiesto né dell'agente — va al modello del suo ruolo
invece che al default dell'engine (rivede ADR-016 §6); il default resta quando nessun modello del catalogo è servito.
**Il modello legato a un agente non si tocca** (ADR-025): l'instradamento vale solo dove nessuno ha scelto. Il
contesto che la decisione propone per il lavoro di codice usa ancora `CODE_WORK`, come prima.

**Una run locale alla volta.** I modelli di un provider `LOCAL` (Ollama) girano uno per volta: le run di un modello
locale vanno su un loro esecutore a un thread (`localRunExecutor`, `aicos.runs.local-concurrency`, 1) e aspettano in
coda, `QUEUED`, senza occupare i thread delle altre run; un cancello comune (`LocalModelGate`) fa aspettare anche una
generazione di piano su un modello locale e una run che arrivino insieme. Le altre run (echo, cloud) non aspettano.
Due modelli locali caricati insieme portavano la RAM libera a 0,1 GB (ADR-034 §3.7).

**Alternative scartate.** Impostazioni per ruolo (ricaricamenti a ogni cambio di ruolo dello stesso modello);
`OLLAMA_NUM_CTX` e `OLLAMA_KEEP_ALIVE` globali (una configurazione del servizio dell'operatore, e uguale per tutti i
modelli); instradare anche gli agenti con un modello (ne cambierebbe il legame deciso dall'operatore); ridurre a uno i
thread di tutte le run (avrebbe fatto aspettare anche quelle non locali); un modello che classifica le task (una
chiamata in più per decidere).

**Verifica.** `test_providers.py` (finestra e keep-alive dalla richiesta, che vince sui default; formato rifiutato),
`HttpEngineClientTest`, `OllamaByRoleApiTest` (valori del catalogo; modifica con valori sbagliati rifiutati;
impostazioni nella richiesta, nessuna per un modello senza; decisione per ruolo; run senza modello instradata;
run locali in coda mentre una non locale passa), `ModelRouterTest` (ruoli, parità, «classifica», «controller», scelta
del più piccolo, ripiego), `ModelRoleRoutingApiTest` (invariato, verde), console `Models.test.tsx`. Mutazioni viste
rosse: run locali non in coda; impostazioni non applicate; preferenza per il più piccolo tolta; parità vinta dalla
regola successiva; formato di `keep_alive` non validato (engine). V43 applicata dal vivo su un database popolato.

## 13. Che cosa resta fuori, e che cosa non è verificato

- **Due modelli locali in memoria insieme.** «Una run alla volta» impedisce due chiamate insieme, non che Ollama tenga
  caricato il modello precedente per il suo `keep_alive` mentre carica il successivo. Con 9–13 GB liberi 9B e 4B
  (6,1 + 3,4 GB) entrano; con il desktop più carico, come nello smoke finale, no. Le leve sono di configurazione:
  `keep_alive` più corto per il 9B, o `OLLAMA_MAX_LOADED_MODELS=1` (una decisione dell'operatore, ADR-034).
- **Le run di pianificazione e le azioni AI del Blueprint** non sono misurate (§5); le generazioni di piano ricevono
  però finestra, `keep_alive` e il cancello dei modelli locali.
- **La quantizzazione installata** non è confrontata con quella consigliata: l'engine non la riporta ancora.
- **Le durate** dipendono dalla RAM libera del momento: le tre sessioni di misura avevano 7,5, 10,7 e 8,9 GB liberi.
- **Il link di Claude Desktop e l'apertura reale degli strumenti** non sono stati provati: nello smoke il launcher è
  spento, e il pacchetto per Claude Code è stato misurato sul file scritto.
