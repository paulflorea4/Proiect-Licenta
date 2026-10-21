from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from ai_service import config
from ai_service.config import Settings, get_settings
from ai_service.main import app

ENV_EXAMPLE = Path(__file__).resolve().parents[1] / ".env.example"


def write_env(tmp_path: Path, text: str) -> Path:
    env = tmp_path / ".env"
    env.write_text(text, encoding="utf-8")
    return env


# ---- reading the variables ----


def test_reads_the_token_and_the_gemini_values_from_the_environment(
    monkeypatch: pytest.MonkeyPatch, test_token: str
) -> None:
    monkeypatch.setenv("GEMINI_API_KEY", "key-from-env")
    monkeypatch.setenv("GEMINI_MODEL", "model-from-env")

    settings = get_settings()

    assert settings.ai_service_token.get_secret_value() == test_token
    assert settings.gemini_api_key is not None
    assert settings.gemini_api_key.get_secret_value() == "key-from-env"
    assert settings.gemini_model == "model-from-env"


def test_reads_the_env_file(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.delenv("AI_SERVICE_TOKEN")
    env = write_env(
        tmp_path,
        "AI_SERVICE_TOKEN=token-from-file\n"
        "GEMINI_API_KEY=key-from-file\n"
        "GEMINI_MODEL=model-from-file\n",
    )
    monkeypatch.setattr(config, "ENV_FILE", env)

    settings = get_settings()

    assert settings.ai_service_token.get_secret_value() == "token-from-file"
    assert settings.gemini_api_key is not None
    assert settings.gemini_api_key.get_secret_value() == "key-from-file"
    assert settings.gemini_model == "model-from-file"


def test_a_real_environment_variable_wins_over_the_env_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, test_token: str
) -> None:
    env = write_env(tmp_path, "AI_SERVICE_TOKEN=token-from-file\n")
    monkeypatch.setattr(config, "ENV_FILE", env)

    assert get_settings().ai_service_token.get_secret_value() == test_token


def test_the_default_env_file_is_the_one_next_to_the_example() -> None:
    assert config.DEFAULT_ENV_FILE == ENV_EXAMPLE.with_name(".env")


def test_the_settings_are_exactly_the_variables_of_the_env_example() -> None:
    # 0.3b's .env.example is the list of what the service reads: nothing more (no
    # database settings), nothing less.
    names = {
        line.split("=", 1)[0].strip().lower()
        for line in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    }

    assert set(Settings.model_fields) == names
    assert names == {"ai_service_token", "gemini_api_key", "gemini_model"}


def test_unknown_variables_in_the_env_file_are_ignored(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    env = write_env(tmp_path, "DATABASE_URL=postgres://nowhere\nSOMETHING_ELSE=1\n")
    monkeypatch.setattr(config, "ENV_FILE", env)

    settings = get_settings()

    assert not hasattr(settings, "database_url")
    assert not hasattr(settings, "something_else")


# ---- the token is required ----


@pytest.mark.parametrize("value", [None, "", "   "], ids=["missing", "empty", "blank"])
def test_a_missing_or_empty_token_is_refused(
    monkeypatch: pytest.MonkeyPatch, value: str | None
) -> None:
    if value is None:
        monkeypatch.delenv("AI_SERVICE_TOKEN")
    else:
        monkeypatch.setenv("AI_SERVICE_TOKEN", value)

    with pytest.raises(ValidationError) as refused:
        get_settings()

    assert "ai_service_token" in str(refused.value)


def test_an_empty_token_in_the_env_file_is_refused_too(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # The file as .env.example ships it, copied without filling the token in.
    monkeypatch.delenv("AI_SERVICE_TOKEN")
    env = write_env(tmp_path, ENV_EXAMPLE.read_text(encoding="utf-8"))
    monkeypatch.setattr(config, "ENV_FILE", env)

    with pytest.raises(ValidationError):
        get_settings()


def test_the_service_does_not_start_without_a_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("AI_SERVICE_TOKEN")

    # Entering the client runs the app's startup, as uvicorn does.
    with pytest.raises(ValidationError), TestClient(app):
        pass


def test_the_service_starts_and_answers_with_a_token() -> None:
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200


# ---- the Gemini values may wait for 6.2a ----


@pytest.mark.parametrize("value", [None, "", "  "], ids=["missing", "empty", "blank"])
def test_the_gemini_values_are_optional_and_blank_means_unset(
    monkeypatch: pytest.MonkeyPatch, value: str | None
) -> None:
    if value is not None:
        monkeypatch.setenv("GEMINI_API_KEY", value)
        monkeypatch.setenv("GEMINI_MODEL", value)

    settings = get_settings()

    assert settings.gemini_api_key is None
    assert settings.gemini_model is None


# ---- secrets are never shown ----


def test_secrets_never_appear_when_the_settings_are_printed_or_logged(
    monkeypatch: pytest.MonkeyPatch, test_token: str
) -> None:
    monkeypatch.setenv("GEMINI_API_KEY", "gemini-secret-value")

    settings = get_settings()

    for shown in (str(settings), repr(settings), str(settings.model_dump())):
        assert test_token not in shown
        assert "gemini-secret-value" not in shown


def test_the_settings_are_read_once(monkeypatch: pytest.MonkeyPatch) -> None:
    first = get_settings()
    monkeypatch.setenv("AI_SERVICE_TOKEN", "changed-later")

    assert get_settings() is first
