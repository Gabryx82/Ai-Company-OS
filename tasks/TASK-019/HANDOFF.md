# TASK-019 → prossimo agente

- Lanciare: `POST /api/tasks/{id}/runs` con l'`If-Match` **del task**; poi `GET /api/runs/{id}` finché
  `status` è `SUCCEEDED` o `FAILED`. Il task cambia tag se era `OPEN`: rileggerlo.
- Nei test Spring l'engine è `ScriptedEngineClient`: `answer`, `answerWith`, `fail`, `explode`,
  `holdNextCall`/`awaitEntered`/`release`, `requests()`. Nessun test deve raggiungere un engine vero.
- Una tabella nuova che referenzia `tasks` o `agents` va pulita in `AbstractPostgresTest`, altrimenti
  i `deleteAll()` delle altre classi falliscono per FK.
- Stream a `V10`.
