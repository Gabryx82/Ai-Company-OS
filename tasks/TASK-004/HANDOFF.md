# TASK-004 — Handoff

Per l'agente successivo. Breve di proposito: il dettaglio è in `ARTIFACT.md` e in ADR-006.

## Stato

TASK-004 **completata**, suite **126 verdi**, schema a **`V3`**, nessuna migrazione.
Branch `task-004-archival-consistency`, integrato in `autonomous/phase-1-foundations`.

## Le tre cose da sapere prima di toccare `task` o `project`

1. **Esiste un protocollo di lock, ed è universale (L7).** Qualunque percorso di scrittura la
   cui correttezza dipende da `Project.status` lo applica: riga del task esclusiva **prima**
   (L0), poi le righe dei progetti condivise (L2), ordine `tasks` → `projects` per id crescente
   (L5). Non c'è un caso «tanto questo è innocuo»: è il ragionamento che ha prodotto TD-25.
   Enunciato in ADR-006 §4, con la tabella di ogni percorso esistente.

2. **L'aciclicità dipende da ADR-006 §1.** Non ci sono deadlock perché chi tocca i progetti non
   prende mai un lock su un task. Vale finché `archive`/`restore` non scrivono righe di `tasks`.
   Se un giorno servisse, l'ordine globale va **rivisto**, non aggirato.

3. **La regola di congelamento legge un proxy non inizializzato.** `TaskService` legge l'id del
   progetto dalla riga bloccata e solo dopo blocca quel progetto. È corretto **solo** finché
   leggere l'id non carica il progetto. Un test lo pinna
   (`readingTheProjectIdDoesNotLoadTheProject`): se si aggiunge una `join fetch` alla query L0
   diventa rosso — e comunque PostgreSQL rifiuta `FOR UPDATE` sul lato nullable di un outer join.

## Debito che il prossimo lavoro incontrerà

| ID | Perché conta |
|---|---|
| **TD-07** | Tre forme di errore convivono. **Assorbe TD-20, TD-27 e TD-29.** È il debito più grosso e più vicino alla superficie |
| **TD-28 / TD-30** | Gemelli: manca la rilevazione dell'intento stantio, su `Project` e su `Task`. Si chiudono insieme, con `ETag`/`If-Match`, non con un lock |
| **TD-22** | `V1` → `V2` non ha il test di upgrade incrementale. Il modello esiste già (`MigrationStreamTest`), applicarlo è meccanico |
| **TD-26** | Il path lazy resta non esercitato fuori transazione. Ora parzialmente coperto |

Chiusi da questa task: **TD-19(a), TD-24, TD-25**.

## Domande di contratto ancora aperte

- Nessun modo di sapere in anticipo che un task è congelato: lo si scopre dal `409`.
- Nessun `DELETE /api/tasks/{id}/project`: `NULL → non NULL` resta a senso unico.
- Nessun filtro «task senza progetto» su `GET /api/tasks`.
- `archive` non idempotente; `GET /api/projects` senza filtro include gli archiviati.

Vanno decise insieme, quando esisterà un client reale che le pone.
