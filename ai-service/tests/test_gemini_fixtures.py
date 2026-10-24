"""The whole feedback path against Gemini's own answers (6.6a): the real provider,
retries, guard and endpoint, with only the network faked from the fixtures in
``tests/fixtures/gemini``."""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from ai_service import api, config
from ai_service.feedback import SAMPLE_REQUEST, generate_feedback, parse_hint
from ai_service.leak_guard import SAFE_HINT, Leak, find_leak
from ai_service.llm import (
    LlmBadResponse,
    LlmUnavailable,
)
from ai_service.main import app
from ai_service.prompts import AFTER_LEAK, build_prompt
from ai_service.schemas import FeedbackRequest, FeedbackResponse
from fakes import (
    FIXTURES,
    KEY,
    fixture_names,
    gemini_fixture,
    gemini_playing,
    prompt_sent,
)

HINT = "Look again at which arithmetic operation your print statement performs."


def at_level(level: int) -> FeedbackRequest:
    return SAMPLE_REQUEST.model_copy(update={"hint_level": level})


# ---- the fixtures themselves ----


def test_every_fixture_is_listed_in_the_readme() -> None:
    readme = (FIXTURES / "README.md").read_text(encoding="utf-8")

    assert len(fixture_names()) == 12
    for name in fixture_names():
        assert f"| `{name}` |" in readme


@pytest.mark.parametrize("name", fixture_names())
def test_a_fixture_is_a_gemini_body_and_an_error_carries_its_own_status(
    name: str,
) -> None:
    status, body = gemini_fixture(name)

    if name.startswith("error_"):
        assert f"error_{status}_" in name
        assert set(body["error"]) >= {"code", "message", "status"}
        assert body["error"]["status"].lower() in name
    else:
        assert status == 200
        assert "candidates" in body or "promptFeedback" in body


# ---- the provider reads them ----


@pytest.mark.parametrize(
    "name", ["hint", "hint_split_in_parts"], ids=["one part", "two parts"]
)
def test_the_provider_returns_the_answers_text(name: str) -> None:
    provider, seen = gemini_playing(name)

    assert provider.generate("prompt") == f'{{"hint": "{HINT}"}}'
    assert seen[0].headers["x-goog-api-key"] == KEY


@pytest.mark.parametrize("name", ["safety_no_content", "prompt_blocked"])
def test_an_answer_without_text_is_a_bad_response(name: str) -> None:
    provider, _ = gemini_playing(name)

    with pytest.raises(LlmBadResponse):
        provider.generate("prompt")


@pytest.mark.parametrize(
    ("name", "message"),
    [
        ("error_400_invalid_argument", "Gemini answered HTTP 400 (INVALID_ARGUMENT)"),
        (
            "error_429_resource_exhausted",
            "Gemini answered HTTP 429 (RESOURCE_EXHAUSTED)",
        ),
        ("error_503_unavailable", "Gemini answered HTTP 503 (UNAVAILABLE)"),
    ],
)
def test_an_error_body_gives_the_status_and_its_word_only(
    name: str, message: str
) -> None:
    provider, _ = gemini_playing(name)

    with pytest.raises(LlmUnavailable) as raised:
        provider.generate("prompt")

    assert str(raised.value) == message
    assert raised.value.status_code == gemini_fixture(name)[0]
    # Google's own message (it can echo the request) is never passed on.
    assert gemini_fixture(name)[1]["error"]["message"] not in str(raised.value)


# ---- the feedback loop ----


@pytest.mark.parametrize(
    "first", ["hint", "hint_fenced", "hint_split_in_parts"], ids=str
)
@pytest.mark.parametrize("level", [1, 2, 3])
def test_a_good_answer_is_one_call(first: str, level: int) -> None:
    provider, seen = gemini_playing(first)

    response = generate_feedback(provider, at_level(level))

    assert response == FeedbackResponse(hint=HINT, fallback=False)
    assert [prompt_sent(r) for r in seen] == [build_prompt(at_level(level))]


@pytest.mark.parametrize(
    "first",
    [
        "cut_off_max_tokens",
        "safety_no_content",
        "prompt_blocked",
        "error_429_resource_exhausted",
        "error_503_unavailable",
    ],
)
def test_one_failure_worth_retrying_then_a_hint(first: str) -> None:
    provider, seen = gemini_playing(first, "hint")

    assert generate_feedback(provider, SAMPLE_REQUEST).hint == HINT
    assert len(seen) == 2
    assert prompt_sent(seen[0]) == prompt_sent(seen[1]) == build_prompt(SAMPLE_REQUEST)


