# ADR-037 — Ecosistema 3D: gli agenti e ciò che li definisce, come mappa navigabile

- **Stato**: Accettata e implementata
- **Data**: 2026-10-10
- **Decisa da**: agente, su direttiva umana del 2026-10-10 («una nuova scheda dedicata alla
  visualizzazione grafica 3D, animata e interattiva dell'intero ecosistema degli agenti», «non devi
  modificare, sostituire, semplificare o rimuovere nulla di ciò che è già stato sviluppato», «non creare
  dati fittizi», «le informazioni sensibili non devono essere esposte»)
- **Usa**: ADR-025 (legame Agente → Modello → Provider → Execution Target), ADR-023 (harness),
  ADR-036 (Knowledge Hub). Nessuna modifica al control plane, al database o al contratto.

## 1. Decisione

Una scheda **Ecosistema 3D** (`#/ecosystem3d`, nel gruppo *Lavoro* dopo *Agenti*) disegna ogni agente
come nodo principale e, attorno, tutto ciò che lo definisce, come grafo tridimensionale:

| Categoria | Da dove viene | Forma |
|---|---|---|
| Agente, sottoagente (arco padre → figlio) | `GET /api/ecosystem/bindings` | icosaedro con guscio |
| Modello LLM → Provider (locale / cloud / abbonamento) | `binding.model`, `binding.provider` | ottaedro → prisma esagonale |
| Esecuzione (AI Engine, Claude Code, Codex…) | `binding.target` | toro |
| Skill, Knowledge, MCP, Tool, Framework, fonti di template | `resources` dell'harness | tetraedro, libro, nodo, cono, dodecaedro, capsula |
| Software che l'agente può usare | `software` | cubo |
| System prompt, output atteso, politica di contesto | i campi del prompt | scheda |
| Direttive, nell'ordine | `directives` | piramide |
| Ruolo, dominio, responsabilità, limiti e permessi | i campi di identità | moneta |
| Capability | `capabilities` | sfera |
| Skill, plugin e connettori MCP delle app in cui l'agente lavora | `GET /api/skill-hub/installed` | cluster espandibili |

**Niente di inventato.** Un nodo è un dato letto dal control plane; un arco è una relazione che il
control plane dichiara. Le skill di un'app si agganciano solo agli execution target che l'app serve
(`HubApp.targets`) e su cui lavora almeno un agente; la libreria di AI Company OS (`aicos`) non si
aggancia a nulla, perché le sue voci sono già le risorse dell'harness assegnate a ciascun agente.

**Niente di duplicato.** Una risorsa usata da due agenti è un nodo con due archi; ruolo, dominio e
capability uguali (a meno di maiuscole e spazi) sono un nodo solo; la copia di una skill della
libreria in un'app (`libraryKey`) è quella skill, non una seconda.

## 2. Lettura, non scrittura

La scheda legge con le stesse API della scheda Agenti e del Knowledge Hub e non scrive nulla. Il
pannello delle proprietà riusa la catena del legame (`BindingChain`) e rimanda dove le cose si
modificano: *Apri nella scheda Agenti* (`#/agents/{id}`, che ora apre l'editor di quell'agente),
*Apri nel Knowledge Hub*, *Software Hub*, *Modelli LLM*.

**Aggiornamento**: ogni 15 secondi gli agenti, ogni minuto il Hub, solo mentre la scheda è visibile, e
subito quando torna visibile; una risposta identica alla precedente non tocca la scena. Un agente
creato, modificato o eliminato altrove compare, cambia o sparisce da solo.

## 3. Segreti

Nessun segreto arriva sullo schermo, per costruzione: la `configuration` di MCP e tool (dove può stare
una chiave) diventa solo «presente»; i percorsi delle cartelle del Hub e le copie restano nel Hub; ogni
testo libero (prompt, direttive, descrizioni, dettagli dei plugin) passa da `redactSecrets`, che maschera
chiavi dei provider, token bearer, JWT, chiavi private, `password=…`/`api_key: …` e password negli URL.
La ricerca guarda il testo già mascherato.

## 4. Interazione

Rotazione, spostamento e zoom (OrbitControls); clic per le proprietà, doppio clic per espandere un
cluster o aprire la **vista agente singolo** (l'agente al centro con tutte le sue proprietà, padre e
sottoagenti attorno); filtri per categoria (legenda) e per agente; *Comprimi/Espandi gli agenti* e *i
cluster*; ricerca su nomi e testi, che rivela anche ciò che è nascosto (categoria, cluster, agente) e vi
porta la camera; passaggio del mouse che evidenzia nodo, vicini e archi e attenua il resto; segnali che
scorrono lungo gli archi. Con `prefers-reduced-motion` le animazioni partono spente e la camera salta
invece di volare.

## 5. Prestazioni e risorse

- **three.js + React Three Fiber** (nuove dipendenze, `three`, `@react-three/fiber`; niente drei: i
  controlli vengono da `three/examples`). La scheda è caricata con `React.lazy`: three.js sta in un
  chunk a parte (~264 KB gzip) che le altre sezioni non scaricano.
- Una mesh istanziata per forma, una per le sfere di selezione, un `LineSegments` per tutti gli archi,
  due `Points` per aloni e segnali: poche draw call a qualunque scala. Tutto ciò che si muove è scritto
  in un solo ciclo di frame su buffer preallocati; React rirenderizza solo quando cambiano dati o vista.
  Misurato sullo smoke (16 agenti, 320 elementi, 387 archi, tutte le etichette): 0,7 ms di mediana per frame.
- Layout deterministico (`ecosystemLayout.ts`): agenti su una sfera appiattita, proprietà private in
  calotte per categoria rivolte verso l'esterno, risorse condivise tra gli agenti che le usano,
  infrastruttura verso il centro, membri di un cluster su un guscio; poi un rilassamento con griglia
  spaziale (lineare) che toglie le sovrapposizioni. Etichette come sprite, una texture per testo,
  condivise e contate, con altezza a schermo limitata.
- Geometrie, materiali e texture si liberano allo smontaggio; il contesto WebGL va via con la scheda.
  Senza WebGL la scheda lo dice e rimanda alla scheda Agenti.

## 6. Che cosa non fa

Non modifica agenti, risorse o legami: è una vista. Non mostra progetti, task e documenti: quello è il
Second Brain (grafo 2D dell'intero sistema). Non mostra le skill delle app che nessun agente usa (es.
Claude Desktop, che non è un execution target).

## 7. Test

`ecosystemModel.test.ts` (grafo, condivisione, cluster del Hub, segreti, visibilità, ricerca, rivelazione),
`ecosystemLayout.test.ts` (determinismo, sovrapposizioni con 249 nodi, vicinanza, inquadratura),
`Ecosystem3D.test.tsx` (la scheda con la scena sostituita: dati reali dalle API, pannello, link, segreti,
ricerca, filtri, cluster, vista singola, aggiornamento che aggiunge e toglie, Hub non leggibile, assenza di
WebGL). Smoke dal vivo su un control plane isolato (porta 8091, database `aicompany_eco3d`, 16 agenti dei
template, Hub reale in sola lettura): rendering, inquadratura, selezione, vista singola, ricerca dentro un
cluster, agente creato ed eliminato via API che compare e sparisce da solo, link all'editor.
