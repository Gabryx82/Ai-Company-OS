"""Local models through Ollama (https://ollama.com), over its HTTP API.

Free and local, so enabled by default -- and an Ollama that is not running is a
model that is *unavailable*, reported as such, never an engine failure.
"""
from __future__ import annotations

import httpx

from app.contract import CompletionRequest, ModelInfo
from app.problems import EngineError, EngineProblem
from app.providers import ProviderResult

_FINISH = {"stop": "stop", "length": "length"}


class OllamaProvider:
    name = "ollama"

    def __init__(self, base_url: str, timeout_seconds: float, transport: httpx.AsyncBaseTransport | None = None):
        self._client = httpx.AsyncClient(base_url=base_url.rstrip("/"),
                                         timeout=httpx.Timeout(timeout_seconds, connect=3.0),
                                         transport=transport)

    def knows(self, model: str) -> bool:
        # Which models exist is Ollama's to say, and it says so at call time: a
        # model that was never pulled comes back as unknown-model, with the fix.
        return bool(model) and model != "default"

    async def models(self) -> list[ModelInfo]:
        try:
            response = await self._client.get("/api/tags", timeout=3.0)
            response.raise_for_status()
            names = [m["name"] for m in response.json().get("models", [])]
        except (httpx.HTTPError, ValueError, KeyError):
            return [ModelInfo(id="ollama:*", provider=self.name, available=False,
                              detail="Ollama is not reachable; start it and pull a model")]
        if not names:
            return [ModelInfo(id="ollama:*", provider=self.name, available=False,
                              detail="Ollama is running but has no model; run `ollama pull <model>`")]
        return [ModelInfo(id=f"ollama:{name}", provider=self.name, available=True) for name in names]

    async def complete(self, model: str, request: CompletionRequest) -> ProviderResult:
        messages = []
        if request.system:
            messages.append({"role": "system", "content": request.system})
        messages.extend({"role": m.role, "content": m.content} for m in request.messages)

        try:
            response = await self._client.post("/api/chat", json={
                "model": model,
                "messages": messages,
                "stream": False,
                "options": {"num_predict": request.max_tokens},
            })
        except httpx.TimeoutException as error:
            raise EngineError(EngineProblem.PROVIDER_TIMEOUT, f"Ollama did not answer in time ({type(error).__name__})")
        except httpx.HTTPError as error:
            raise EngineError(EngineProblem.PROVIDER_UNAVAILABLE,
                              f"Ollama is not reachable ({type(error).__name__}); is it running?")

        if response.status_code == 404:
            raise EngineError(EngineProblem.UNKNOWN_MODEL,
                              f"Ollama has no model '{model}'; run `ollama pull {model}`",
                              errors={"model": f"unknown model 'ollama:{model}'"})
        if response.status_code >= 400:
            raise EngineError(EngineProblem.PROVIDER_ERROR, f"Ollama answered {response.status_code}")

        try:
            body = response.json()
            output = body["message"]["content"]
        except (ValueError, KeyError, TypeError):
            raise EngineError(EngineProblem.PROVIDER_ERROR, "Ollama answered with a body this engine cannot read")

        return ProviderResult(
            output=output,
            finish_reason=_FINISH.get(body.get("done_reason", "stop"), "stop"),
            input_tokens=int(body.get("prompt_eval_count", 0)),
            output_tokens=int(body.get("eval_count", 0)),
            served_model=body.get("model") or model,
        )

    async def aclose(self) -> None:
        await self._client.aclose()
