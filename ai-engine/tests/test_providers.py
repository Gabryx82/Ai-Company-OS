"""Ollama and Anthropic, behind simulated transports: no network, no cost."""
import json

import anthropic
import httpx
import httpx2
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.providers.anthropic_provider import FALLBACK_BETA, AnthropicProvider
from app.providers.echo import EchoProvider
from app.providers.ollama import OllamaProvider
from tests.conftest import AUTH, TOKEN, completion

PROBLEM = "urn:ai-company-os:engine:problem:"


def app_with(*providers):
    settings = Settings(profile="test", engine_token=TOKEN, ollama_url=None)
    return TestClient(create_app(settings, providers=[EchoProvider(), *providers]))


# --- Ollama -----------------------------------------------------------------

def ollama(handler):
    return OllamaProvider("http://ollama.test", 5.0, transport=httpx.MockTransport(handler))


def test_ollama_turns_the_contract_into_a_chat_call_and_back():
    seen = {}

    def handler(request: httpx.Request):
        seen["path"] = request.url.path
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"model": "llama3.2", "message": {"role": "assistant", "content": "Plan: ..."},
                                         "done_reason": "stop", "prompt_eval_count": 12, "eval_count": 3})

    with app_with(ollama(handler)) as client:
        body = client.post("/v1/completions", json=completion("Plan it", model="ollama:llama3.2",
                                                              system="You are the planner", max_tokens=64),
                           headers=AUTH).json()

    assert seen["path"] == "/api/chat"
    assert seen["body"]["stream"] is False
    assert seen["body"]["options"] == {"num_predict": 64}
    assert seen["body"]["messages"][0] == {"role": "system", "content": "You are the planner"}
    assert seen["body"]["messages"][1] == {"role": "user", "content": "Plan it"}
    assert body["output"] == "Plan: ..."
    assert body["model"] == "ollama:llama3.2"
    assert body["usage"] == {"input_tokens": 12, "output_tokens": 3}


def test_ollama_is_asked_for_the_context_window_it_is_configured_with():
    # PHASE 29, measured: with Ollama's 4096-token default on a machine without a GPU,
    # qwen3.5 reasoned for 3873 tokens, reached the right answer inside its thinking
    # and ran out of window before writing it -- done_reason "length", content "".
    seen = {}

    def handler(request):
        seen["options"] = json.loads(request.content)["options"]
        return httpx.Response(200, json={"message": {"content": "ok"}})

    provider = OllamaProvider("http://ollama.test", 5.0, transport=httpx.MockTransport(handler), num_ctx=16384)
    with app_with(provider) as client:
        client.post("/v1/completions", json=completion(model="ollama:qwen3.5:9b", max_tokens=64), headers=AUTH)
    assert seen["options"] == {"num_predict": 64, "num_ctx": 16384}


def think_sent(provider_kwargs, **request):
    seen = {}

    def handler(r):
        seen["body"] = json.loads(r.content)
        return httpx.Response(200, json={"message": {"content": "ok"}})

    provider = OllamaProvider("http://ollama.test", 5.0, transport=httpx.MockTransport(handler), **provider_kwargs)
    with app_with(provider) as client:
        client.post("/v1/completions", json=completion(model="ollama:qwen3.5:9b", **request), headers=AUTH)
    return seen["body"].get("think", "absent")


def test_text_runs_do_not_think_unless_configured_to():
    # PHASE 29, measured on this CPU: with thinking on, qwen3.5 reasoned 3873 tokens
    # (12 minutes) and never answered; with think false it answered 4/4 in 742 tokens.
    assert think_sent({}) is False
    assert think_sent({"think": True}) is True
    assert think_sent({"think": None}) == "absent"  # the model's own default
    # A JSON answer never thinks, whatever the setting: the plan must fit the budget.
    assert think_sent({"think": True}, response_format="json") is False


def test_the_think_setting_comes_from_the_environment():
    assert Settings.from_environment({}).ollama_think is False
    assert Settings.from_environment({"AICOS_ENGINE_OLLAMA_THINK": "true"}).ollama_think is True
    assert Settings.from_environment({"AICOS_ENGINE_OLLAMA_THINK": "model"}).ollama_think is None


