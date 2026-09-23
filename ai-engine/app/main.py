"""The AI Engine: the Python service of ADR-001, in the shape ADR-015 decided.

It owns provider calls and nothing else. No database, no state between calls,
no domain rules: the control plane decides what runs and records what happened;
this service turns one request into one completion.
"""
from __future__ import annotations

import hmac
import logging
import re
import uuid
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.config import Settings
from app.contract import CONTRACT_VERSION, CompletionRequest, CompletionResponse, ModelList
from app.gateway import Gateway
from app.problems import EngineError, EngineProblem, problem_response
from app.providers import Provider
from app.providers.echo import EchoProvider

log = logging.getLogger("aicos.engine")

CORRELATION_HEADER = "X-Correlation-Id"
_CORRELATION_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def default_providers(settings: Settings) -> list[Provider]:
    providers: list[Provider] = [EchoProvider()]
    return providers


def create_app(settings: Settings | None = None, providers: list[Provider] | None = None) -> FastAPI:
    settings = settings or Settings.from_environment()
    gateway = Gateway(providers if providers is not None else default_providers(settings),
                      settings.default_model)
    expected_token = settings.engine_token.encode("utf-8")

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await gateway.aclose()

    app = FastAPI(title="AI Company OS — AI Engine", version=CONTRACT_VERSION, lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    # --- correlation id -------------------------------------------------------

    @app.middleware("http")
    async def correlation(request: Request, call_next):
        incoming = request.headers.get(CORRELATION_HEADER)
        correlation_id = incoming if incoming and _CORRELATION_PATTERN.match(incoming) else uuid.uuid4().hex
        request.state.correlation_id = correlation_id
        response = await call_next(request)
        response.headers[CORRELATION_HEADER] = correlation_id
        return response

    # --- authentication between services (ADR-001 boundary, ADR-015 §4) ------

    def require_service_token(request: Request) -> None:
        header = request.headers.get("Authorization", "")
        scheme, _, token = header.strip().partition(" ")
        presented = token.strip().encode("utf-8") if scheme.lower() == "bearer" else b""
        # compare_digest: the time taken does not depend on where the tokens differ.
        if not presented or not hmac.compare_digest(presented, expected_token):
            raise EngineError(EngineProblem.UNAUTHENTICATED, "This request must carry the engine's bearer token")

    # --- errors: one renderer -------------------------------------------------

    @app.exception_handler(EngineError)
    async def on_engine_error(request: Request, error: EngineError):
        if error.problem.status >= 500:
            log.warning("correlation=%s problem=%s detail=%s",
                        getattr(request.state, "correlation_id", None), error.problem.slug, error.detail)
        return problem_response(error.problem, error.detail,
                                getattr(request.state, "correlation_id", None), error.errors)

    @app.exception_handler(RequestValidationError)
    async def on_validation(request: Request, error: RequestValidationError):
        errors: dict[str, str] = {}
        for item in error.errors():
            location = [str(part) for part in item.get("loc", ()) if part != "body"]
            errors.setdefault(".".join(location) or "body", item.get("msg", "invalid"))
        return problem_response(EngineProblem.VALIDATION_FAILED, "The request body failed validation",
                                getattr(request.state, "correlation_id", None), errors)

    @app.exception_handler(StarletteHTTPException)
    async def on_http(request: Request, error: StarletteHTTPException):
        problem = EngineProblem.NOT_FOUND if error.status_code in (404, 405) else EngineProblem.INTERNAL_ERROR
        return problem_response(problem, "There is nothing at this path" if problem is EngineProblem.NOT_FOUND
                                else "The request could not be completed",
                                getattr(request.state, "correlation_id", None))

    @app.exception_handler(Exception)
    async def on_anything(request: Request, error: Exception):
        # Logged in full, answered with a fixed detail: nothing internal crosses
        # the boundary (the rule of ADR-007 §4, on this side too).
        log.exception("correlation=%s unhandled", getattr(request.state, "correlation_id", None))
        return problem_response(EngineProblem.INTERNAL_ERROR, "The request could not be completed",
                                getattr(request.state, "correlation_id", None))

    # --- routes ---------------------------------------------------------------

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/v1/models", response_model=ModelList, dependencies=[Depends(require_service_token)])
    async def list_models() -> ModelList:
        return await gateway.models()

    @app.post("/v1/completions", response_model=CompletionResponse,
              dependencies=[Depends(require_service_token)])
    async def complete(body: CompletionRequest, request: Request) -> CompletionResponse:
        correlation_id = request.state.correlation_id
        log.info("correlation=%s model=%s metadata=%s", correlation_id, body.model or gateway.default_model,
                 body.metadata)
        return await gateway.complete(body, correlation_id)

    return app
