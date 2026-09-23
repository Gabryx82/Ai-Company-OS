# TASK-018 — Artefatto

## Implementazione
| File | Ruolo |
|---|---|
| `ai-engine/app/providers/ollama.py` | `/api/chat` non-streaming, `num_predict`, `/api/tags` per il listato; trasporto iniettabile |
| `ai-engine/app/providers/anthropic_provider.py` | SDK ufficiale `anthropic` 1.8, `AsyncAnthropic`, `beta.messages.create` con `fallbacks="default"`; client iniettabile |
| `ai-engine/app/main.py` | `default_providers`: echo, Ollama (se URL), Anthropic (sempre registrato, attivo solo con chiave) |

Test: `ai-engine/tests/test_providers.py` — **21**; suite engine **30 → 51**. Trasporti simulati:
`httpx.MockTransport` per Ollama, `httpx2.MockTransport` dentro `DefaultAsyncHttpxClient` per l'SDK.

## Verifica per mutazione
| # | Mutazione | Esito |
|---|---|---|
| P1 | niente `fallbacks` | rosso |
| P2 | `APITimeoutError` catturata dopo `APIConnectionError` (sua superclasse) | rosso |
| P3 | rifiuto mappato a `stop` | rosso |
| P4 | 404 di Ollama non riconosciuto | rosso |
| P5 | Ollama spento → `provider-error` | rosso |
| P6 | messaggio del provider nel `detail` | rosso |

## Smoke test reale (2026-09-23)
Engine avviato con `python -m app` (dev): `/health` `UP`; anonimo `401`; `/v1/models` elenca echo,
**quattro modelli Ollama realmente installati su questa macchina** (`llama3.2:3b`, `qwen3.5:9b`,
`gemma4:e4b`, `deepseek-coder-v2:16b`) e `anthropic:claude-opus-5` non disponibile senza chiave.
**Completion reale** con `ollama:llama3.2:3b`: 107 token in 13 s, `finish_reason: stop`. Modello non
installato → `400 unknown-model` con `ollama pull` nel dettaglio. Nessuna chiamata cloud.

## LOW
- L-1 Nessuno streaming: una completion lunga su Ollama tiene la connessione aperta fino alla fine.
- L-2 Il costo delle chiamate cloud non è contato né limitato dall'engine (quote: fuori scope, ADR-015 §6).
