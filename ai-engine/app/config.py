"""Configuration of the AI Engine, read once from the environment.

Mirrors the control plane's profiles on purpose (ADR-015 §4): ``dev`` is the
default and carries local-development defaults that say "change me"; ``prod``
has no defaults for anything secret, so a missing value stops the process
instead of letting it run open.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

MINIMUM_TOKEN_LENGTH = 16

DEV_ENGINE_TOKEN = "dev-engine-token-change-me"

# PHASE 29 (ADR-034): when Ollama has not pulled a model, a model that can do its
# work answers instead, followed as a chain. The served model is reported, so a
# fallback is never silent. lfm2.5:8b falls back to the model it replaced for the
# Test Engineer; gemma4:e4b to its previous package; qwen3.5:9b to its smaller
# sibling, which has the same capabilities (tools, vision, thinking).
DEFAULT_OLLAMA_FALLBACKS = {
    "lfm2.5:8b": "qwen3.5:4b",
    "gemma4:e4b": "gemma4:e4b-legacy",
    "qwen3.5:9b": "qwen3.5:4b",
}
DEFAULT_OLLAMA_NUM_CTX = 16384
# Models whose context cache is heavy keep a smaller window. Measured at 16K:
# deepseek-coder-v2:16b +3.2 GB (12.6 GB in all), llama3.2:3b +1.3 GB, against
# +0.15-0.4 GB for qwen3.5, gemma4 and lfm2.5.
DEFAULT_OLLAMA_NUM_CTX_BY_MODEL = {
    "deepseek-coder-v2:16b": 4096,
    "llama3.2:3b": 4096,
}
# PHASE 32 (TD-67): how many layers a model may put on the GPU (Ollama's num_gpu).
# Ollama turns the integrated GPU on for every model at once (OLLAMA_IGPU_ENABLE=1);
# measured on the Arc 140V, it speeds Qwen 3.5 and Gemma 4 up 2-3.7x on the prompt
# but halves LFM2.5's generation, which therefore stays on the CPU (0 layers). With
# the iGPU off -- the default -- 0 is what happens anyway.
DEFAULT_OLLAMA_NUM_GPU_BY_MODEL = {
    "lfm2.5:8b": 0,
}


def local_secrets(path: Path | None = None) -> dict[str, str]:
    """The operator's local secrets file (ADR-024 §6): ``KEY=VALUE`` lines.

    ``~/.aicos/local.env`` by default (``AICOS_HOME`` moves it), written by
    ``scripts/init-local-secrets.ps1`` and never inside the repository. The
    control plane imports the same file, so both processes agree on the engine
    token however each of them was started. Real environment variables win.
    """
    if path is None:
        home = os.environ.get("AICOS_HOME")
        path = Path(home) if home else Path.home() / ".aicos"
        path = path / "local.env"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return {}
    values: dict[str, str] = {}
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


class ConfigurationError(RuntimeError):
    """The engine refuses to start. Raised at startup, never per request."""


def _pairs(value: str) -> dict[str, str]:
    """``"a:1=b:1, c:2=d:2"`` -> ``{"a:1": "b:1", "c:2": "d:2"}``."""
    pairs = {}
    for part in value.split(","):
        key, _, target = part.partition("=")
        if key.strip() and target.strip():
            pairs[key.strip()] = target.strip()
    return pairs


def _think(value: str) -> bool | None:
    """``true`` / ``false``, or ``model`` for the model's own default."""
    normalized = value.strip().lower()
    if normalized in ("model", "default", ""):
        return None
    if normalized in ("true", "false"):
        return normalized == "true"
    raise ConfigurationError(f"AICOS_ENGINE_OLLAMA_THINK must be true, false or model, not {value!r}")


def _csv(value: str | None) -> tuple[str, ...]:
    if not value:
        return ()
    return tuple(part.strip() for part in value.split(",") if part.strip())