def test_a_model_with_a_heavy_context_cache_keeps_a_smaller_window():
    # PHASE 29, measured: at 16K deepseek-coder-v2:16b grows by 3.2 GB (to 12.6 GB),
    # llama3.2:3b by 1.3 GB; qwen3.5, gemma4 and lfm2.5 by 0.15-0.4 GB.
    seen = []

    def handler(r):
        seen.append(json.loads(r.content))
        return httpx.Response(200, json={"message": {"content": "ok"}})

    provider = OllamaProvider("http://ollama.test", 5.0, transport=httpx.MockTransport(handler), num_ctx=16384,
                              num_ctx_by_model={"deepseek-coder-v2:16b": 4096})
    with app_with(provider) as client:
        client.post("/v1/completions", json=completion(model="ollama:deepseek-coder-v2:16b"), headers=AUTH)
        client.post("/v1/completions", json=completion(model="ollama:qwen3.5:9b"), headers=AUTH)
    assert [b["options"]["num_ctx"] for b in seen] == [4096, 16384]


def test_the_per_model_windows_come_from_the_environment():
    assert Settings.from_environment({}).ollama_num_ctx_by_model == {"deepseek-coder-v2:16b": 4096, "llama3.2:3b": 4096}
    assert Settings.from_environment({"AICOS_ENGINE_OLLAMA_NUM_CTX_BY_MODEL": "x:1=8192"}).ollama_num_ctx_by_model \
        == {"x:1": 8192}
    assert Settings.from_environment({"AICOS_ENGINE_OLLAMA_NUM_CTX_BY_MODEL": ""}).ollama_num_ctx_by_model == {}


def test_the_context_window_comes_from_the_environment_and_zero_leaves_ollamas_default():
    assert Settings.from_environment({}).ollama_num_ctx == 16384
    assert Settings.from_environment({"AICOS_ENGINE_OLLAMA_NUM_CTX": "8192"}).ollama_num_ctx == 8192
    assert Settings.from_environment({"AICOS_ENGINE_OLLAMA_NUM_CTX": "0"}).ollama_num_ctx is None


def test_ollama_length_is_reported_as_length():
    handler = lambda r: httpx.Response(200, json={"message": {"content": "cut"}, "done_reason": "length"})
    with app_with(ollama(handler)) as client:
        assert client.post("/v1/completions", json=completion(model="ollama:x"), headers=AUTH) \
            .json()["finish_reason"] == "length"


def test_an_ollama_that_is_not_running_is_unavailable_not_broken():
    def handler(request):
        raise httpx.ConnectError("refused")

    with app_with(ollama(handler)) as client:
        response = client.post("/v1/completions", json=completion(model="ollama:llama3.2"), headers=AUTH)
        models = client.get("/v1/models", headers=AUTH).json()["models"]

    assert response.status_code == 503
    assert response.json()["type"] == PROBLEM + "provider-unavailable"
    assert {"id": "ollama:*", "provider": "ollama", "available": False,
            "detail": "Ollama is not reachable; start it and pull a model", "billed": False} in models


def test_a_model_ollama_has_not_pulled_is_an_unknown_model_with_the_fix():
    handler = lambda r: httpx.Response(404, json={"error": "model 'nope' not found"})
    with app_with(ollama(handler)) as client:
        response = client.post("/v1/completions", json=completion(model="ollama:nope"), headers=AUTH)
    assert response.status_code == 400
    assert response.json()["type"] == PROBLEM + "unknown-model"
    assert "ollama pull nope" in response.json()["detail"]


def fallback_ollama(handler, fallbacks):
    return OllamaProvider("http://ollama.test", 5.0, transport=httpx.MockTransport(handler), fallbacks=fallbacks)


def pulled(*names):
    """An Ollama that has pulled only ``names``: anything else is its 404."""
    def handler(request: httpx.Request):
        model = json.loads(request.content)["model"]
        if model not in names:
            return httpx.Response(404, json={"error": f"model '{model}' not found"})
        return httpx.Response(200, json={"model": model, "message": {"content": f"from {model}"}})
    return handler


