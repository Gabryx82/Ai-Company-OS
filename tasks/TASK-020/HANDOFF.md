# TASK-020 → prossimo agente

- `agents.model` (V11): un id dell'engine o `null`. Il campo è nel `PUT` degli agenti.
- Routing: `POST /api/routing/suggestions {"text": …}` e `GET /api/tasks/{id}/agent-suggestions`.
  Restituisce **tutti** gli agenti attivi con `score` e `matchedTerms`: la console può mostrare il
  primo come suggerimento e gli altri come alternative.
- Stream a `V11`.
