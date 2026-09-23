import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.providers.echo import EchoProvider

TOKEN = "test-engine-token-0123456789"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture
def settings() -> Settings:
    return Settings(profile="test", engine_token=TOKEN, ollama_url=None)


@pytest.fixture
def client(settings: Settings):
    with TestClient(create_app(settings, providers=[EchoProvider()])) as test_client:
        yield test_client


def completion(content: str = "Hello", **extra) -> dict:
    body = {"messages": [{"role": "user", "content": content}]}
    body.update(extra)
    return body
