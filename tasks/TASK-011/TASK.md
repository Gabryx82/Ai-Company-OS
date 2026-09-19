# TASK-011 — Registro del debito e documentazione operativa

**Livello 6** di `AUTONOMOUS_LOOP.md` §4. Ultima task di PHASE 2.
Auto-approvata in Autonomous Project Mode il 2026-09-19.

## 1. Perché il livello 6, con il 5 non vuoto

Va scritto perché il criterio, letto di sfuggita, dice il contrario.

TASK-010 ha aperto **TD-37** (le transizioni di `status`), che è livello 5, e il livello 6 non si
tocca finché il 5 è pieno. TD-37 resta comunque fuori da PHASE 2:

1. **la fase si chiama *Assignment*, non *Lifecycle*.** `PHASE_2_PLAN.md` §2 elenca ciò che resta
   fuori e perché. Introdurre le transizioni adesso allargherebbe lo scope di una fase mentre la
   fase è in corso — charter §4;
2. **`PHASE_2_PLAN.md` §4 condizione 3 non è soddisfatta**: chiede un `FINAL_HANDOFF` aggiornato,
   e quello che esiste è di PHASE 1 (`158 test`, schema `V4`). Charter §8: a fine fase si prepara
   quel documento e **ci si ferma**.

TD-37 è il primo candidato di PHASE 3, accanto a TD-04.

## 2. I due problemi

### 2.1. Collisione di identificatori nel registro del debito

`docs/audit/TECHNICAL_DEBT.md` (TASK-000) e la numerazione viva in `PROJECT_STATE.md` usano **lo
stesso spazio di identificatori per debiti diversi**. Censito, non stimato:

| ID | Nell'audit di TASK-000 | Nella numerazione viva |
|---|---|---|
| **TD-14** | Build non riproducibile offline | Nessuna CI |
| **TD-19** | Metadati `pom.xml` vuoti | Componente del ciclo di vita — *chiuso da TASK-004* |
| **TD-20** | Igiene Git | Eccezioni sollevate da Spring prima del nostro codice — *chiuso da TASK-005* |
| **TD-21** | `System.out.println` invece di logging | *chiuso da TASK-005* |
| **TD-22** | Configurazione porta e ambiente assenti | Test incrementale `V1 → V2` — *chiuso da TASK-006* |

**Cinque collisioni.** L'audit arriva a `TD-22`; la numerazione viva è a `TD-37` e ha **riusato**
cinque identificatori già assegnati, con significati diversi.

Il rischio è reale e ha una forma precisa: **una task futura può chiudere il debito sbagliato
credendo di chiudere quello giusto.** «TD-20 è chiuso» è vero in uno spazio e falso nell'altro, e
niente nel repository dice quale si stia usando.

Non colpiscono tutti: `TD-01`…`TD-13`, `TD-15`…`TD-18` **coincidono** nei due spazi, ed è ciò che
rende la collisione insidiosa — la maggior parte degli identificatori si comporta bene.

### 2.2. `docs/RUNNING.md` descrive un sistema che non esiste più

Documenta **3 endpoint su 18**, e contiene almeno un'affermazione falsa:

> **La prossima migrazione di schema si chiama `V2__...sql` e va in `db/migration`.**

Lo stream è a **`V7`**. Non è documentazione incompleta, è documentazione che **istruisce a
sbagliare**: chi la segue crea una `V2` che Flyway rifiuta.

Assenti: tutto `/api/projects`, quasi tutto `/api/agents`, `GET /api/tasks/{id}`, le due
sotto-risorse di assegnazione, i listati per progetto e per agente, il protocollo `If-Match`
(senza il quale **ogni mutazione risponde `428`** e un lettore non sa perché), e il contratto di
errore.

## 3. Che cosa si fa

| # | Intervento |
|---|---|
| 1 | **`docs/DEBT_REGISTRY.md`** — il documento che disambigua i due spazi e dichiara quale è autoritativo d'ora in poi |
| 2 | **Nota in testa a `docs/audit/TECHNICAL_DEBT.md`** — chi apre quel file deve sapere, sulla prima schermata, che i suoi identificatori non sono quelli vivi |
| 3 | **Un test** che fallisce se un identificatore dell'audit smette di comparire nel registro |
| 4 | **`docs/RUNNING.md` riscritto** nelle parti sbagliate e completato in quelle assenti |

## 4. Che cosa **non** si fa, e perché

| Escluso | Perché |
|---|---|
| **Rinumerare i debiti vivi** | Ogni ADR, artefatto di task e messaggio di commit li cita. Rinumerare romperebbe ogni riferimento scritto finora per sistemare un documento |
| **Rinumerare i debiti dell'audit** | È uno **snapshot datato** di TASK-000. Riscriverne gli identificatori significa riscrivere ciò che quella task trovò |
| **Fondere i due registri** | Sono due cose diverse: uno è la fotografia di un momento, l'altro è un registro vivo. Fonderli perde la data |
| **Chiudere debiti dell'audit** | Diversi sono risolti da tempo, e dirlo sarebbe utile — ma è un'altra task, e questa non allarga lo scope per sistemare tutto |
| **OpenAPI/springdoc** | È `TD-03`/M-3 dell'audit, e sostituirebbe `RUNNING.md` invece di correggerlo. Decisione a sé |

La soluzione è **additiva e non distruttiva**, che è la stessa forma che il progetto dà alle
migrazioni: non si riscrive ciò che c'è, si aggiunge ciò che manca a renderlo interpretabile.

## 5. Invarianti

| # | Invariante |
|---|---|
| **I-1** | Ogni identificatore `TD-NN` presente in `docs/audit/TECHNICAL_DEBT.md` compare in `docs/DEBT_REGISTRY.md`. Aggiungerne uno all'audit senza registrarlo rende rosso un test |
| **I-2** | Il registro dichiara **esplicitamente** quale spazio è autoritativo, e per ciascuna delle cinque collisioni dice entrambi i significati |
| **I-3** | Il test **non è vacuo**: se i file non si trovano, fallisce invece di passare |
| **I-4** | `docs/RUNNING.md` non contiene istruzioni false. In particolare non dice che la prossima migrazione è `V2` |
| **I-5** | Ogni endpoint che esiste nei controller compare in `RUNNING.md` |
| **I-6** | Nessuna modifica al codice applicativo. La suite resta a **216** più i test nuovi |

## 6. Criterio di chiusura

1. I-1…I-6 veri, e il test di I-1 visto **rosso** prima che il registro esistesse;
2. suite completa verde;
3. `PROJECT_STATE.md` aggiornato, merge fast-forward in `autonomous/phase-2-assignment`;
4. **`FINAL_HANDOFF.md` di PHASE 2 scritto**, che è la condizione 3 di `PHASE_2_PLAN.md` §4 e
   l'ultimo adempimento della fase;
5. `master` fermo a `d5ff121`, e **ci si ferma lì** — charter §8.
