# TASK-013 — Implementazione

| File | Ruolo |
|---|---|
| `security/ApiTokenProperties` | `aicos.security.api-tokens.<nome>=<token>` |
| `security/ApiTokenRegistry` | Fail closed; digest SHA-256; confronto a tempo costante su tutte le voci |
| `security/BearerTokenAuthenticationFilter` | Header assente → anonimo; header non valido → `401` subito; valido → principal |
| `security/ProblemAuthenticationEntryPoint` | Consegna l'eccezione ai resolver MVC: il `401` lo rende `ApiExceptionHandler` |
| `security/SecurityConfiguration` | Chain stateless, CSRF/Basic/form/logout off, health pubblico, `anyRequest().authenticated()` |
| `api/ApiProblem.UNAUTHENTICATED` | Nuovo `type` `urn:ai-company-os:problem:unauthenticated` |
| `api/ApiExceptionHandler` | Handler di `AuthenticationException` con `WWW-Authenticate: Bearer` |
| `application*.properties` | Token `dev` con default locale, `prod` solo da ambiente; actuator ridotto a `health` senza dettagli |
| `pom.xml` | `spring-boot-starter-security`, `-actuator`, `-security-test` |

**Test**: `ApiAuthenticationContractTest` (11) cammina **ogni** rotta del `RequestMappingHandlerMapping`
sotto `/api`; `ApiTokenRegistryTest` (4). `AuthenticatedMockMvcConfiguration` rende il `MockMvc`
condiviso un operatore autenticato: **nessun test esistente modificato**.

**Rosso prima**: 8/10 rossi contro la security di default di Boot (commit `ac689f6`).
