"""The v1 contract: shape, errors, authentication, correlation (ADR-015)."""
import pytest

from app.config import ConfigurationError, Settings
from tests.conftest import AUTH, TOKEN, completion

PROBLEM = "urn:ai-company-os:engine:problem:"


# --- authentication between services -----------------------------------------

@pytest.mark.parametrize("method,path", [("GET", "/v1/models"), ("POST", "/v1/completions")])
def test_every_v1_route_refuses_a_caller_without_the_token(client, method, path):
    response = client.request(method, path, json=completion())
    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.headers["WWW-Authenticate"] == "Bearer"
    assert response.json()["type"] == PROBLEM + "unauthenticated"


def test_a_wrong_token_and_no_token_get_the_same_answer(client):
    missing = client.post("/v1/completions", json=completion())
    wrong = client.post("/v1/completions", json=completion(), headers={"Authorization": "Bearer nope-nope-nope-nope"})
    assert missing.status_code == wrong.status_code == 401
    assert missing.json() == wrong.json()


def test_the_right_token_under_another_scheme_is_not_a_credential(client):
    response = client.post("/v1/completions", json=completion(), headers={"Authorization": f"Basic {TOKEN}"})
    assert response.status_code == 401


def test_the_scheme_is_case_insensitive(client):
    response = client.post("/v1/completions", json=completion(), headers={"Authorization": f"bearer {TOKEN}"})
    assert response.status_code == 200


def test_authentication_comes_before_validation(client):
    """An anonymous caller must not learn the request schema from 400s."""
    response = client.post("/v1/completions", json={"nonsense": True})
    assert response.status_code == 401


def test_health_is_public_and_says_nothing_else(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_no_documentation_endpoint_is_exposed(client):
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404


# --- the completion ---------------------------------------------------------

def test_a_completion_has_exactly_the_contract_shape(client):
    response = client.post("/v1/completions", json=completion("Write the plan", system="You are the planner"),
                           headers=AUTH)
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"id", "model", "provider", "output", "finish_reason", "usage", "latency_ms",
                         "correlation_id"}
    assert body["id"].startswith("cmpl_")
    assert body["model"] == "echo:default"
    assert body["provider"] == "echo"
    assert body["finish_reason"] == "stop"
    assert set(body["usage"]) == {"input_tokens", "output_tokens"}
    assert "Write the plan" in body["output"]
    assert "You are the planner" in body["output"]


def test_the_echo_says_it_is_an_echo(client):
    """It must never be mistaken for a model's answer."""
    output = client.post("/v1/completions", json=completion(), headers=AUTH).json()["output"]
    assert "not by a model" in output


def test_the_echo_is_deterministic(client):
    first = client.post("/v1/completions", json=completion("same"), headers=AUTH).json()
    second = client.post("/v1/completions", json=completion("same"), headers=AUTH).json()
    assert first["output"] == second["output"]
    assert first["usage"] == second["usage"]
    assert first["id"] != second["id"]


def test_max_tokens_truncates_and_says_so(client):
    body = client.post("/v1/completions", json=completion("x " * 50, max_tokens=5), headers=AUTH).json()
    assert body["finish_reason"] == "length"
    assert len(body["output"].split()) == 5


def test_no_model_means_the_configured_default(client):
    assert client.post("/v1/completions", json=completion(), headers=AUTH).json()["model"] == "echo:default"


def test_a_bare_provider_name_means_its_default_model(client):
    assert client.post("/v1/completions", json=completion(model="echo"), headers=AUTH).json()["model"] \
        == "echo:default"


# --- errors -----------------------------------------------------------------

def test_an_unknown_provider_is_a_problem_that_names_the_field(client):
    response = client.post("/v1/completions", json=completion(model="nobody:x"), headers=AUTH)
    assert response.status_code == 400
    body = response.json()
    assert body["type"] == PROBLEM + "unknown-model"
    assert "model" in body["errors"]


def test_an_unknown_model_of_a_known_provider_is_the_same_problem(client):
    response = client.post("/v1/completions", json=completion(model="echo:gpt"), headers=AUTH)
    assert response.status_code == 400
    assert response.json()["type"] == PROBLEM + "unknown-model"


@pytest.mark.parametrize("body,field", [
    ({"messages": []}, "messages"),
    ({"messages": [{"role": "user", "content": ""}]}, "messages.0.content"),
    ({"messages": [{"role": "system", "content": "x"}]}, "messages.0.role"),
    ({"messages": [{"role": "user", "content": "x"}], "max_tokens": 0}, "max_tokens"),
    ({"messages": [{"role": "user", "content": "x"}], "temperature": 2}, "temperature"),
])
def test_validation_failures_are_400_with_the_offending_field(client, body, field):
    response = client.post("/v1/completions", json=body, headers=AUTH)
    assert response.status_code == 400, response.text
    problem = response.json()
    assert problem["type"] == PROBLEM + "validation-failed"
    assert field in problem["errors"], problem


def test_an_unknown_path_is_inside_the_contract(client):
    response = client.get("/v1/nothing", headers=AUTH)
    assert response.status_code == 404
    assert response.json()["type"] == PROBLEM + "resource-not-found"


# --- correlation --------------------------------------------------------------

def test_the_correlation_id_is_echoed_when_given(client):
    response = client.post("/v1/completions", json=completion(), headers={**AUTH, "X-Correlation-Id": "run-42"})
    assert response.headers["X-Correlation-Id"] == "run-42"
    assert response.json()["correlation_id"] == "run-42"


def test_a_correlation_id_is_generated_when_absent_or_unsafe(client):
    generated = client.post("/v1/completions", json=completion(), headers=AUTH)
    assert len(generated.headers["X-Correlation-Id"]) == 32
    unsafe = client.post("/v1/completions", json=completion(), headers={**AUTH, "X-Correlation-Id": "a\nb"})
    assert unsafe.headers["X-Correlation-Id"] != "a\nb"


def test_errors_carry_the_correlation_id_too(client):
    response = client.post("/v1/completions", json=completion(model="nobody:x"),
                           headers={**AUTH, "X-Correlation-Id": "run-43"})
    assert response.headers["X-Correlation-Id"] == "run-43"


# --- models -----------------------------------------------------------------

def test_the_model_list_names_the_default_and_the_echo(client):
    body = client.get("/v1/models", headers=AUTH).json()
    assert body["default"] == "echo:default"
    assert {"id": "echo:default", "provider": "echo", "available": True,
            "detail": "Deterministic echo; not a language model", "billed": False} in body["models"]


# --- configuration fails closed ---------------------------------------------

def test_outside_dev_there_is_no_default_token():
    with pytest.raises(ConfigurationError):
        Settings.from_environment({"AICOS_ENGINE_PROFILE": "prod"})


def test_a_short_token_stops_the_engine():
    with pytest.raises(ConfigurationError):
        Settings.from_environment({"AICOS_ENGINE_TOKEN": "short"})


def test_dev_has_a_default_token_that_says_change_me():
    assert "change-me" in Settings.from_environment({}).engine_token


def test_a_default_model_without_a_provider_stops_the_engine():
    with pytest.raises(ConfigurationError):
        Settings.from_environment({"AICOS_ENGINE_DEFAULT_MODEL": "llama3"})
