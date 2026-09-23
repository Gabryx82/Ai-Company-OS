# TASK-018 — Provider dell'engine: Ollama (locale) e Anthropic (cloud)

**Fase**: PHASE 5. **ADR**: ADR-015 §5.

## Invarianti
- **I-1** Un provider non lascia mai uscire un'eccezione di libreria: ogni guasto è
  `provider-unavailable`, `provider-timeout`, `provider-error` o `unknown-model`.
- **I-2** Un Ollama spento è *non disponibile*, non un errore del listato.
- **I-3** Il provider Anthropic è spento senza `ANTHROPIC_API_KEY`, e lo dice con il rimedio.
- **I-4** `claude-opus-5` per default; `fallbacks: "default"` con la beta `server-side-fallback-2026-07-01`
  sui modelli che lo supportano, e solo su quelli.
- **I-5** Un rifiuto è `finish_reason: "refusal"`, non un errore.
- **I-6** Niente di ciò che dice il provider finisce nel `detail`.
- **I-7** Nessun test raggiunge una rete o un servizio a pagamento.
