"""Routes a completion to the provider its model id names."""
from __future__ import annotations

import time
import uuid

from app.contract import CompletionRequest, CompletionResponse, ModelList, Usage
from app.problems import EngineError, EngineProblem
from app.providers import Provider


def split_model_id(model_id: str) -> tuple[str, str]:
    """``"ollama:llama3.2"`` -> ``("ollama", "llama3.2")``; ``"echo"`` -> ``("echo", "default")``."""
    if ":" not in model_id:
        return model_id, "default"
    provider, _, name = model_id.partition(":")
    return provider, name


class Gateway:

    def __init__(self, providers: list[Provider], default_model: str) -> None:
        self._providers = {p.name: p for p in providers}
        self.default_model = default_model

    async def complete(self, request: CompletionRequest, correlation_id: str) -> CompletionResponse:
        model_id = request.model or self.default_model
        provider_name, model = split_model_id(model_id)

        provider = self._providers.get(provider_name)
        if provider is None:
            raise EngineError(EngineProblem.UNKNOWN_MODEL,
                              f"No provider '{provider_name}'. Known providers: {', '.join(sorted(self._providers))}",
                              errors={"model": f"unknown provider '{provider_name}'"})
        if not provider.knows(model):
            raise EngineError(EngineProblem.UNKNOWN_MODEL,
                              f"Provider '{provider_name}' does not serve '{model}'",
                              errors={"model": f"unknown model '{model_id}'"})

        started = time.monotonic()
        result = await provider.complete(model, request)
        latency_ms = int((time.monotonic() - started) * 1000)

        return CompletionResponse(
            id=f"cmpl_{uuid.uuid4().hex}",
            model=f"{provider_name}:{result.served_model or model}",
            provider=provider_name,
            output=result.output,
            finish_reason=result.finish_reason,
            usage=Usage(input_tokens=result.input_tokens, output_tokens=result.output_tokens),
            latency_ms=latency_ms,
            correlation_id=correlation_id,
        )

    async def models(self) -> ModelList:
        listed = []
        for provider in self._providers.values():
            listed.extend(await provider.models())
        return ModelList(default=self.default_model, models=listed)

    async def aclose(self) -> None:
        for provider in self._providers.values():
            await provider.aclose()
