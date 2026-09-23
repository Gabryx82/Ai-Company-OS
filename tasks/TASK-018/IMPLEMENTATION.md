# TASK-018 — Implementazione

- **SDK ufficiale per Anthropic**, non HTTP grezzo: è il default per un progetto Python, e le classi
  d'errore tipizzate (`APITimeoutError`, `NotFoundError`, `RateLimitError`, …) sono ciò che rende
  la mappatura ai problemi dell'engine esatta invece che dedotta da stringhe.
- **Non-streaming con timeout esplicito** (600 s): il contratto v1 non espone streaming.
- **`thinking` non impostato**: su `claude-opus-5` il default è adattivo.
- **`fallbacks="default"`** solo per `claude-opus-5` e `claude-fable-5-1`; altri modelli configurati
  ricevono la richiesta senza, perché inviare la beta dove non è supportata sarebbe un `400`.
- **Ollama**: `knows()` accetta ogni nome — quali modelli esistono lo dice Ollama al momento della
  chiamata (404 → `unknown-model` con il comando `ollama pull`).
