"""Configuration of the AI Engine, read once from the environment.

Mirrors the control plane's profiles on purpose (ADR-015 §4): ``dev`` is the
default and carries local-development defaults that say "change me"; ``prod``
has no defaults for anything secret, so a missing value stops the process
instead of letting it run open.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field

MINIMUM_TOKEN_LENGTH = 16

DEV_ENGINE_TOKEN = "dev-engine-token-change-me"


class ConfigurationError(RuntimeError):
    """The engine refuses to start. Raised at startup, never per request."""


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
        env = dict(os.environ if env is None else env)
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
