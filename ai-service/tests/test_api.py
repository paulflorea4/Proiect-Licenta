import json
from collections.abc import Iterator
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from ai_service import api, config
from ai_service.api import ERROR_STATUS, get_provider
from ai_service.auth import NOT_AUTHENTICATED
from ai_service.feedback import SAMPLE_REQUEST
from ai_service.leak_guard import SAFE_HINT
from ai_service.llm import (
    GEMINI_BASE_URL,
    LlmBadResponse,
    LlmNotConfigured,
    LlmProvider,
    LlmUnavailable,
    gemini_from_settings,
)
from ai_service.main import app
from ai_service.prompts import build_prompt
from ai_service.schemas import FeedbackRequest

GOOD = '{"hint": "Check which operator you used."}'
LEAK = json.dumps({"hint": "Write:\n```python\nprint(a + b)\n```"})
BODY: dict[str, Any] = SAMPLE_REQUEST.model_dump(mode="json")
TIMEOUT = LlmUnavailable("The Gemini request timed out")


class ScriptedProvider(LlmProvider):
    """Plays its script, one entry per call: a text to return or an error to raise."""

    def __init__(self, *script: str | Exception):
        self.script = list(script)
        self.prompts: list[str] = []
        self.closed = False

    def generate(self, prompt: str) -> str:
        self.prompts.append(prompt)
        step = self.script.pop(0)
        if isinstance(step, Exception):
            raise step
        return step

    def close(self) -> None:
        self.closed = True


class Scripted:
    """Stands in for get_provider: hands out one ScriptedProvider per request,
    closes it afterwards as the real dependency does, and counts the requests."""

    def __init__(self, *script: str | Exception):
        self.provider = ScriptedProvider(*script)
        self.built = 0

    def __call__(self) -> Iterator[LlmProvider]:
        self.built += 1
        try:
            yield self.provider
        finally:
            self.provider.close()


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()


def script(*steps: str | Exception) -> Scripted:
    scripted = Scripted(*steps)
    app.dependency_overrides[get_provider] = scripted
    return scripted


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def unavailable(status: int) -> LlmUnavailable:
    return LlmUnavailable(f"Gemini answered HTTP {status}", status_code=status)


# ---- the hint ----


@pytest.mark.parametrize("level", [1, 2, 3])
def test_a_request_gets_the_hint(
    client: TestClient, test_token: str, level: int
) -> None:
    scripted = script(GOOD)
    body = {**BODY, "hintLevel": level}

    response = client.post("/feedback", json=body, headers=bearer(test_token))

    assert response.status_code == 200
    assert response.json() == {
        "hint": "Check which operator you used.",
        "fallback": False,
    }
    assert scripted.provider.prompts == [
        build_prompt(FeedbackRequest.model_validate(body))
    ]
    assert scripted.provider.closed


def test_two_leaks_get_the_safe_hint_flagged_as_a_fallback(
    client: TestClient, test_token: str
) -> None:
    scripted = script(LEAK, LEAK)

    response = client.post("/feedback", json=BODY, headers=bearer(test_token))

    assert response.status_code == 200
    assert response.json() == {"hint": SAFE_HINT, "fallback": True}
    assert "a + b" not in response.text
    assert len(scripted.provider.prompts) == 2


@pytest.fixture
def gemini_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """Gemini settings for the real get_provider. The app read the settings at
    startup, so the cache is cleared for these to be seen."""
    monkeypatch.setenv("GEMINI_API_KEY", "fake-key")
    monkeypatch.setenv("GEMINI_MODEL", "some-model")
    config.get_settings.cache_clear()