def test_a_model_that_is_not_pulled_falls_back_to_the_previous_one_and_says_so():
    provider = fallback_ollama(pulled("qwen3.5:4b"), {"lfm2.5:8b": "qwen3.5:4b"})
    with app_with(provider) as client:
        body = client.post("/v1/completions", json=completion(model="ollama:lfm2.5:8b"), headers=AUTH).json()
    assert body["output"] == "from qwen3.5:4b"
    assert body["model"] == "ollama:qwen3.5:4b"  # what ran, not what was asked


def test_the_fallback_is_followed_as_a_chain_and_never_loops():
    chain = {"a:1": "b:1", "b:1": "c:1", "c:1": "a:1"}
    with app_with(fallback_ollama(pulled("c:1"), chain)) as client:
        assert client.post("/v1/completions", json=completion(model="ollama:a:1"), headers=AUTH) \
            .json()["model"] == "ollama:c:1"
    with app_with(fallback_ollama(pulled(), chain)) as client:
        response = client.post("/v1/completions", json=completion(model="ollama:a:1"), headers=AUTH)
    assert response.status_code == 400
    assert "ollama pull a:1" in response.json()["detail"]  # the error names the model that was asked for


def test_only_a_missing_model_falls_back_not_a_failing_one():
    calls = []

    def handler(request):
        calls.append(json.loads(request.content)["model"])
        return httpx.Response(500, json={"error": "runner crashed"})

    with app_with(fallback_ollama(handler, {"lfm2.5:8b": "qwen3.5:4b"})) as client:
        response = client.post("/v1/completions", json=completion(model="ollama:lfm2.5:8b"), headers=AUTH)
    assert response.status_code == 502
    assert calls == ["lfm2.5:8b"]


def test_the_fallbacks_come_from_the_environment_and_can_be_switched_off():
    assert Settings.from_environment({"AICOS_ENGINE_OLLAMA_FALLBACKS": "lfm2.5:8b=qwen3.5:4b, x:1 = y:2"}) \
        .ollama_fallbacks == {"lfm2.5:8b": "qwen3.5:4b", "x:1": "y:2"}
    assert Settings.from_environment({"AICOS_ENGINE_OLLAMA_FALLBACKS": ""}).ollama_fallbacks == {}
    assert Settings.from_environment({}).ollama_fallbacks == Settings().ollama_fallbacks


def test_an_ollama_timeout_is_a_timeout():
    def handler(request):
        raise httpx.ReadTimeout("slow")

    with app_with(ollama(handler)) as client:
        response = client.post("/v1/completions", json=completion(model="ollama:x"), headers=AUTH)
    assert response.status_code == 504
    assert response.json()["type"] == PROBLEM + "provider-timeout"


def test_an_ollama_that_answers_garbage_is_a_provider_error_not_a_500():
    handler = lambda r: httpx.Response(200, text="<html>proxy</html>")
    with app_with(ollama(handler)) as client:
        response = client.post("/v1/completions", json=completion(model="ollama:x"), headers=AUTH)
    assert response.status_code == 502
    assert response.json()["type"] == PROBLEM + "provider-error"


def test_ollama_lists_what_it_has_pulled():
    handler = lambda r: httpx.Response(200, json={"models": [{"name": "llama3.2:latest"}, {"name": "qwen3:8b"}]})
    with app_with(ollama(handler)) as client:
        ids = [m["id"] for m in client.get("/v1/models", headers=AUTH).json()["models"]]
    assert "ollama:llama3.2:latest" in ids and "ollama:qwen3:8b" in ids


# --- Anthropic --------------------------------------------------------------

MESSAGE = {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5",
           "content": [{"type": "text", "text": "Here is the plan."}],
           "stop_reason": "end_turn", "stop_sequence": None,
           "usage": {"input_tokens": 21, "output_tokens": 5}}


def anthropic_provider(handler, *, key="sk-test-not-a-real-key", models=("claude-opus-5",)):
    settings = Settings(profile="test", engine_token=TOKEN, ollama_url=None,
                        anthropic_api_key=key, anthropic_models=models)
    factory = lambda: anthropic.AsyncAnthropic(
        api_key=key, max_retries=0,
        http_client=anthropic.DefaultAsyncHttpxClient(transport=httpx2.MockTransport(handler)))
    return AnthropicProvider(settings, client_factory=factory)


