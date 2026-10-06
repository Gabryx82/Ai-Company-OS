# Final handoff — PHASE 30: ciò che si crea qui si elimina qui

Branch `autonomous/phase-30-deletion` (da `f4c69b3`, punta di PHASE 29), **non pushato**. `master` intoccato.
Decisioni e ragioni: [ADR-035](../adr/ADR-035-delete-what-you-create.md).

## Che cosa cambia

| Dove | Cambio |
|---|---|
| Agenti | si eliminano davvero, anche quelli del set iniziale: dall'editor (cestino) o selezionandone più d'uno. Anteprima di che cosa se ne va e che cosa resta; *Disattiva* offerto accanto come gesto reversibile |
| Template | *Da template* mostra, agente per agente, che cosa verrebbe creato e che cosa è già presente; *Installa tutti i mancanti*. Gli agenti eliminati non tornano da soli |
| Reference | cestino su ogni immagine e su ogni altro file di `references/`; selezione multipla; la lista si aggiorna subito |
| Documenti | un documento che nessun record usa (per esempio quello di una task eliminata) si elimina dal visualizzatore |
| Knowledge Hub | skill e knowledge creati o importati si eliminano con il loro file; le voci del catalogo spedito dicono perché no |
| Strumenti del progetto | le risorse adottate dal Knowledge Hub ora si vedono e si tolgono |
| Blueprint | *Elimina Blueprint* (admin, nome del progetto digitato): mai il codice |
| Costi | un budget si rimuove; la spesa di un agente eliminato resta sotto il suo nome |
| Database | `V28`: `task_runs.agent_name`, `agent_id` nullable, vincolo `task_runs_agent_known`. Additiva |
| API | 14 operazioni nuove, contratto OpenAPI **solo additivo** (`docs/RUNNING.md`, «Eliminare») |

## Come si comporta l'eliminazione di un agente

- le task **da fare** e **completate** restano, senza agente;
- le task **in corso** bloccano l'eliminazione, con il nome della task; spuntando *riporta a «Da fare»*
  tornano in coda senza agente;
- una **run in esecuzione** blocca sempre;
- le **esecuzioni concluse** restano nello storico con il nome dell'agente;
- i **sottoagenti** diventano agenti principali, oppure — se scelto — vengono eliminati con lui;
- skill e software restano nel catalogo; si tolgono solo i collegamenti.

Solo un **admin** elimina agenti, voci del catalogo e il Blueprint; reference e documenti li elimina
anche l'operatore (il rovescio del caricamento).

## Verifiche eseguite

| Verifica | Esito |
|---|---|
| Suite Java (`./mvnw clean test`, Testcontainers) | **549 test, 0 falliti**, 1 saltato (preesistente, link simbolici su Windows); erano 518. Due test che fissavano il vecchio contratto («nessun DELETE sugli agenti» → `405`) aggiornati al nuovo (`403` per l'operatore) |
| Console (`npm test`, `npm run build`) | 57/57, build ok |
| AI Engine (`pytest`) | 68/68 (non toccato) |
| Mutazioni backend | 13 guardie tolte una per volta → 13 rosse (una, il `412` del budget, è diventata rossa dopo aver aggiunto il test che mancava) |
| Mutazioni console | 8: 7 rosse; 1 sopravvissuta (uno stato locale reso inutile dalla rilettura) → codice rimosso |
| Smoke dal vivo (backend su `aicompany_p30`, console nel browser integrato) | vedi sotto |
| Windows | un file aperto da un altro programma rifiuta l'eliminazione e la selezione resta intatta (`ManagedFilesTest`) |

## Smoke dal vivo (2026-10-06)

Backend del branch su un database **nuovo e separato** (`aicompany_p30`, porta 8091, home, workspace e libreria
temporanei, nessun programma avviato), console sulla porta 5180 guidata nel browser integrato, come admin.
Il backend e la console già in esecuzione sulle porte 8081 e 5173 non sono stati toccati.

| Passo | Esito |
|---|---|
| Avvio su DB vuoto | V1 → **V28**, seed di sviluppo: 3 agenti |
| *Da template* | «Mancano 13 agenti», *Installa tutti i mancanti* → 13 creati, piano «installato per intero» |
| Elimina *Software Engineer* dall'editor | anteprima: configurazione, 3 skill e 2 software scollegati, 4 sottoagenti che diventano principali; *Annulla* non tocca niente; *Elimina definitivamente* → «Eliminato», sparisce subito; DB: sottoagenti con `parent_id` NULL, evento `DATA_DELETED` |
| Selezione dei 3 agenti del seed, uno con una task in corso | anteprima **bloccata** con il nome della task, *Elimina* disabilitato; spuntato *riporta a «Da fare»* → anteprima aggiornata, eliminazione riuscita; DB: task `OPEN`, senza agente, `version` avanzata |
| **Riavvio del backend** | 12 agenti, **nessun agente del seed né *Software Engineer* ricreato** (lo stream del seed non rigira) |
| *Da template* dopo il riavvio | «Manca 1 agente: Software Engineer», i 4 sottoagenti «presente»; installazione → 13 agenti, **13 nomi distinti** |
| Reference: cestino su `red.png` | dialog rapido con il file; eliminato dal disco senza residui, tile sparita senza ricaricare |
| Reference: selezione di `blue.png` e `appunti.md` | «Eliminati 2 file», elenco aggiornato, «Nessuna immagine ancora» |
| Reference eliminata in Explorer dopo la lettura | prima: `404` mostrato come errore nel dialog → corretto (`c5223d3`): «non c'era più (eliminato fuori dalla console): elenco aggiornato» — verificato dal vivo |
| Knowledge Hub: skill creata ed eliminata | voce, collegamenti e `SKILL.md` rimossi; la cartella resta |
| Knowledge Hub: voce del catalogo (*Expo*) | anteprima bloccata: «tornerebbe al prossimo avvio», *Elimina* disabilitato |

## Prima di accettare

- Il database di sviluppo `aicompany` non è stato toccato: al primo avvio su questo branch riceve, oltre a
  V26–V27 di PHASE 28–29, anche **V28** (additiva).
- Lo smoke dal vivo di questa fase gira su un database separato, `aicompany_p30` (vedi `PROJECT_STATE.md`).
  Per toglierlo: `DROP DATABASE aicompany_p30;`.

## Eccezioni intenzionali (non si eliminano)

Voci del catalogo spedito (tornerebbero all'avvio: si scollegano), blocchi del Blueprint derivati dal codice
(tornerebbero alla scansione), persone (identità del registro di sicurezza: si disattivano), la struttura del
workspace (MASTER_PROMPT.md, AGENTS.md, CLAUDE.md, `.aicos/`), la cartella di un progetto eliminato (può
essere un tuo repository). Tabella completa: ADR-035 §9.

## Debiti aperti dalla fase

TD-68 software aggiunto via API · TD-69 servizi di avvio aggiunti via API · TD-70 file preparati lasciati da
un crash · TD-71 congedare le voci del catalogo · TD-72 due forme di eliminazione (ADR-027 e ADR-035) ·
TD-73 origine `CATALOG` delle voci create via `POST /api/resources`. Prossimo libero **TD-74**.

## Come tornare indietro

Codice: tornare su `autonomous/phase-29-local-model-upgrade`. Database: `V28` è additiva; un database che l'ha
ricevuta funziona anche con il codice di PHASE 29 (scrive sempre `agent_id`), ma le run di agenti eliminati
non avrebbero più un agente da mostrare.
