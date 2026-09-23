# TASK-013 — Artefatto

## 1. Esito
TD-04 **chiuso**. Suite **223 → 238**, schema invariato (`V8`), nessuna migrazione.

## 2. Commit
| Hash | Contenuto |
|---|---|
| `a49981d` | `docs(phase-3)` — roadmap del blocco e piano di fase |
| `ac689f6` | `test(security)` — 8/10 deliberatamente rossi |
| `7bcfefd` | `feat(security)` — la chain, il registry, il `401` in ADR-007 |
| (successivi) | `test(security)` — il test trovato per mutazione; docs di chiusura |

## 3. Rotture dichiarate
Ogni richiesta a `/api/**` senza bearer token valido → `401`. È la rottura voluta di TD-04.

## 4. Verifica per mutazione
Albero verificato pulito prima e dopo ciascuna.

| # | Mutazione | Esito |
|---|---|---|
| M1 | `anyRequest().authenticated()` → `permitAll()` | **rosso** (6 test, compreso quello di copertura) |
| M2 | il filtro accetta qualunque token | **rosso** (3) |
| M3 | health non più pubblico | **rosso** (1) |
| M4 | un header non valido prosegue come anonimo | **verde → sopravvissuta**. Sulle rotte protette l'anonimo è comunque rifiutato, quindi nessun test distingueva i due disegni che il javadoc diceva diversi. Aggiunto `aWrongTokenIsRefusedEvenWhereNoTokenIsNeeded`; rieseguita: **rosso** |
| M5 | nessun controllo di lunghezza | **rosso** |

**Lezione sull'harness, da non ripetere.** Il primo giro dava cinque «rossi»: erano falsi. Il
wrapper Maven invocato da Python via `mvnw.cmd` non partiva (`powershell` non trovato) ed usciva
con codice 1 — che l'harness leggeva come test falliti. Adesso l'harness **si rifiuta di dare un
verdetto** se Surefire non ha riportato `Tests run:`. È la stessa famiglia della lezione di
TASK-009: prima di credere a un rosso, verificare che il test abbia girato davvero.

## 5. Rilievi LOW
- L-1 Nessun rate limiting sui `401`: un token si può tentare senza limite. Mitigato dal bind su
  loopback (TASK-014) e dalla lunghezza minima; non motivato finché non c'è esposizione in rete.
- L-2 Nessuna scadenza né rotazione dei token: si ruota cambiando la variabile e riavviando.