def test_without_a_key_the_cloud_provider_is_off_and_says_how_to_turn_it_on():
    provider = AnthropicProvider(Settings(profile="test", engine_token=TOKEN, ollama_url=None))
    with app_with(provider) as client:
        response = client.post("/v1/completions", json=completion(model="anthropic:claude-opus-5"), headers=AUTH)
        models = client.get("/v1/models", headers=AUTH).json()["models"]
    assert response.status_code == 503
    assert response.json()["type"] == PROBLEM + "provider-unavailable"
    assert "ANTHROPIC_API_KEY" in response.json()["detail"]
    assert {"id": "anthropic:claude-opus-5", "provider": "anthropic", "available": False,
            "detail": "Set ANTHROPIC_API_KEY to enable; billed per token", "billed": True} in models


def test_the_default_engine_has_no_cloud_access_without_a_key():
    """The environment of this process may hold a key; the default Settings must not read it here."""
    settings = Settings(profile="test", engine_token=TOKEN, ollama_url=None)
    assert settings.anthropic_enabled is False


def test_anthropic_request_uses_the_messages_api_with_the_refusal_fallback():
    seen = {}

    def handler(request: httpx2.Request):
        seen["url"] = str(request.url)
        seen["headers"] = dict(request.headers)
        seen["body"] = json.loads(request.content)
        return httpx2.Response(200, json=MESSAGE)

    with app_with(anthropic_provider(handler)) as client:
        body = client.post("/v1/completions", json=completion("Plan it", model="anthropic:claude-opus-5",
                                                              system="You are the planner", max_tokens=800),
                           headers=AUTH).json()

    assert seen["url"].endswith("/v1/messages?beta=true") or seen["url"].endswith("/v1/messages")
    assert seen["headers"]["x-api-key"] == "sk-test-not-a-real-key"
    assert FALLBACK_BETA in seen["headers"]["anthropic-beta"]
    assert seen["body"]["model"] == "claude-opus-5"
    assert seen["body"]["fallbacks"] == "default"
    assert seen["body"]["max_tokens"] == 800
    assert seen["body"]["system"] == "You are the planner"
    assert seen["body"]["messages"] == [{"role": "user", "content": "Plan it"}]
    assert "thinking" not in seen["body"], "thinking stays at the model's default (adaptive on Opus 5)"

    assert body["output"] == "Here is the plan."
    assert body["model"] == "anthropic:claude-opus-5"
    assert body["finish_reason"] == "stop"
    assert body["usage"] == {"input_tokens": 21, "output_tokens": 5}


def test_models_without_the_default_fallback_do_not_send_it():
    seen = {}

    def handler(request):
        seen["body"] = json.loads(request.content)
        seen["beta"] = request.headers.get("anthropic-beta", "")
        return httpx2.Response(200, json={**MESSAGE, "model": "claude-haiku-4-5"})

    with app_with(anthropic_provider(handler, models=("claude-haiku-4-5",))) as client:
        client.post("/v1/completions", json=completion(model="anthropic:claude-haiku-4-5"), headers=AUTH)
    assert "fallbacks" not in seen["body"]
    assert FALLBACK_BETA not in seen["beta"]


def test_a_refusal_is_a_finish_reason_not_an_error():
    refusal = {**MESSAGE, "content": [], "stop_reason": "refusal"}
    with app_with(anthropic_provider(lambda r: httpx2.Response(200, json=refusal))) as client:
        body = client.post("/v1/completions", json=completion(model="anthropic:claude-opus-5"), headers=AUTH).json()
    assert body["finish_reason"] == "refusal"
    assert body["output"] == ""


def test_max_tokens_is_length():
    cut = {**MESSAGE, "stop_reason": "max_tokens"}
    with app_with(anthropic_provider(lambda r: httpx2.Response(200, json=cut))) as client:
        assert client.post("/v1/completions", json=completion(model="anthropic:claude-opus-5"),
                           headers=AUTH).json()["finish_reason"] == "length"


