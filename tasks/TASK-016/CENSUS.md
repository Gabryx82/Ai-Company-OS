# TASK-016 — Censimento di `tasks.priority`, prima di qualunque decisione

Il metodo di `tasks/TASK-010/CENSUS.md`, rifatto **su questo campo** come TD-36 chiedeva: il
censimento di `status` non si eredita.

| Fonte | Comando | Valori trovati |
|---|---|---|
| Schema | `grep -rn priority backend/src/main/resources/db` | `VARCHAR(255) NOT NULL` da `V1`, nessun vincolo |
| Dev seed | `backend/src/main/resources/db/dev/V1__dev_seed_agents.sql` | nessun task seminato |
| Fixture di test | `grep -rhoE 'priority…' backend/src/test` | `HIGH` (≈20), `LOW` (≈6) |
| Storia git | `git log -p --all -- backend \| grep -oE 'priority…'` | `HIGH`, `LOW` |
| **Database di sviluppo reale** (`aicompany`, a `V3`) | `SELECT id, title, status, priority FROM tasks;` | **1 riga, `LOW`** |

**Due valori, scritti sempre esattamente così. Zero righe da trasformare.** `V9` è additiva su
questi dati; il database reale la passerà al primo avvio (verificato su un clone a fine fase).

Nessun hard stop: nessun dato da cancellare o trasformare, nessun mapping da inventare.
