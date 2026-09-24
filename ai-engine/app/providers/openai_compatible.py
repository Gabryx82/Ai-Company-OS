"""Any server speaking the OpenAI chat-completions dialect (PHASE 8, ADR-018).

Used for OpenRouter -- the optional router to many cloud models behind one
interface -- and, in principle, for any other OpenAI-compatible endpoint such as
a DwarfStar 4 server running on another machine.

Off without a key, exactly like the Anthropic provider (ADR-015 §5, charter hard
stop #6): the models are listed as unavailable and a completion is refused with
provider-unavailable. No test reaches a real endpoint.
"""
from __future__ import annotations

import httpx

from app.contract import CompletionRequest, ModelInfo
from app.problems import EngineError, EngineProblem
from app.providers import ProviderResult

_FINISH = {"stop": "stop", "length": "length", "content_filter": "refusal"}


class OpenAICompatibleProvider:

    def __init__(self, name: str, base_url: str, api_key: str | None, models: tuple[str, ...],
                 timeout_seconds: float, billed: bool, enable_hint: str,
                 transport: httpx.AsyncBaseTransport | None = None):
        self.name = name
        self._models = models
        self._enabled = bool(api_key)
        self._billed = billed
        self._enable_hint = enable_hint
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=timeout_seconds,
                                         headers=headers, transport=transport)

    def knows(self, model: str) -> bool:
        # A router serves more models than anybody lists: any non-empty name is
        # plausible, and the router itself says whether it exists.
        return bool(model)

    async def models(self) -> list[ModelInfo]:
        detail = None if self._enabled else self._enable_hint
        return [ModelInfo(id=f"{self.name}:{m}", provider=self.name, available=self._enabled,
                          detail=detail, billed=self._billed) for m in self._models]

    async def complete(self, model: str, request: CompletionRequest) -> ProviderResult:
        if not self._enabled:
            raise EngineError(EngineProblem.PROVIDER_UNAVAILABLE,
                              f"The {self.name} provider is disabled: {self._enable_hint}")
        messages = []
        if request.system:
            messages.append({"role": "system", "content": request.system})
        messages.extend({"role": m.role, "content": m.content} for m in request.messages)

        try:
            response = await self._client.post("/chat/completions", json={
                "model": model,
                "messages": messages,
                "max_tokens": request.max_tokens,
            })
        except httpx.TimeoutException as error:
            raise EngineError(EngineProblem.PROVIDER_TIMEOUT,
                              f"{self.name} did not answer in time ({type(error).__name__})")
        except httpx.HTTPError as error:
            raise EngineError(EngineProblem.PROVIDER_UNAVAILABLE,
                              f"{self.name} is not reachable ({type(error).__name__})")

        if response.status_code == 404:
            raise EngineError(EngineProblem.UNKNOWN_MODEL, f"{self.name} has no model '{model}'",
                              errors={"model": f"unknown model '{self.name}:{model}'"})
        if response.status_code in (401, 403):
            raise EngineError(EngineProblem.PROVIDER_UNAVAILABLE, f"{self.name} refused the configured key")
        if response.status_code >= 400:
            raise EngineError(EngineProblem.PROVIDER_ERROR, f"{self.name} answered {response.status_code}")

        try:
            body = response.json()
            choice = body["choices"][0]
            output = choice["message"]["content"] or ""
            usage = body.get("usage") or {}
        except (ValueError, KeyError, IndexError, TypeError):
            raise EngineError(EngineProblem.PROVIDER_ERROR,
                              f"{self.name} answered with a body this engine cannot read")
        if not output:
            raise EngineError(EngineProblem.PROVIDER_ERROR, f"{self.name} answered with an empty completion")

        return ProviderResult(
            output=output,
            finish_reason=_FINISH.get(choice.get("finish_reason") or "stop", "stop"),
            input_tokens=int(usage.get("prompt_tokens", 0)),
            output_tokens=int(usage.get("completion_tokens", 0)),
            served_model=body.get("model") or model,
        )

    async def aclose(self) -> None:
        await self._client.aclose()