def test_the_whole_loop_runs_against_a_faked_gemini(
    client: TestClient,
    test_token: str,
    monkeypatch: pytest.MonkeyPatch,
    gemini_env: None,
) -> None:
    # Only the network is faked: the real get_provider, settings, GeminiProvider,
    # prompt, parsing and guard all run.
    seen: list[httpx.Request] = []

    def gemini(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        text = '{"hint": "Look at the operator in your print statement."}'
        return httpx.Response(
            200, json={"candidates": [{"content": {"parts": [{"text": text}]}}]}
        )

    monkeypatch.setattr(
        api,
        "gemini_from_settings",
        lambda settings: gemini_from_settings(
            settings, httpx.Client(transport=httpx.MockTransport(gemini))
        ),
    )

    response = client.post("/feedback", json=BODY, headers=bearer(test_token))

    assert response.status_code == 200
    assert response.json() == {
        "hint": "Look at the operator in your print statement.",
        "fallback": False,
    }
    assert len(seen) == 1
    assert str(seen[0].url) == f"{GEMINI_BASE_URL}/models/some-model:generateContent"
    assert seen[0].headers["x-goog-api-key"] == "fake-key"
    sent = json.loads(seen[0].content)["contents"][0]["parts"][0]["text"]
    assert sent == build_prompt(SAMPLE_REQUEST)


# ---- the token comes first ----


@pytest.mark.parametrize(
    "headers",
    [{}, bearer("wrong"), {"Authorization": "Basic x"}],
    ids=["none", "wrong token", "other scheme"],
)
def test_without_the_token_nothing_is_read_or_built(
    client: TestClient, headers: dict[str, str]
) -> None:
    scripted = script(GOOD)

    for body in (BODY, {"not": "a request"}):
        response = client.post("/feedback", json=body, headers=headers)

        assert response.status_code == 401
        assert response.json() == {"detail": NOT_AUTHENTICATED}
    assert scripted.built == 0
    assert scripted.provider.prompts == []


# ---- a body that is not a request ----


@pytest.mark.parametrize(
    "body",
    [
        {**BODY, "hiddenTests": [{"passed": False, "input": "SECRET-HIDDEN-INPUT"}]},
        {**BODY, "hintLevel": 4, "code": "SECRET-STUDENT-CODE"},
        {**BODY, "code": "SECRET-STUDENT-CODE", "extra": "SECRET-EXTRA"},
        {k: v for k, v in BODY.items() if k != "code"},
        {**BODY, "hintLevel": "SECRET-LEVEL"},
    ],
    ids=["hidden input", "bad level", "unknown field", "missing code", "level text"],
)
def test_an_invalid_body_is_refused_without_echoing_it(
    client: TestClient, test_token: str, body: dict[str, Any]
) -> None:
    scripted = script(GOOD)

    response = client.post("/feedback", json=body, headers=bearer(test_token))

    assert response.status_code == 422
    assert "SECRET" not in response.text
    assert "input" not in {key for error in response.json()["detail"] for key in error}
    for error in response.json()["detail"]:
        assert set(error) == {"loc", "msg", "type"}
    assert scripted.provider.prompts == []


def test_a_refusal_still_says_which_field_is_wrong(
    client: TestClient, test_token: str
) -> None:
    script(GOOD)
    body = {**BODY, "hiddenTests": [{"passed": False, "input": "x"}]}

    response = client.post("/feedback", json=body, headers=bearer(test_token))

    assert response.json() == {
        "detail": [
            {
                "loc": ["body", "hiddenTests", 0, "input"],
                "msg": "Extra inputs are not permitted",
                "type": "extra_forbidden",
            }
        ]
    }


def test_a_body_that_is_not_json_is_refused(
    client: TestClient, test_token: str
) -> None:
    script(GOOD)

    response = client.post(
        "/feedback",
        content=b"SECRET {not json",
        headers={**bearer(test_token), "Content-Type": "application/json"},
    )

    assert response.status_code == 422
    assert "SECRET" not in response.text


# ---- the provider's failures ----


@pytest.mark.parametrize(
    ("steps", "status", "detail"),
    [
        (
            [unavailable(503), unavailable(503)],
            503,
            "Gemini answered HTTP 503",
        ),
        ([TIMEOUT], 503, "The Gemini request timed out"),
        ([unavailable(400)], 503, "Gemini answered HTTP 400"),
        (
            ["not json", "still not json"],
            502,
            "The model's answer is not a {hint} object",
        ),
        (
            [LlmBadResponse("Gemini's answer has no text")] * 2,
            502,
            "Gemini's answer has no text",
        ),
        ([LEAK, unavailable(503)], 503, "Gemini answered HTTP 503"),
    ],
    ids=["503 twice", "timeout", "400", "bad twice", "no text twice", "leak then 503"],
)
def test_a_provider_failure_is_a_clean_error(
    client: TestClient,
    test_token: str,
    steps: list[str | Exception],
    status: int,
    detail: str,
) -> None:
    scripted = script(*steps)
    body = {**BODY, "code": "print('SECRET-STUDENT-CODE')\n"}

    response = client.post("/feedback", json=body, headers=bearer(test_token))

    assert response.status_code == status
    assert response.json() == {"detail": detail}
    assert "SECRET" not in response.text
    assert scripted.provider.closed


def test_the_error_statuses_are_the_named_table() -> None:
    assert ERROR_STATUS == {
        LlmNotConfigured: 503,
        LlmUnavailable: 503,
        LlmBadResponse: 502,
    }


def test_without_gemini_settings_the_endpoint_says_it_is_not_configured(
    client: TestClient, test_token: str
) -> None:
    # The real get_provider; the conftest gives no Gemini key or model.
    response = client.post("/feedback", json=BODY, headers=bearer(test_token))

    assert response.status_code == 503
    assert response.json() == {
        "detail": "GEMINI_API_KEY and GEMINI_MODEL must both be set"
    }


def test_the_real_provider_is_closed_after_the_request(
    client: TestClient,
    test_token: str,
    monkeypatch: pytest.MonkeyPatch,
    gemini_env: None,
) -> None:
    clients: list[httpx.Client] = []

    def build(settings: Any) -> Any:
        clients.append(
            httpx.Client(
                transport=httpx.MockTransport(lambda _: httpx.Response(503, json={}))
            )
        )
        return gemini_from_settings(settings, clients[-1])

    monkeypatch.setattr(api, "gemini_from_settings", build)

    response = client.post("/feedback", json=BODY, headers=bearer(test_token))

    assert response.status_code == 503
    assert len(clients) == 1
    assert clients[0].is_closed
