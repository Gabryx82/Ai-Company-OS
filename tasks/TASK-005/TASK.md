# TASK-005 — Uniform Error Contract

> **Autonomous Project Mode.** Scope definito, deciso e approvato dall'agente secondo
> `AUTONOMOUS_CHARTER.md` §2. Nessuna approvazione intermedia richiesta.

## Baseline
- Branch: `task-005-uniform-error-contract`, da `autonomous/phase-1-foundations` (`1ddeee3`).
- Suite di partenza: **126 verdi**.

## Perché questa task, adesso

`AUTONOMOUS_LOOP.md` §4, livello 3: debito che diventa pericoloso per il passo successivo.
I livelli 1 e 2 sono vuoti — nessun invariante promesso è falso, nessun blocker architetturale.

TD-07 peggiora da solo. L'API parla **tre dialetti di errore** e ogni endpoint nuovo deve
sceglierne uno o aggiungerne un quarto. Ogni pezzo della target architecture in arrivo — Agent
Registry, Planner, Model Gateway — è superficie nuova che eredita la scelta. E TD-07 **assorbe
TD-20, TD-27 e TD-29**, che sono aspetti dello stesso contratto mancante.

## Il problema, oggi

| Richiesta | Risposta |
|---|---|
| `POST /api/projects {}` | `ProblemDetail` con `errors` |
| `POST /api/tasks {}` | default di Spring: `{timestamp, status, error, path}` |
| `GET /api/projects/999/tasks` | `ProblemDetail` |
| `GET /api/projects/abc/tasks` | default di Spring — **stessa rotta, due dialetti** (TD-27) |
| JSON malformato su `/api/projects` | default di Spring (TD-20) |
| Qualunque errore su `/api/agents` | default di Spring |
| Fallimento di lock | default di Spring (TD-29) |

## Decisioni

In `docs/adr/ADR-007-uniform-error-contract.md`. **Supera ADR-004 §6 e ADR-005 §8**, che
avevano dichiarato la limitazione al modulo come temporanea e l'avevano tracciata come TD-07.

| # | Decisione |
|---|---|
| 1 | Un **advice globale** che estende `ResponseEntityExceptionHandler`, così le eccezioni del framework entrano nel contratto senza essere rincorse a ogni upgrade |
| 2 | Ogni problema ha un **`type` stabile**, `urn:ai-company-os:problem:<slug>`, enumerato in `ApiProblem`. **Il `type` è il contratto, il `title` è la prosa** |
| 3 | I `400` di validazione elencano i campi in `errors`, **ovunque** |
| 4 | Un **catch-all** `500` con `detail` fisso e log completo: nessun dettaglio interno esce |
| 5 | I fallimenti di locking prendono quella forma. **Nessuna policy**: niente timeout, retry o `503` — ADR-006 §7 resta valida |
| 6 | I **due advice di modulo spariscono**: un contratto con due punti di definizione dipende da una precedenza che nessuno ha scelto |

## Invarianti

| ID | Invariante | Come si verifica |
|---|---|---|
| **I-1** | **Ogni** risposta di errore dell'API è un `ProblemDetail` con `type`, `title`, `status`, `detail` | Test su tutte le famiglie: dominio, validazione, framework, catch-all |
| **I-2** | Il `type` di ogni problema è stabile ed enumerato: nessun handler ne inventa uno | Test sull'insieme esatto di `ApiProblem` |
| **I-3** | Due problemi diversi non condividono uno slug | Test sull'unicità degli slug |
| **I-4** | Nessuno stato HTTP cambia rispetto a prima: cambiano solo i corpi | I test di stato preesistenti restano verdi senza modifiche |
| **I-5** | Il `detail` di un `500` non contiene nulla dell'eccezione interna | Test che provoca un fallimento e asserisce il `detail` fisso |
| **I-6** | Ogni eccezione di dominio dichiarata ha una mappatura | Test strutturale sulle classi di eccezione del package |

## In scope

1. `ApiProblem`: enum di slug, stato e titolo.
2. `ApiExceptionHandler`: advice globale che estende `ResponseEntityExceptionHandler`.
3. Rimozione di `ProjectExceptionHandler` e `TaskExceptionHandler`.
4. Test che dimostrano i tre dialetti **prima** dell'implementazione, e il contratto dopo.
5. Riconversione di `TaskExceptionHandlerScopeTest` da guardia contro l'allargamento a guardia
   di **completezza**.
6. Artefatti, ADR-007, `PROJECT_STATE.md`.

## Fuori scope

- Qualunque cambio di **codice di stato**. Solo i corpi.
- Migrazioni, schema, entità, service, repository: **non si toccano**.
- Policy di timeout, retry, `503` → ADR-006 §7 resta valida.
- Internazionalizzazione dei messaggi.
- `ETag`/`If-Match` → TD-28, TD-30.
- Autenticazione, CORS (TD-11), paginazione, CI.
- Nuovi endpoint, `GET /api/tasks/{id}`, filtri.
- Debito documentale.
- Merge in `master`, push, remote.

## Acceptance criteria

- **AC-1** — `POST /api/tasks {}` risponde `400` `application/problem+json` con `type`, `title`,
  `status`, `detail` e `errors` che elenca i campi. Lo **stato** è lo stesso di prima.
- **AC-2** — `POST /api/projects {}` risponde come prima nella sostanza, e adesso porta anche
  `type`.
- **AC-3** — JSON malformato e `Content-Type` mancante → `ProblemDetail` (TD-20).
- **AC-4** — `GET /api/projects/abc/tasks` e `PUT /api/tasks/abc/project` → `ProblemDetail`,
  stesso dialetto di `GET /api/projects/999/tasks` (TD-27).
- **AC-5** — `DELETE /api/projects/{id}` → `405` `ProblemDetail` (ADR-004 §3 invariato).
- **AC-6** — Un errore su `/api/agents` → `ProblemDetail`.
- **AC-7** — Ogni eccezione di dominio esistente produce il proprio `type`: progetto non
  trovato, nome duplicato, archiviato immutabile, transizione illegale, task non trovato,
  progetto archiviato non riceve task, task congelato.
- **AC-8** (I-2, I-3) — L'insieme degli slug è esatto e senza duplicati.
- **AC-9** (I-5) — Un fallimento imprevisto produce `500` con `detail` fisso, e **nulla**
  dell'eccezione originale nel corpo.
- **AC-10** (I-6) — Ogni classe di eccezione nei package di dominio ha una mappatura. Aggiungerne
  una senza mapparla fallisce.
- **AC-11** (I-4) — I test di stato preesistenti restano verdi **senza modifiche**.
- **AC-12** — Suite completa verde.
- **AC-13** — Verifica per mutazione su AC-9, AC-10 e sull'unicità degli slug.

## Chiusure attese

| ID | Esito |
|---|---|
| **TD-07** | CLOSED |
| **TD-20** | CLOSED — assorbito |
| **TD-27** | CLOSED — assorbito |
| **TD-29** | CLOSED — assorbito, come **forma** e non come policy |
| **TD-21** | CLOSED — l'asserzione debole sparisce col test che la conteneva |
