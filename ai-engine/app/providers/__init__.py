"""Model providers behind one interface.

A provider turns a ``CompletionRequest`` into a ``ProviderResult`` or raises an
``EngineError`` with one of the provider problems. It never raises anything
else on purpose: an adapter that lets a library exception escape turns a
provider outage into a 500 that says nothing about which provider or why.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from app.contract import CompletionRequest, FinishReason, ModelInfo


@dataclass(frozen=True)
class ProviderResult:
    output: str
    finish_reason: FinishReason
    input_tokens: int
    output_tokens: int
    # The provider's own name for what ran, when it differs from the request
    # (a fallback model, a resolved alias).
    served_model: str | None = None


class Provider(Protocol):
    name: str

    async def complete(self, model: str, request: CompletionRequest) -> ProviderResult:
        """``model`` is the part after ``<provider>:``."""
        ...

    async def models(self) -> list[ModelInfo]:
        ...

    def knows(self, model: str) -> bool:
        """Whether ``model`` is a name this provider could serve at all."""
        ...

    async def aclose(self) -> None:
        ...
