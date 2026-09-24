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
