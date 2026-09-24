"""Version 1 of the contract between the control plane and the AI Engine.

Explicit DTOs, as ADR-001 requires of the boundary. Everything the control plane
sends is validated here; everything the engine answers has exactly this shape,
whatever provider produced it. Adding an optional field is a compatible change;
anything else is ``/v2``.
"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

CONTRACT_VERSION = "v1"


class Message(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=200_000)


class CompletionRequest(BaseModel):
    """One completion. Stateless: the engine keeps nothing between calls."""

    model_config = ConfigDict(extra="forbid")

    # "<provider>:<model>", e.g. "echo:default", "ollama:llama3.2",
    # "anthropic:claude-opus-5". Absent means the engine's configured default.
    model: str | None = Field(default=None, max_length=200)
    system: str | None = Field(default=None, max_length=200_000)
    messages: list[Message] = Field(min_length=1, max_length=200)
    max_tokens: int = Field(default=4096, ge=1, le=128_000)
    # Opaque to the engine; logged with the correlation id, never interpreted.
    metadata: dict[str, str] = Field(default_factory=dict, max_length=20)
    # PHASE 10: ask for a JSON object (the planner's plan.json). Optional, so the
    # addition is compatible; a provider without a JSON mode ignores it and the
    # caller validates what comes back anyway.
    response_format: Literal["json"] | None = None


class Usage(BaseModel):
    input_tokens: int
    output_tokens: int


FinishReason = Literal["stop", "length", "refusal"]


class CompletionResponse(BaseModel):
    id: str
    model: str
    provider: str
    output: str
    finish_reason: FinishReason
    usage: Usage
    latency_ms: int
    correlation_id: str


class ModelInfo(BaseModel):
    id: str
    provider: str
    available: bool
    # Why a model is unavailable, in words an operator can act on.
    detail: str | None = None
    # True for models that bill per token. The control plane shows it; it does
    # not decide anything with it.
    billed: bool = False


class ModelList(BaseModel):
    default: str
    models: list[ModelInfo]
