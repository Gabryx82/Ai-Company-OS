# ADR-027 — Eliminare davvero progetti e task

- **Stato**: Accettata e implementata (PHASE 20)
- **Data**: 2026-09-25
- **Decisa da**: agente, su direttiva umana del 2026-09-25 (§2 «Eliminazione Project e Task»)
- **Modifica**: ADR-004 §8 e ADR-008, che avevano escluso la cancellazione fisica («nulla si cancella»)

## 1. Due gesti distinti

| Gesto | Chi | Reversibile | Cosa succede |
|---|---|---|---|
| **Archivia / Ripristina** (progetto) | operatore | sì | il progetto e le sue task si congelano |
| **Elimina** (progetto o task) | **solo admin** | **no** | le righe spariscono dal database |

## 2. Tre protezioni contro l'errore

1. **Ruolo**: `DELETE /api/projects/{id}` e `DELETE /api/tasks/{id}` sono `ADMIN` in
   `SecurityConfiguration` (ADR-024); un operatore riceve `403`.
2. **Il tag della riga** (ADR-009): senza `If-Match` `428`, con un tag vecchio `412`.
3. **Il nome digitato**: `?confirm=<nome>` deve coincidere con il nome del progetto o il titolo della
   task (maiuscole e spazi esterni ignorati), altrimenti `400 delete-confirmation-mismatch`. La console
   chiede di scriverlo, e il server lo verifica comunque.

In più: niente si elimina mentre un'esecuzione è in corso (`409 task-run-in-progress`), e ogni
eliminazione è un evento `DATA_DELETED` nel registro di sicurezza.

## 3. Le relazioni

- **Task**: con lei se ne vanno esecuzioni, handoff e review. Le voci del **Daily Work** che la
  citavano **restano**, come voci personali con il titolo della task e la nota «(task eliminata)».
- **Progetto**: il destino delle task **si sceglie sempre** (`tasks=DETACH|DELETE`, nessun default):
  `DETACH` le lascia senza progetto, fase, codice e documento di piano; `DELETE` le elimina come sopra.
  Fasi, generazioni di piano e risorse adottate se ne vanno con il progetto.
- **File**: la cartella del progetto e i documenti restano sul disco. Sono dell'operatore;
  un'eliminazione nel database non è il posto per cancellare file. La risposta lo dice e indica la
  cartella.

Prima di confermare, `GET /api/tasks/{id}/deletion` e `GET /api/projects/{id}/deletion?tasks=…`
mostrano cosa verrebbe eliminato.

## 4. Test

`DeletionApiTest` (operatore rifiutato; admin senza tag, con nome sbagliato, con nome giusto;
dipendenze eliminate e voci Daily conservate; esecuzione in corso; progetto con task staccate o
eliminate e senza default; archiviazione ancora reversibile), `ProjectApiTest` (l'operatore non
elimina), coperture `PreconditionCoverageTest` e `ApiProblemCoverageTest`; console:
`DeleteDialog.test` (impatto mostrato, conferma col nome, rifiuto con run in corso).
