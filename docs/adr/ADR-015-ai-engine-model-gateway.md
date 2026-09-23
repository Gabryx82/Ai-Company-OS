# ADR-015 — L'AI Engine: un model gateway Python, senza stato, dietro un contratto HTTP v1

- **Stato**: Accettata e implementata (TASK-017, TASK-018)
- **Data**: 2026-09-23
- **Decisa da**: agente, entro il blocco PHASE 3–7 autorizzato il 2026-09-23
- **Attua**: ADR-001 §2–3 («il servizio Python […] richiederà una decisione propria»): questa è
  quella decisione. **Non** decide il runtime a grafo (LangGraph o alternative), che ADR-001
  «Rinviato» lascia a un esperimento con criteri misurabili.

## 1. Che cosa fa, e che cosa non fa

Trasforma **una** richiesta di completamento in **una** risposta, attraverso un provider. Niente
altro. Nessun database, nessuno stato fra chiamate, nessuna regola di dominio, nessuna scrittura
sulle tabelle di Spring (ADR-001, confine di proprietà). Chi decide cosa gira e registra cosa è
successo è il control plane (PHASE 6).

## 2. Stack

FastAPI + uvicorn + httpx + pydantic v2, Python ≥ 3.11 (CI 3.12). Minimo, e nessuna dipendenza
di orchestrazione: un gateway non ne ha bisogno, e sceglierne una adesso sarebbe prendere per
omissione la decisione che ADR-001 ha rinviato. Il provider Anthropic usa l'**SDK ufficiale**
`anthropic` (TASK-018).

## 3. Contratto v1

| Rotta | Auth | Contenuto |
|---|---|---|
| `GET /health` | no | `{"status":"UP"}` e basta |
| `GET /v1/models` | sì | `{"default", "models":[{id, provider, available, detail, billed}]}` |
| `POST /v1/completions` | sì | `{model?, system?, messages[1..200], max_tokens 1..128000, metadata{}}` → `{id, model, provider, output, finish_reason: stop\|length\|refusal, usage{input_tokens, output_tokens}, latency_ms, correlation_id}` |

- **Id di modello** `"<provider>:<modello>"` (`echo:default`, `ollama:llama3.2`,
  `anthropic:claude-opus-5`); il solo nome del provider vale il suo `default`; assente vale il
  default configurato.
- **Campi sconosciuti rifiutati** (`extra="forbid"`): un client che crede di mandare
  `temperature` e viene ignorato in silenzio è peggio di uno che riceve `400`.
- **Errori**: RFC 9457, `type = urn:ai-company-os:engine:problem:<slug>` — prefisso diverso da
  quello del control plane, così un errore inoltrato dice sempre da che lato del confine è nato.
  `unauthenticated` 401, `validation-failed` 400 (con `errors` per campo), `unknown-model` 400,
  `provider-unavailable` 503, `provider-timeout` 504, `provider-error` 502, `internal-error` 500
  con `detail` fisso.
- **Correlation id**: `X-Correlation-Id` accettato se sicuro (`[A-Za-z0-9._:-]{1,128}`),
  altrimenti generato; restituito su ogni risposta, errori compresi.
- **Compatibilità**: aggiungere un campo facoltativo è compatibile; ogni altra modifica è `/v2`.

## 4. Autenticazione fra servizi

Un bearer token condiviso (`AICOS_ENGINE_TOKEN`), confronto a tempo costante
(`hmac.compare_digest`), stessa risposta per token assente e sbagliato, autenticazione **prima**
della validazione. Profili come il backend: in `dev` un default locale che dice «change me», fuori
da `dev` **nessun default** e l'engine non parte; token sotto i 16 caratteri → non parte. Bind su
`127.0.0.1` per default. Nessuna documentazione OpenAPI esposta.

## 5. Provider

| Provider | Costo | Abilitato | Disponibilità |
|---|---|---|---|
| `echo` | zero, offline | sempre | sempre. **Deterministico**, e dice nel testo di non essere un modello |
| `ollama` | zero, locale | se `AICOS_ENGINE_OLLAMA_URL` non è vuoto (default `http://127.0.0.1:11434`) | interrogata su `/api/tags`; assente → `available: false`, non un errore |
| `anthropic` | **a consumo** | **solo** se `ANTHROPIC_API_KEY` è impostata | modelli da `AICOS_ENGINE_ANTHROPIC_MODELS`, default `claude-opus-5`; `billed: true` |

Il provider cloud è **spento per costruzione** senza chiave: nessun percorso raggiunge un servizio
a pagamento per errore (charter, hard stop #6). Nessun test lo raggiunge: il trasporto è simulato.

Un adapter **non lascia mai uscire un'eccezione di libreria**: la traduce in uno dei tre problemi
di provider. Altrimenti un'interruzione del provider diventa un `500` che non dice quale né perché.

## 6. Fuori scope

Streaming, tool use, embedding, runtime a grafo, quote e costi aggregati, retry lato engine oltre a
quelli dell'SDK, cache.
