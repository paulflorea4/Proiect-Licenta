from collections.abc import Iterator

import pytest

from ai_service import config

TEST_TOKEN = "test-token-for-the-ai-service"


@pytest.fixture(autouse=True)
def isolated_settings(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """Every test sees the same settings, whatever the developer's own
    ``ai-service/.env`` or shell holds: no env file, a test token, no Gemini values.
    A test changes them with ``monkeypatch`` and gets fresh settings, since the
    cache is cleared before and after it."""
    monkeypatch.setattr(config, "ENV_FILE", None)
    monkeypatch.setenv("AI_SERVICE_TOKEN", TEST_TOKEN)
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.delenv("GEMINI_MODEL", raising=False)
    config.get_settings.cache_clear()
    yield
    config.get_settings.cache_clear()


@pytest.fixture
def test_token() -> str:
    """The token every test starts with."""
    return TEST_TOKEN
