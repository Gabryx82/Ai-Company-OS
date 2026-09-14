# TASK-007 — Handoff

## Stato
Completata. Suite **158 verdi**, schema **`V4`**.

## Le tre cose da sapere

1. **`status` su un agente è derivato, non esiste nel database.** Un test lo asserisce. Se
   qualcuno lo materializza, lo stesso stato finisce in due posti che possono divergere — che è
   l'alternativa che ADR-004 ha scartato per `deleted_at` e ADR-006 per la cascata.

2. **`agents` ha un `DEFAULT now()` sui timestamp, e serve.** Il seed di sviluppo è una
   migrazione versionata che inserisce senza fornirli: senza il default lo stream si rompe.
   Se un giorno il seed verrà riscritto, il default potrà andarsene con lui.

3. **`Agent` non ha relazioni.** Né con `Project` né con `Task`, con la stessa motivazione che
   ADR-004 §1 diede per `Project`: le domande di dominio — un task ha un agente? un agente
   appartiene a un progetto? è condivisibile? — non sono state poste.

## Candidati per il passo successivo

| Candidato | Nota |
|---|---|
| **Relazione `Task` → `Agent`** | Il nodo naturale, come TASK-003 lo fu per i progetti. Richiede prima le tre domande di sopra, come ADR-005 fece per le sue |
| **TD-14 — CI** | 158 test, invarianti di concorrenza, guardie verificate per mutazione, e nulla che li esegua automaticamente |
| **TD-04 / TD-11 — sicurezza e CORS** | L'API ha adesso tre registri con scritture e nessuna autenticazione. La superficie cresce a ogni task |
| **TD-28 / TD-30** | Rilevazione dell'intento stantio, `ETag`/`If-Match`. Si chiudono insieme |
| **TD-31** | Unificare il ciclo di vita di `Agent` con l'enum di `Project`. Richiede una decisione umana sulla migrazione |
