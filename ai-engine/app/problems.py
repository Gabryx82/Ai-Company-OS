"""The engine's error contract: RFC 9457 problem details, one closed set.

The same discipline ADR-007 imposed on the control plane: ``type`` is the
machine-readable contract and is stable, ``title`` is prose. The prefix differs
(``engine:problem``) so that a problem the control plane relays can always be
traced to the side of the boundary that raised it.
"""
from __future__ import annotations

from enum import Enum

from fastapi.responses import JSONResponse

URN_PREFIX = "urn:ai-company-os:engine:problem:"


class EngineProblem(Enum):
    UNAUTHENTICATED = ("unauthenticated", 401, "Authentication required")
    VALIDATION_FAILED = ("validation-failed", 400, "Invalid request payload")
    UNKNOWN_MODEL = ("unknown-model", 400, "Unknown model")
    PROVIDER_UNAVAILABLE = ("provider-unavailable", 503, "Model provider unavailable")
    PROVIDER_TIMEOUT = ("provider-timeout", 504, "Model provider timed out")
    PROVIDER_ERROR = ("provider-error", 502, "Model provider failed")
    NOT_FOUND = ("resource-not-found", 404, "Resource not found")
    INTERNAL_ERROR = ("internal-error", 500, "Internal error")

    def __init__(self, slug: str, status: int, title: str) -> None:
        self.slug = slug
        self.status = status
        self.title = title

    @property
    def type(self) -> str:
        return URN_PREFIX + self.slug


class EngineError(Exception):
    """Raised anywhere in the engine; rendered once, by the app's handler."""

    def __init__(self, problem: EngineProblem, detail: str, *, errors: dict[str, str] | None = None) -> None:
        super().__init__(detail)
        self.problem = problem
        self.detail = detail
        self.errors = errors


def problem_response(problem: EngineProblem, detail: str, correlation_id: str | None,
                     errors: dict[str, str] | None = None) -> JSONResponse:
    body: dict[str, object] = {
        "type": problem.type,
        "title": problem.title,
        "status": problem.status,
        "detail": detail,
    }
    if errors:
        body["errors"] = errors
    headers = {"X-Correlation-Id": correlation_id} if correlation_id else {}
    if problem is EngineProblem.UNAUTHENTICATED:
        headers["WWW-Authenticate"] = "Bearer"
    return JSONResponse(status_code=problem.status, content=body, headers=headers,
                        media_type="application/problem+json")
