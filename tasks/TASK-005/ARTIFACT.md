# TASK-005 — Artifact

**Completata il 2026-09-14.** Branch `task-005-uniform-error-contract`.
Suite **136 verdi**. Schema fermo a `V3`. **Nessuno stato HTTP cambiato.**

## Che cosa è cambiato, osservabile

| Richiesta | Prima | Ora |
|---|---|---|
| `POST /api/tasks {}` | `400`, `{timestamp, status, error, path}` | `400`, `ProblemDetail` + `errors` |
| Errore su `/api/agents` | default di Spring | `ProblemDetail` |
| JSON malformato, `Content-Type` mancante | default di Spring | `ProblemDetail` |
| `GET /api/projects/abc/tasks` | default di Spring | `ProblemDetail`, stesso dialetto di `/999/tasks` |
| `DELETE /api/projects/{id}` | `405`, default | `405`, `ProblemDetail` |
| Fallimento imprevisto, locking incluso | default di Spring | `ProblemDetail` `500`, detail fisso |
| Ogni errore | nessun `type` | `type: urn:ai-company-os:problem:<slug>` |

**Rottura dichiarata**: i corpi di errore di `/api/agents` e delle risposte preesistenti di
`/api/tasks` cambiano forma. È il lavoro della task.

## Acceptance criteria

Tutti soddisfatti. AC-11 in particolare: **nessun test di stato preesistente è stato modificato
per farlo passare.** I due test rimossi lo sono stati perché asserivano l'opposto di quanto ora
deciso, non perché fallivano in modo scomodo.

## Debito

| ID | Esito |
|---|---|
| **TD-07** | **CLOSED** |
| **TD-20** | **CLOSED** — assorbito |
| **TD-21** | **CLOSED** — l'asserzione debole sparisce col test che la conteneva |
| **TD-27** | **CLOSED** — assorbito |
| **TD-29** | **CLOSED** — assorbito come **forma**. Nessuna policy di timeout o retry: ADR-006 §7 resta valida |

## Commit

| Hash | Contenuto |
|---|---|
| `40c3578` | `test(api)` — i tre dialetti, 9 test deliberatamente rossi |
| `2f77f47` | `feat(api)` — il contratto unico |
