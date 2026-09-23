# TASK-017 — AI Engine: scheletro, contratto v1, provider echo

**Fase**: PHASE 5. **ADR**: ADR-015. **Milestone**: M-5 (metà provider).

## Invarianti
- **I-1** Ogni rotta `/v1/*` rifiuta un chiamante senza il token dell'engine (`401`), prima di validare.
- **I-2** Token assente e sbagliato: risposte identiche.
- **I-3** Ogni errore è un problem detail con `type` stabile; la validazione nomina il campo.
- **I-4** Campi sconosciuti nella richiesta sono rifiutati.
- **I-5** L'echo è deterministico, dichiara di non essere un modello, rispetta `max_tokens`.
- **I-6** Il correlation id attraversa la chiamata, anche negli errori; uno insicuro è sostituito.
- **I-7** Fuori da `dev` l'engine non parte senza token.
