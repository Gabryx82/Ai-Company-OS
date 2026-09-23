"""The deterministic provider: no model, no network, no cost.

It exists so that the whole execution path -- control plane, contract, engine,
back -- can be run and tested end to end on any machine, offline, with results
that are the same every time. Its output says plainly that it is an echo: it
must never be mistaken for a model's answer.
"""
from __future__ import annotations

from app.contract import CompletionRequest, ModelInfo
from app.providers import ProviderResult

MODELS = ("default",)


def _words(text: str) -> int:
    return len(text.split())


class EchoProvider:
    name = "echo"

    def knows(self, model: str) -> bool:
        return model in MODELS

    async def models(self) -> list[ModelInfo]:
        return [ModelInfo(id=f"echo:{m}", provider=self.name, available=True,
                          detail="Deterministic echo; not a language model") for m in MODELS]

    async def complete(self, model: str, request: CompletionRequest) -> ProviderResult:
        last_user = next(m.content for m in reversed(request.messages) if m.role == "user") \
            if any(m.role == "user" for m in request.messages) else request.messages[-1].content

        lines = ["[echo] This response was produced by the deterministic echo provider, not by a model."]
        if request.system:
            first_line = request.system.strip().splitlines()[0]
            lines.append(f"[echo] Acting as: {first_line}")
        lines.append(f"[echo] Received {len(request.messages)} message(s). The last one from the user was:")
        lines.append(last_user)

        output = "\n".join(lines)
        finish = "stop"
        words = output.split()
        if len(words) > request.max_tokens:
            output = " ".join(words[: request.max_tokens])
            finish = "length"

        prompt_words = sum(_words(m.content) for m in request.messages) + _words(request.system or "")
        return ProviderResult(output=output, finish_reason=finish,
                              input_tokens=prompt_words, output_tokens=_words(output))

    async def aclose(self) -> None:
        return None
