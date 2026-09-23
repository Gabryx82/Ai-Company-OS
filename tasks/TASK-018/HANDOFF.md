# TASK-018 → prossimo agente

- Modelli locali: `ollama:<nome>` come compare in `/v1/models` (per esempio `ollama:llama3.2:3b`).
- Cloud: impostare `ANTHROPIC_API_KEY` **abilita spesa reale**. Nessun codice del repository la imposta.
- `finish_reason` può essere `refusal`: il control plane (PHASE 6) deve registrarlo come esito, non
  come fallimento tecnico.
- `latency_ms` di un modello locale è dell'ordine delle decine di secondi: il client Java deve avere
  un timeout di lettura coerente (≥ 120 s), e l'esecuzione deve essere asincrona.
