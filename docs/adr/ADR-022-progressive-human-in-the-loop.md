# ADR-022 — Human-in-the-Loop progressivo e Learning with Agent

- **Stato**: Accettata e implementata (PHASE 9–11)
- **Data**: 2026-09-24
- **Decisa da**: agente, su direttiva umana del 2026-09-24 (§19–20)
- **Estende**: ADR-016 §3 («una run non completa il task»)

## 1. Due assi, non uno

La direttiva chiede che l'operatore **validi** (piano, fasi, task, decisioni, risultato) e che il suo
coinvolgimento **evolva** nel tempo. Sono due cose diverse:

- i **gate** dicono *dove* l'operatore deve dire sì — sempre, a qualunque livello;
- il **livello di autonomia** dice *quanto lavoro* fa l'operatore e quanto gli agenti.

## 2. I gate (validi a ogni livello)

| Gate | Dove è imposto |
|---|---|
| Piano approvato | `projects.plan_status`; un piano approvato non si sostituisce (ADR-021 §2) |
| Fase approvata | `Task.requirePhaseApproved()`: una task di una fase non approvata **non parte e non gira** (`409 phase-not-approved`) — nell'entità, così nessun percorso lo dimentica |
| Esito accettato | una run o un handoff non completano la task; la completa una review `ACCEPTED` o l'arco `complete` dell'operatore |

Le task create fuori da un piano non hanno fase: il gate di fase non le riguarda, e il flusso di
PHASE 3–7 resta identico.

## 3. I livelli (`projects.autonomy_level`)

| Livello | L'operatore | Gli agenti |
|---|---|---|
| `GUIDED` (default) | scrive, modifica, copia codice, risponde a domande, impara | spiegano, chiedono di prevedere risultati, lasciano pezzi piccoli da scrivere (`TODO(operatore)`), verificano la comprensione |
| `SUPERVISED` | supervisiona, integra, controlla le decisioni importanti | propongono lavoro completo motivato, si fermano prima delle decisioni irreversibili |
| `DELEGATED` | revisiona e approva | implementano, testano, documentano |
| `FINAL_REVIEW` | fa la review finale funzionale e visiva | coordinano fino al risultato verificabile |

Il testo di ciascun livello vive in **un solo posto** (`AutonomyPolicy`) e arriva a tre destinatari:
`AGENTS.md` del progetto (agenti esterni), il system prompt delle run dell'engine, i file di handoff.
Così un agente esterno e l'engine non possono ricevere istruzioni diverse sullo stesso progetto.

## 4. Learning with Agent

È il livello `GUIDED` reso operativo: il sistema non fa il lavoro *al posto* dell'operatore ma *con*
lui. Nel prompt: spiegazione breve, una domanda di previsione, uno o due pezzi da scrivere a mano, la
causa degli errori prima della correzione, 2–3 domande di verifica finali. Il passaggio di livello è
una scelta dell'operatore sul profilo del progetto, non un automatismo: il sistema non decide da solo
che l'operatore «ha imparato».

## 5. Rinviato

Metriche di apprendimento (domande risposte, pezzi scritti) e suggerimento automatico del livello:
richiedono dati che oggi non esistono. Registrato come debito (TD-45).

## Revisione PHASE 32 (2026-10-08) — metriche di apprendimento e livello suggerito (TD-45)

Rivede §5: i dati che mancavano ora esistono.

- **Il livello di ogni review** (V32, `task_reviews.autonomy_level`): quello del progetto quando la
  review è stata data; nullo per le review precedenti e per le task fuori da un progetto, mai indovinato.
- **Metriche** (`GET /api/projects/{id}/learning`), tutte al livello **corrente** del progetto:
  task riviste; task accettate alla **prima** review a questo livello; modifiche chieste; task completate
  a mano dall'operatore (la storia di TD-38, arco `COMPLETE` via `API`); pezzi `TODO(operatore)`
  ancora aperti nel codice della cartella — il contratto di `GUIDED` (§4), contato solo nei file
  sorgente (le regole in `AGENTS.md` e negli handoff lo citano senza essere un pezzo), con i limiti del
  grafo del codice e senza dipendenze e build.
- **La regola del suggerimento**, in un posto solo (`LearningService.suggest`):
  - si sale quando almeno l'80% delle ultime *N* task riviste a questo livello è stato accettato alla
    prima review — *N* = 5 da `GUIDED`, 8 da `SUPERVISED`, 10 da `DELEGATED` — e, da `GUIDED`, quando
    non restano pezzi `TODO(operatore)` da scrivere;
  - si scende di un livello quando 3 delle ultime 4 task riviste sono state rimandate alla prima review;
    `GUIDED` è il pavimento, `FINAL_REVIEW` il tetto;
  - altrimenti si resta, e il motivo dice che cosa manca.
- **Mai applicato da solo** (§4 resta): la console mostra metriche, motivi e un pulsante «Passa a …» che
  cambia il livello sul profilo del progetto con il suo `If-Match`, come la scelta manuale. Cambiato il
  livello, il conteggio riparte: le review a un livello non dicono nulla di un altro.
- Restano fuori le «domande risposte»: le risposte dell'operatore alle domande di verifica vivono nello
  strumento in cui lavora (la chat di Claude Code, l'IDE), non passano dal control plane, e contarle
  vorrebbe dire inventarle.
