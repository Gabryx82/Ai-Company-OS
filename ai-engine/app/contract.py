"""Version 1 of the contract between the control plane and the AI Engine.

Explicit DTOs, as ADR-001 requires of the boundary. Everything the control plane
sends is validated here; everything the engine answers has exactly this shape,
whatever provider produced it. Adding an optional field is a compatible change;
anything else is ``/v2``.
"""
from __future__ import annotations

from typing import Any, Literal

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
    # PHASE 10: a JSON Schema the answer must follow. A provider with constrained
    # decoding (Ollama) enforces it token by token -- measured necessary: a local
    # 9B planner asked only for "json" returned a phase with no tasks.
    response_schema: dict[str, Any] | None = None
    # PHASE 35 (ADR-041 §11): how the model runs, as the control plane's catalog decides it per model.
    # Optional, so the addition is compatible; absent leaves the engine's own defaults. The window is
    # Ollama's num_ctx; keep_alive how long Ollama keeps the model loaded after the answer ("5m", "0" to
    # unload at once, "-1" to keep it). A provider without these knobs ignores them.
    context_window: int | None = Field(default=None, ge=512, le=1_048_576)
    keep_alive: str | None = Field(default=None, max_length=8, pattern=r"^(0|-1|[1-9][0-9]{0,5}[smh]?)$")


class Usage(BaseModel):
    input_tokens: int
    output_tokens: int


class Metrics(BaseModel):
    """PHASE 35: what the provider itself measured about this call, as it reported it.

    A field the provider did not report is null: nothing here is estimated.
    ``context_window`` is the exception that is not a measurement -- it is the
    window this call *asked* for (Ollama's ``num_ctx``), null when the call left
    the provider's own default.
    """
    total_duration_ms: int | None = None
    load_duration_ms: int | None = None
    prompt_eval_duration_ms: int | None = None
    eval_duration_ms: int | None = None
    context_window: int | None = None


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
    # PHASE 35: optional, so the addition is compatible; null for a provider that measures nothing.
    metrics: Metrics | None = None


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
