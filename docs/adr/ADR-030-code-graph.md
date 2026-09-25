# ADR-030 — Graph engineering: il grafo del codice di un progetto

- **Stato**: Accettata e implementata (PHASE 23)
- **Data**: 2026-09-25
- **Decisa da**: agente (roadmap V2, ex PHASE 16; chiude in parte TD-46)

## 1. Decisione

`POST /api/projects/{id}/code-graph` legge la cartella del progetto e costruisce il suo grafo:

- **nodi**: i file sorgente (TypeScript/JavaScript, Java, Python), i manifest (`package.json`,
  `pom.xml`, `requirements.txt`), i pacchetti esterni;
- **archi**: `IMPORT` (un file ne importa un altro del progetto), `USES` (un file usa un pacchetto
  esterno), `DECLARES` (un manifest dichiara una dipendenza);
- **cicli di import**: le componenti fortemente connesse (Tarjan) di più di un file;
- **task e file**: le task i cui documenti nominano un file del grafo (percorsi tra backtick).

Statico e lessicale di proposito: legge le istruzioni di import con espressioni regolari, come farebbe
una persona che scorre il codice; **non compila né esegue nulla**. Limiti: 4000 file, 512 KB per file;
cartelle generate o di terze parti saltate (`node_modules`, `target`, `dist`, `.venv`, `.git`…).

## 2. Dove finisce

- `.aicos/code-graph.json` — la forma completa, per gli strumenti;
- `.aicos/CODE_GRAPH.md` — la sintesi leggibile (file più importati, cicli da spezzare, dipendenze
  esterne, task e file). L'orchestratore la propone come **contesto** alle task di codice quando esiste:
  è context engineering, non un'altra finestra da aprire.

La console la mostra nella scheda **Codice** del progetto: numeri, grafo interattivo (d3-force; i cicli
in rosso), file più importati, cicli, task collegate.

## 3. Che cosa non fa

Niente call graph o analisi semantica (tipi, chiamate): richiederebbero un compilatore per linguaggio.
Il grafo di dipendenze tra **task** e il grafo **agenti** esistono già nel Second Brain (PHASE 14).

## 4. Test

`CodeGraphApiTest`: un progetto con TypeScript, Java, Python e manifest; import interni risolti (anche
relativi Python e classi Java per package), pacchetti esterni senza librerie standard, `node_modules`
ignorato, ciclo trovato, file scritti.
