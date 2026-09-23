# TASK-017 → prossimo agente

- Avvio: `cd ai-engine && python -m venv .venv && .venv/Scripts/python -m pip install -r requirements-dev.txt`,
  poi `.venv/Scripts/python -m app` (dev, token `dev-engine-token-change-me`, porta 8090).
- Test: `.venv/Scripts/python -m pytest` (non aggiungere `-q`: è già in `addopts`).
- Il client Java (PHASE 6) deve mandare `Authorization: Bearer <AICOS_ENGINE_TOKEN>` e
  `X-Correlation-Id`, e ramificare sul `type` dei problemi, non sul `title`.
