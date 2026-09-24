"""OpenRouter through the OpenAI-compatible provider, behind a simulated transport."""
import json

import httpx
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.providers.echo import EchoProvider
from app.providers.openai_compatible import OpenAICompatibleProvider
from tests.conftest import AUTH, TOKEN, completion

PROBLEM = "urn:ai-company-os:engine:problem:"


def router(handler=None, key="sk-test"):
    transport = httpx.MockTransport(handler or (lambda request: httpx.Response(500)))
    return OpenAICompatibleProvider(name="openrouter", base_url="http://router.test/api/v1", api_key=key,
                                    models=("openrouter/auto",), timeout_seconds=5.0, billed=True,
                                    enable_hint="Set the key", transport=transport)


def app_with(provider):
    settings = Settings(profile="test", engine_token=TOKEN, ollama_url=None)
    return TestClient(create_app(settings, providers=[EchoProvider(), provider]))


def test_without_a_key_the_router_is_listed_unavailable_and_billed_and_refuses_to_complete():
    with app_with(router(key=None)) as client:
        models = client.get("/v1/models", headers=AUTH).json()["models"]
        entry = next(m for m in models if m["id"] == "openrouter:openrouter/auto")
        assert entry["available"] is False and entry["billed"] is True

        response = client.post("/v1/completions", headers=AUTH, json=completion(model="openrouter:openrouter/auto"))
        assert response.status_code == 503
        assert response.json()["type"] == PROBLEM + "provider-unavailable"


def test_a_completion_is_a_chat_completion_call_and_its_usage_comes_back():
    seen = {}

    def handler(request: httpx.Request):
        seen["path"] = request.url.path
        seen["auth"] = request.headers.get("authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"model": "anthropic/claude-x", "choices": [
            {"message": {"role": "assistant", "content": "Done."}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 11, "completion_tokens": 2}})

    with app_with(router(handler)) as client:
        body = client.post("/v1/completions", headers=AUTH,
                           json=completion(model="openrouter:anthropic/claude-x", system="S")).json()

    assert seen["path"] == "/api/v1/chat/completions"
    assert seen["auth"] == "Bearer sk-test"
    assert seen["body"]["model"] == "anthropic/claude-x"
    assert seen["body"]["messages"][0]["role"] == "system"
    assert body["output"] == "Done."
    assert body["provider"] == "openrouter"
    assert body["usage"] == {"input_tokens": 11, "output_tokens": 2}


def test_a_refused_key_and_a_broken_body_are_problems_not_crashes():
    with app_with(router(lambda request: httpx.Response(401))) as client:
        response = client.post("/v1/completions", headers=AUTH, json=completion(model="openrouter:x/y"))
        assert response.json()["type"] == PROBLEM + "provider-unavailable"

    with app_with(router(lambda request: httpx.Response(200, json={"choices": []}))) as client:
        response = client.post("/v1/completions", headers=AUTH, json=completion(model="openrouter:x/y"))
        assert response.status_code == 502
        assert response.json()["type"] == PROBLEM + "provider-error"