def test_a_400_is_not_retried() -> None:
    provider, seen = gemini_playing("error_400_invalid_argument", "hint")

    with pytest.raises(LlmUnavailable):
        generate_feedback(provider, SAMPLE_REQUEST)
    assert len(seen) == 1


@pytest.mark.parametrize(
    ("steps", "raised"),
    [
        (["error_429_resource_exhausted"] * 2, LlmUnavailable),
        (["error_503_unavailable", "cut_off_max_tokens"], LlmBadResponse),
        (["prompt_blocked"] * 2, LlmBadResponse),
    ],
    ids=["429 twice", "503 then cut off", "blocked twice"],
)
def test_two_failures_raise_the_last(steps: list[str], raised: type[Exception]) -> None:
    provider, seen = gemini_playing(*steps)

    with pytest.raises(raised):
        generate_feedback(provider, SAMPLE_REQUEST)
    assert len(seen) == 2


@pytest.mark.parametrize(
    ("name", "reason"),
    [
        ("leak_edited_line", Leak.EDITED_STUDENT_LINE),
        ("leak_complete_program", Leak.COMPLETE_PROGRAM),
    ],
)
def test_the_leak_fixtures_leak_at_level_3(name: str, reason: Leak) -> None:
    provider, _ = gemini_playing(name)
    hint = provider.generate("prompt")

    assert find_leak(parse_hint(hint), at_level(3)) is reason


@pytest.mark.parametrize("leak", ["leak_edited_line", "leak_complete_program"])
@pytest.mark.parametrize("level", [1, 2, 3])
def test_a_leak_is_regenerated_after_leak(leak: str, level: int) -> None:
    provider, seen = gemini_playing(leak, "hint")

    assert generate_feedback(provider, at_level(level)).hint == HINT
    assert prompt_sent(seen[0]) == build_prompt(at_level(level))
    assert prompt_sent(seen[1]) == build_prompt(at_level(level), after_leak=True)
    assert AFTER_LEAK in prompt_sent(seen[1])


def test_two_leaks_give_the_safe_hint() -> None:
    provider, _ = gemini_playing("leak_edited_line", "leak_complete_program")

    assert generate_feedback(provider, at_level(3)) == FeedbackResponse(
        hint=SAFE_HINT, fallback=True
    )


def test_a_level_3_snippet_on_another_example_is_kept() -> None:
    provider, seen = gemini_playing("hint_level3_snippet")

    response = generate_feedback(provider, at_level(3))

    assert response.fallback is False
    assert "total = total + x" in response.hint
    assert len(seen) == 1


# ---- through the endpoint ----


@pytest.fixture
def through_http(
    monkeypatch: pytest.MonkeyPatch, test_token: str
) -> Iterator[tuple[TestClient, dict[str, str]]]:
    """The app with Gemini configured; each test sets the script with ``play``."""
    monkeypatch.setenv("GEMINI_API_KEY", KEY)
    monkeypatch.setenv("GEMINI_MODEL", "fixture-model")
    config.get_settings.cache_clear()
    with TestClient(app) as client:
        yield client, {"Authorization": f"Bearer {test_token}"}


def play(monkeypatch: pytest.MonkeyPatch, *names: str) -> None:
    provider, _ = gemini_playing(*names)
    monkeypatch.setattr(api, "gemini_from_settings", lambda settings: provider)


@pytest.mark.parametrize(
    ("steps", "status", "body"),
    [
        (["hint"], 200, {"hint": HINT, "fallback": False}),
        (["error_503_unavailable", "hint"], 200, {"hint": HINT, "fallback": False}),
        (
            ["leak_edited_line", "leak_edited_line"],
            200,
            {"hint": SAFE_HINT, "fallback": True},
        ),
        (
            ["error_429_resource_exhausted"] * 2,
            503,
            {"detail": "Gemini answered HTTP 429 (RESOURCE_EXHAUSTED)"},
        ),
        (
            ["cut_off_max_tokens"] * 2,
            502,
            {"detail": "The model's answer is not a {hint} object"},
        ),
    ],
    ids=["hint", "503 then hint", "two leaks", "429 twice", "cut off twice"],
)
def test_the_endpoint_answers_from_gemini_s_own_bodies(
    through_http: tuple[TestClient, dict[str, str]],
    monkeypatch: pytest.MonkeyPatch,
    steps: list[str],
    status: int,
    body: dict[str, object],
) -> None:
    client, headers = through_http
    play(monkeypatch, *steps)

    response = client.post(
        "/feedback", json=SAMPLE_REQUEST.model_dump(mode="json"), headers=headers
    )

    assert response.status_code == status
    assert response.json() == body
