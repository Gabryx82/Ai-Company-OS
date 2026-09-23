# TASK-013 → prossimo agente

- Ogni `/api/**` richiede `Authorization: Bearer <token>`. Token in `aicos.security.api-tokens.<nome>`.
- **Nei test non serve fare niente**: `AbstractPostgresTest` porta già il token dell'operatore.
  Per testare l'anonimo costruire un `MockMvc` proprio (vedi `ApiAuthenticationContractTest`).
- Una rotta nuova è coperta automaticamente, e `everyApiRouteRefusesAnAnonymousCaller` lo verifica.
- Il principal (`Authentication#getName()`) è il nome del token: usarlo per registrare «chi».
- Mutazioni: l'harness deve rifiutare un verdetto senza output di Surefire (ARTIFACT §4).
