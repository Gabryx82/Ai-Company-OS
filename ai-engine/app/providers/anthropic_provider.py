"""Claude, through the official Anthropic Python SDK.

Billed per token, so **off unless ``ANTHROPIC_API_KEY`` is set** (ADR-015 §5,
charter hard stop #6): without a key the models are listed as unavailable and a
completion is refused with provider-unavailable. No test reaches the real API --
the SDK's HTTP transport is replaced.

Defaults follow the current API: ``claude-opus-5``, thinking left at the model's
default (adaptive on Opus 5), and the server-side refusal fallback opted in with
``fallbacks: "default"`` for the models that support it, so a request declined
by a safety classifier is re-run on Anthropic's recommended fallback inside the
same call instead of coming back empty. A refusal that survives the fallback is
reported as ``finish_reason: "refusal"``, never as an error.
"""
from __future__ import annotations

from typing import Callable

import anthropic

from app.config import Settings
from app.contract import CompletionRequest, ModelInfo
from app.problems import EngineError, EngineProblem
from app.providers import ProviderResult

FALLBACK_BETA = "server-side-fallback-2026-07-01"
MODELS_WITH_DEFAULT_FALLBACK = frozenset({"claude-opus-5", "claude-fable-5-1"})

_FINISH = {"end_turn": "stop", "stop_sequence": "stop", "max_tokens": "length", "refusal": "refusal"}


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, settings: Settings,
                 client_factory: Callable[[], anthropic.AsyncAnthropic] | None = None) -> None:
        self._models = settings.anthropic_models
        self._enabled = settings.anthropic_enabled
        self._client: anthropic.AsyncAnthropic | None = None
        if self._enabled:
            self._client = client_factory() if client_factory else anthropic.AsyncAnthropic(
                api_key=settings.anthropic_api_key,
                timeout=settings.anthropic_timeout_seconds,
                max_retries=settings.anthropic_max_retries,
            )

    def knows(self, model: str) -> bool:
        return model in self._models or model == "default"

    async def models(self) -> list[ModelInfo]:
        detail = None if self._enabled else "Set ANTHROPIC_API_KEY to enable; billed per token"
        return [ModelInfo(id=f"anthropic:{m}", provider=self.name, available=self._enabled,
                          detail=detail, billed=True) for m in self._models]

    async def complete(self, model: str, request: CompletionRequest) -> ProviderResult:
        if self._client is None:
            raise EngineError(EngineProblem.PROVIDER_UNAVAILABLE,
                              "The Anthropic provider is disabled: ANTHROPIC_API_KEY is not configured")
        if model == "default":
            model = self._models[0]

        arguments: dict = {
            "model": model,
            "max_tokens": request.max_tokens,
            "messages": [{"role": m.role, "content": m.content} for m in request.messages],
        }
        if request.system:
            arguments["system"] = request.system
        if model in MODELS_WITH_DEFAULT_FALLBACK:
            arguments["betas"] = [FALLBACK_BETA]
            arguments["fallbacks"] = "default"

        # Most specific first: APITimeoutError is a subclass of APIConnectionError,
        # and the status errors each mean something different to the caller.
        try:
            message = await self._client.beta.messages.create(**arguments)
        except anthropic.APITimeoutError:
            raise EngineError(EngineProblem.PROVIDER_TIMEOUT, "Anthropic did not answer in time")
        except anthropic.APIConnectionError:
            raise EngineError(EngineProblem.PROVIDER_UNAVAILABLE, "Anthropic is not reachable")
        except (anthropic.AuthenticationError, anthropic.PermissionDeniedError):
            raise EngineError(EngineProblem.PROVIDER_UNAVAILABLE,
                              "Anthropic rejected the configured credential; check ANTHROPIC_API_KEY")
        except anthropic.NotFoundError:
            raise EngineError(EngineProblem.UNKNOWN_MODEL, f"Anthropic has no model '{model}'",
                              errors={"model": f"unknown model 'anthropic:{model}'"})
        except anthropic.RateLimitError:
            raise EngineError(EngineProblem.PROVIDER_UNAVAILABLE, "Anthropic is rate limiting this key; retry later")
        except anthropic.APIStatusError as error:
            raise EngineError(EngineProblem.PROVIDER_ERROR, f"Anthropic answered {error.status_code}")

        # Check the stop reason before reading content: a refusal carries none.
        finish = _FINISH.get(message.stop_reason or "end_turn", "stop")
        text = "".join(block.text for block in message.content if getattr(block, "type", None) == "text")

        return ProviderResult(
            output=text,
            finish_reason=finish,
            input_tokens=message.usage.input_tokens,
            output_tokens=message.usage.output_tokens,
            served_model=message.model,
        )

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.close()