@dataclass(frozen=True)
class Settings:
    profile: str = "dev"
    engine_token: str = DEV_ENGINE_TOKEN
    default_model: str = "echo:default"

    # Local models: Ollama. Enabled by default because it costs nothing and is
    # reported as unavailable, not as an error, when nothing listens there.
    ollama_url: str | None = "http://127.0.0.1:11434"
    ollama_timeout_seconds: float = 600.0
    ollama_fallbacks: dict[str, str] = field(default_factory=lambda: dict(DEFAULT_OLLAMA_FALLBACKS))
    # PHASE 29: the context window asked of Ollama. Without a GPU Ollama defaults to
    # 4096 tokens, which a thinking model fills with its reasoning before it answers
    # (measured: done_reason "length", content ""). None leaves Ollama's default.
    ollama_num_ctx: int | None = DEFAULT_OLLAMA_NUM_CTX
    ollama_num_ctx_by_model: dict[str, int] = field(default_factory=lambda: dict(DEFAULT_OLLAMA_NUM_CTX_BY_MODEL))
    # PHASE 32 (TD-67): per model, the layers Ollama may offload to the GPU; a model not
    # listed is left to Ollama.
    ollama_num_gpu_by_model: dict[str, int] = field(default_factory=lambda: dict(DEFAULT_OLLAMA_NUM_GPU_BY_MODEL))
    # PHASE 29: whether a text answer thinks first. False by default: on this CPU a
    # thinking qwen3.5 spent its whole budget reasoning and answered nothing, while
    # without thinking it answered correctly. None leaves the model's own default.
    ollama_think: bool | None = False

    # Cloud models: Anthropic. Disabled unless a key is configured -- a model
    # that bills per token is never reachable by accident (charter hard stop #6).
    anthropic_api_key: str | None = None
    anthropic_models: tuple[str, ...] = ("claude-opus-5",)
    anthropic_timeout_seconds: float = 600.0
    anthropic_max_retries: int = 2

    # PHASE 8: OpenRouter, an OpenAI-compatible router. Disabled unless a key is
    # configured, for the same reason as Anthropic.
    openrouter_api_key: str | None = None
    openrouter_url: str = "https://openrouter.ai/api/v1"
    openrouter_models: tuple[str, ...] = ("openrouter/auto",)
    openrouter_timeout_seconds: float = 600.0

    cors_origins: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if self.profile not in ("dev", "test", "prod"):
            raise ConfigurationError(f"AICOS_ENGINE_PROFILE must be dev, test or prod, not {self.profile!r}")
        if not self.engine_token or len(self.engine_token.strip()) < MINIMUM_TOKEN_LENGTH:
            raise ConfigurationError(
                "AICOS_ENGINE_TOKEN must be set to at least "
                f"{MINIMUM_TOKEN_LENGTH} characters: the engine refuses to run unauthenticated"
            )
        if ":" not in self.default_model:
            raise ConfigurationError(
                f"AICOS_ENGINE_DEFAULT_MODEL must be '<provider>:<model>', not {self.default_model!r}"
            )

    @property
    def anthropic_enabled(self) -> bool:
        return bool(self.anthropic_api_key)

    @staticmethod
    def from_environment(env: dict[str, str] | None = None) -> "Settings":
        env = {**local_secrets(), **os.environ} if env is None else dict(env)
        profile = env.get("AICOS_ENGINE_PROFILE", "dev")

        token = env.get("AICOS_ENGINE_TOKEN")
        if token is None:
            if profile != "dev":
                raise ConfigurationError(
                    "AICOS_ENGINE_TOKEN is required outside the dev profile; there is no default"
                )
            token = DEV_ENGINE_TOKEN

        ollama_url = env.get("AICOS_ENGINE_OLLAMA_URL", "http://127.0.0.1:11434")
        return Settings(
            profile=profile,
            engine_token=token,
            default_model=env.get("AICOS_ENGINE_DEFAULT_MODEL", "echo:default"),
            ollama_url=ollama_url or None,
            ollama_timeout_seconds=float(env.get("AICOS_ENGINE_OLLAMA_TIMEOUT_SECONDS", "600")),
            ollama_fallbacks=_pairs(env["AICOS_ENGINE_OLLAMA_FALLBACKS"]) if "AICOS_ENGINE_OLLAMA_FALLBACKS" in env
            else dict(DEFAULT_OLLAMA_FALLBACKS),
            ollama_num_ctx=int(env.get("AICOS_ENGINE_OLLAMA_NUM_CTX", DEFAULT_OLLAMA_NUM_CTX)) or None,
            ollama_num_ctx_by_model={model: int(size) for model, size in
                                     _pairs(env["AICOS_ENGINE_OLLAMA_NUM_CTX_BY_MODEL"]).items()}
            if "AICOS_ENGINE_OLLAMA_NUM_CTX_BY_MODEL" in env else dict(DEFAULT_OLLAMA_NUM_CTX_BY_MODEL),
            ollama_num_gpu_by_model={model: int(layers) for model, layers in
                                     _pairs(env["AICOS_ENGINE_OLLAMA_NUM_GPU_BY_MODEL"]).items()}
            if "AICOS_ENGINE_OLLAMA_NUM_GPU_BY_MODEL" in env else dict(DEFAULT_OLLAMA_NUM_GPU_BY_MODEL),
            ollama_think=_think(env.get("AICOS_ENGINE_OLLAMA_THINK", "false")),
            anthropic_api_key=env.get("ANTHROPIC_API_KEY") or None,
            anthropic_models=_csv(env.get("AICOS_ENGINE_ANTHROPIC_MODELS")) or ("claude-opus-5",),
            anthropic_timeout_seconds=float(env.get("AICOS_ENGINE_ANTHROPIC_TIMEOUT_SECONDS", "600")),
            anthropic_max_retries=int(env.get("AICOS_ENGINE_ANTHROPIC_MAX_RETRIES", "2")),
            openrouter_api_key=env.get("AICOS_ENGINE_OPENROUTER_API_KEY") or None,
            openrouter_url=env.get("AICOS_ENGINE_OPENROUTER_URL", "https://openrouter.ai/api/v1"),
            openrouter_models=_csv(env.get("AICOS_ENGINE_OPENROUTER_MODELS"))
            or ("openrouter/auto",),
            openrouter_timeout_seconds=float(env.get("AICOS_ENGINE_OPENROUTER_TIMEOUT_SECONDS", "600")),
        )
