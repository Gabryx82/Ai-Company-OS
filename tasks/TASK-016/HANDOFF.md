# TASK-016 → prossimo agente

- `priority` è `TaskPriority` (enum) nel dominio; nel JSON resta il nome.
- `new Task(title, description, TaskStatus, TaskPriority)`.
- Stream a **`V9`**; la prossima è `V10`. I due test che pinnano la lista delle versioni
  (`SchemaMigrationTest`, `DevSeedMigrationTest`) vanno aggiornati a ogni migrazione: è voluto.
