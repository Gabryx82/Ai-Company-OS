"""Local models through Ollama (https://ollama.com), over its HTTP API.

Free and local, so enabled by default -- and an Ollama that is not running is a
model that is *unavailable*, reported as such, never an engine failure.
"""
from __future__ import annotations

import logging

import httpx

from app.contract import CompletionRequest, ModelInfo
from app.problems import EngineError, EngineProblem
from app.providers import ProviderResult

_FINISH = {"stop": "stop", "length": "length"}

log = logging.getLogger("aicos.engine")


class OllamaProvider:
    name = "ollama"

    def __init__(self, base_url: str, timeout_seconds: float, transport: httpx.AsyncBaseTransport | None = None,
                 fallbacks: dict[str, str] | None = None, num_ctx: int | None = None,
                 think: bool | None = False, num_ctx_by_model: dict[str, int] | None = None,
                 num_gpu_by_model: dict[str, int] | None = None):
        self._client = httpx.AsyncClient(base_url=base_url.rstrip("/"),
                                         timeout=httpx.Timeout(timeout_seconds, connect=3.0),
                                         transport=transport)
        # model -> the model it replaced (PHASE 29). Only a model Ollama has not
        # pulled falls back: a model that fails is a failure, not a missing model.
        self._fallbacks = dict(fallbacks or {})
        self._num_ctx = num_ctx
        self._num_ctx_by_model = dict(num_ctx_by_model or {})
        # PHASE 32 (TD-67): layers on the GPU, per model; a model not listed is Ollama's call.
        self._num_gpu_by_model = dict(num_gpu_by_model or {})
        # Thinking for text answers: False, True, or None for the model's own default.
        self._think = think

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
        tried = [model]
        first_error: EngineError | None = None
        while True:
            try:
                return await self._complete(tried[-1], request)
            except EngineError as error:
                first_error = first_error or error
                following = self._fallbacks.get(tried[-1])
                if error.problem is not EngineProblem.UNKNOWN_MODEL or not following or following in tried:
                    # The error names the model that was asked for, with its fix.
                    raise first_error if error.problem is EngineProblem.UNKNOWN_MODEL else error
                log.warning("Ollama has no model '%s': falling back to '%s'", tried[-1], following)
                tried.append(following)

    async def _complete(self, model: str, request: CompletionRequest) -> ProviderResult:
        messages = []
        if request.system:
            messages.append({"role": "system", "content": request.system})
        messages.extend({"role": m.role, "content": m.content} for m in request.messages)

        try:
            body = {
                "model": model,
                "messages": messages,
                "stream": False,
                "options": {"num_predict": request.max_tokens},
            }
            num_ctx = self._num_ctx_by_model.get(model, self._num_ctx)
            if num_ctx:
                # The window has to hold the prompt and the answer; a model whose
                # context cache is heavy keeps a smaller one.
                body["options"]["num_ctx"] = num_ctx
            if model in self._num_gpu_by_model:
                # With the integrated GPU on for every model, a model it slows down stays on the CPU.
                body["options"]["num_gpu"] = self._num_gpu_by_model[model]
            if request.response_schema is not None:
                body["format"] = request.response_schema
                body["think"] = False
            elif request.response_format == "json":
                body["format"] = "json"
                # Measured on the operator's machine (2026-09-24): with thinking on,
                # qwen3.5:9b spent more than 120 s before the first byte of a plan.
                # A JSON answer is validated by the caller anyway; the thinking is
                # what does not fit, not the answer.
                body["think"] = False
            elif self._think is not None:
                # PHASE 29 (ADR-034 §3.6), measured on this CPU: with thinking on,
                # qwen3.5 reasoned until the window or the budget ran out and gave no
                # answer; with it off the same model answered 4/4 in 742 tokens.
                body["think"] = self._think
            response = await self._client.post("/api/chat", json=body)
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
