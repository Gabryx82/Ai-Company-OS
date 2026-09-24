"""The local secrets file (ADR-024 §6): read, never required, overridden by the environment."""
from app.config import local_secrets


def test_key_value_lines_are_read_and_comments_skipped(tmp_path):
    file = tmp_path / "local.env"
    file.write_text("# generated\nAICOS_ENGINE_TOKEN = abc123abc123abc123\n\nBROKEN LINE\nX=a=b\n", encoding="utf-8")
    assert local_secrets(file) == {"AICOS_ENGINE_TOKEN": "abc123abc123abc123", "X": "a=b"}


def test_a_missing_file_is_no_secrets_not_an_error(tmp_path):
    assert local_secrets(tmp_path / "absent.env") == {}