def test_a_model_not_configured_is_unknown_before_any_call():
    calls = []
    with app_with(anthropic_provider(lambda r: calls.append(r) or httpx2.Response(200, json=MESSAGE))) as client:
        response = client.post("/v1/completions", json=completion(model="anthropic:claude-imaginary"), headers=AUTH)
    assert response.status_code == 400
    assert response.json()["type"] == PROBLEM + "unknown-model"
    assert calls == []


def error(status, kind):
    return lambda r: httpx2.Response(status, json={"type": "error", "error": {"type": kind, "message": "x"}})


@pytest.mark.parametrize("handler,status,slug", [
    (error(401, "authentication_error"), 503, "provider-unavailable"),
    (error(429, "rate_limit_error"), 503, "provider-unavailable"),
    (error(404, "not_found_error"), 400, "unknown-model"),
    (error(400, "invalid_request_error"), 502, "provider-error"),
    (error(529, "overloaded_error"), 502, "provider-error"),
])
def test_every_anthropic_failure_is_one_of_the_provider_problems(handler, status, slug):
    with app_with(anthropic_provider(handler)) as client:
        response = client.post("/v1/completions", json=completion(model="anthropic:claude-opus-5"), headers=AUTH)
    assert response.status_code == status
    assert response.json()["type"] == PROBLEM + slug


def test_an_unreachable_anthropic_is_unavailable_and_a_slow_one_is_a_timeout():
    def refuse(request):
        raise httpx2.ConnectError("refused")

    def slow(request):
        raise httpx2.ReadTimeout("slow")

    with app_with(anthropic_provider(refuse)) as client:
        assert client.post("/v1/completions", json=completion(model="anthropic:claude-opus-5"),
                           headers=AUTH).json()["type"] == PROBLEM + "provider-unavailable"
    with app_with(anthropic_provider(slow)) as client:
        assert client.post("/v1/completions", json=completion(model="anthropic:claude-opus-5"),
                           headers=AUTH).json()["type"] == PROBLEM + "provider-timeout"


def test_nothing_the_provider_says_leaks_into_an_error_detail():
    leaky = lambda r: httpx2.Response(400, json={"type": "error",
                                                 "error": {"type": "invalid_request_error", "message": "SECRET-DETAIL"}})
    with app_with(anthropic_provider(leaky)) as client:
        response = client.post("/v1/completions", json=completion(model="anthropic:claude-opus-5"), headers=AUTH)
    assert "SECRET-DETAIL" not in response.text


def test_a_json_request_asks_ollama_for_its_json_format_and_a_plain_one_does_not():
    seen = []

    def handler(request: httpx.Request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"model": "m", "message": {"role": "assistant", "content": "{}"},
                                         "done_reason": "stop", "prompt_eval_count": 1, "eval_count": 1})

    with app_with(ollama(handler)) as client:
        client.post("/v1/completions", headers=AUTH, json=completion(model="ollama:m", response_format="json"))
        client.post("/v1/completions", headers=AUTH, json=completion(model="ollama:m"))

    assert seen[0]["format"] == "json"
    assert seen[0]["think"] is False
    assert "format" not in seen[1]
    # PHASE 29 (ADR-034 §3.6): a plain answer does not think either, by default --
    # on this CPU the thinking never reached the answer. AICOS_ENGINE_OLLAMA_THINK
    # turns it back on, or leaves it to the model.
    assert seen[1]["think"] is False


def test_a_schema_is_handed_to_ollama_as_its_format_for_constrained_decoding():
    seen = []

    def handler(request: httpx.Request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"model": "m", "message": {"role": "assistant", "content": "{}"},
                                         "done_reason": "stop", "prompt_eval_count": 1, "eval_count": 1})

    schema = {"type": "object", "properties": {"phases": {"type": "array", "minItems": 2}}, "required": ["phases"]}
    with app_with(ollama(handler)) as client:
        response = client.post("/v1/completions", headers=AUTH,
                               json=completion(model="ollama:m", response_format="json", response_schema=schema))

    assert response.status_code == 200
    assert seen[0]["format"] == schema
    assert seen[0]["think"] is False
