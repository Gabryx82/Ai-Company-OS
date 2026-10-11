# ADR-038 — Consumi: Antigravity, Kimi Code e GitHub Copilot

- **Stato**: Accettata e implementata
- **Data**: 2026-10-11
- **Decisa da**: agente, su direttiva umana del 2026-10-11 («aggiungere nella scheda dei consumi i consumi di
  Antigravity, GitHub Copilot da VS Code e Kimi»)
- **Estende**: la scheda *Consumi* (PHASE 8, `quota_plans`, V13) con la stessa regola: un numero compare solo se
  qualcosa lo ha misurato. **Rispetta** la decisione dell'operatore su TD-50 (2026-10-11): nessun endpoint interno
  delle app letto con i loro token.

## 1. Che cosa c'è sul PC, misurato il 2026-10-11

| App | Che cosa registra in locale | Fonte scelta |
|---|---|---|
| **Antigravity** e **Antigravity IDE** | Nel database di stato (`%APPDATA%\<app>\User\globalStorage\state.vscdb`, SQLite) la chiave `antigravityUnifiedStateSync.userStatus`: lo stato utente che l'app riceve da Google, con **la quota di ogni modello** — frazione residua e ora del reset | Letta in sola lettura |
| **GitHub Copilot** (VS Code, CLI) | Nessuna quota né conteggio di premium request. VS Code salva solo lo SKU (`free_educational_quota`) e le sessioni di chat (una sola richiesta, di aprile); i log della CLI non contengono quote | Nessuna: solo il reset documentato |
| **Kimi** (app desktop) | Il livello d'abbonamento e una chiave Kimi Code dell'app; nessun consumo | **Kimi Code** chiesto a Kimi con la chiave **dell'operatore** |

## 2. Decisione

### 2.1 Antigravity — `ANTIGRAVITY_LOCAL`, finestra `PER_MODEL`

Due piani, uno per app (`antigravity-models`, `antigravity-ide-models`). Il valore è un protobuf in base64 di cui
non esiste uno schema pubblicato: la forma è **osservata**. Lo stato utente ha nel campo 33 un elemento per modello
con l'etichetta (1) e la quota (15): frazione residua (1, un `float` che protobuf omette quando vale zero, quindi
assente = esaurita) e reset (2, un timestamp). Lo stato utente si **cerca** (il messaggio che elenca modelli con una
quota), non si raggiunge per un percorso fisso: un involucro diverso non lo nasconde; una forma diversa degli elementi
dà «nessun dato», mai numeri sbagliati.

Il valore contiene anche nome ed email dell'account: **non escono mai** dal lettore (`AntigravityQuota`), che
restituisce solo etichette, percentuali e reset. Nessuna dipendenza protobuf: un lettore del formato wire di cento
righe (`ProtoWire`). SQLite è letto con `sqlite-jdbc` (versione gestita da Spring Boot), in sola lettura.

- `observedAt` è l'ora di modifica del database: le quote sono al più così vecchie.
- Un modello il cui reset è passato dopo l'ultimo salvataggio è mostrato a zero, «azzerata».
- La finestra è `MEASURED` con la percentuale del modello **più consumato** e il prossimo reset di un modello
  **usato**; `RESET_SINCE_OBSERVATION` se ogni quota si è azzerata da allora.
- La risposta porta `modelQuotas` (modello, percentuale, reset, azzerata da allora): campo nuovo, additivo.

### 2.2 Kimi Code — `KIMI_CODE_API`

Due piani (`kimi-code-5h`, `kimi-code-weekly`). È la richiesta della CLI ufficiale di Kimi Code per `/usage`:
`GET https://api.kimi.com/coding/v1/usages` con `Authorization: Bearer <chiave>`. La risposta ha la quota settimanale
(`usage`: `limit`, `used`/`remaining`, `resetTime`) e le finestre (`limits[]`, ciascuna con `window.duration` e
`timeUnit`); la finestra di 300 minuti è quella di 5 ore. I numeri possono arrivare come stringhe.

- La chiave è **quella dell'operatore** (`AICOS_KIMI_CODE_API_KEY` nel `local.env`, creata nella console di Kimi
  Code), mai quella che l'app desktop conserva. Senza chiave: `CONFIGURATION_NEEDED` con le istruzioni, e Kimi non
  viene chiamato.
- La chiave va solo all'URL configurato, in HTTPS (HTTP solo verso questa macchina, per i test); non è mai
  registrata né restituita. Una risposta è tenuta 60 secondi.
- `401`/`403` e ogni errore diventano una frase («Kimi Code ha rifiutato la chiave (401)…»), non uno zero.

### 2.3 GitHub Copilot — `MANUAL`, mensile

Un piano `copilot-monthly` con il reset documentato da GitHub: il primo del mese alle 00:00 UTC. Nessun numero:
VS Code legge le premium request da un servizio interno di GitHub con il token di Copilot (escluso dalla decisione su
TD-50), e l'API di fatturazione documentata copre solo i piani acquistati personalmente, non quello di questo
account (SKU `free_educational_quota`). La scheda mostra il conto alla rovescia e la pagina ufficiale.

## 3. Contratto e schema

- `UsageSource`: `ANTIGRAVITY_LOCAL`, `KIMI_CODE_API`; `WindowKind`: `PER_MODEL`. **V40** allarga i due vincoli
  `CHECK` (ogni riga valida prima lo resta).
- `UsageWindowResponse.modelQuotas`: lista, vuota per le altre fonti. Additivo.
- I piani nuovi arrivano da `catalog/quota-plans.json` all'avvio, come gli altri.

## 4. Che cosa non fa

- Non legge token o chiavi delle app (Copilot, Claude, ChatGPT, l'app Kimi).
- Non mostra i consumi della chat di Kimi (kimi.com): non ci sono sul PC né in un'API documentata.
- Le quote di Antigravity sono quelle dell'ultimo salvataggio dell'app: se l'app è chiusa da giorni, la scheda dice
  quando le ha salvate e che si sono azzerate da allora.

## 5. Test

`AntigravityQuotaTest` (un database costruito come quello reale, con nome ed email che non devono uscire; float
omesso = esaurita; valore non riconosciuto = nessun modello), `KimiCodeUsageTest` (server locale: quote e reset,
`limit − remaining`, cache di un minuto, `401` senza la chiave nel messaggio, solo HTTPS), `UsageSourcesApiTest`
(HTTP: modello più consumato, reset del modello usato, azzerata da allora, Kimi settimanale e 5 ore),
`UsageApiTest` (senza app né chiave: nessun numero inventato; Copilot con il reset del primo del mese),
`Usage.test.tsx`. Mutazioni viste rosse: float omesso letto come «tutto residuo», reset del modello non usato,
`used` non ricavato da `remaining`. Smoke dal vivo (porta 8091, database `aicompany_p33`): i due database reali di
Antigravity letti (7 e 14 modelli), Copilot con reset al 1° novembre, Kimi che chiede la chiave.
