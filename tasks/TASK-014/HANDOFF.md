# TASK-014 → prossimo agente
- Il frontend di PHASE 7 deve essere servito da un'origine in `aicos.cors.allowed-origins`
  (default dev: `http://localhost:5173`). Deve mandare il token nell'header, e può leggere `ETag`.
- In `dev` il backend è su `127.0.0.1`: un servizio in un container Docker **non** lo raggiunge
  su `host.docker.internal` senza `SERVER_ADDRESS=0.0.0.0`. Rilevante per l'AI Engine se
  containerizzato (PHASE 5).
