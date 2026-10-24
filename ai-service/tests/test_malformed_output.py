"""Malformed model output → one retry → a clean error (6.6d).

``parse_hint`` decides what a usable answer is (6.3c). These tests feed it the ways a
model's answer goes wrong, then follow each through the retry and out of the endpoint,
where the student-facing error must say nothing of what the model wrote.
"""

import json
import traceback
from collections.abc import Iterator

import httpx
import pytest
from fastapi.testclient import TestClient

from ai_service import api, config
from ai_service.api import get_provider
from ai_service.feedback import (
    MAX_ATTEMPTS,
    SAMPLE_REQUEST,
    generate_feedback,
    parse_hint,
)
from ai_service.llm import GeminiProvider, LlmBadResponse, LlmProvider
from ai_service.main import app
from ai_service.prompts import build_prompt
from ai_service.schemas import FeedbackResponse
from fakes import KEY, MODEL, ScriptedProvider, gemini_fixture

FENCE = "```"
GOOD = '{"hint": "Look at the operator."}'
NOT_A_HINT = "The model's answer is not a {hint} object"
BLANK = "The model's hint is blank"
# Every malformed answer below carries this, so a test can show it never comes back.
TRACE = "SECRET-OUTPUT"

MALFORMED: dict[str, str] = {
    "empty": "",
    "blank": " \n\t ",
    "prose instead of JSON": f"Look at the operator. {TRACE}",
    "prose before the JSON": f'Sure! {{"hint": "{TRACE}"}}',
    "prose after the JSON": f'{{"hint": "Look."}} {TRACE}',
    "python repr": f"{{'hint': '{TRACE}'}}",
    "trailing comma": f'{{"hint": "{TRACE}",}}',
    "cut off": f'{{"hint": "Look at the {TRACE}',
    "other key": f'{{"text": "{TRACE}"}}',
    "capitalised key": f'{{"Hint": "{TRACE}"}}',
    "extra key": f'{{"hint": "Look.", "solution": "{TRACE}"}}',
    "fallback key": f'{{"hint": "{TRACE}", "fallback": true}}',
    "duplicate key": f'{{"hint": "Look.", "hint": "{TRACE}"}}',
    "two objects": f'{{"hint": "Look."}}{{"hint": "{TRACE}"}}',
    "list": f'["{TRACE}"]',
    "hint is a list": f'{{"hint": ["{TRACE}"]}}',
    "hint is an object": f'{{"hint": {{"text": "{TRACE}"}}}}',
    "hint is a number": '{"hint": 42}',
    "hint is null": '{"hint": null}',
    "hint is NaN": '{"hint": NaN}',
    "empty hint": '{"hint": ""}',
    "blank hint": '{"hint": "  \\n "}',
    "zero-width hint": json.dumps({"hint": "​​"}),
    "no-break-space hint": json.dumps({"hint": "  "}),
    "control-character hint": json.dumps({"hint": "\u0000\u0007"}),
    "unclosed fence": f'{FENCE}json\n{{"hint": "{TRACE}"}}',
    "fence then text": f'{FENCE}json\n{{"hint": "Look."}}\n{FENCE}\n{TRACE}',
    "two fences": (
        f'{FENCE}json\n{GOOD}\n{FENCE}\n{FENCE}json\n{{"hint": "{TRACE}"}}\n{FENCE}'
    ),
    "tilde fence": f'~~~json\n{{"hint": "{TRACE}"}}\n~~~',
    "html page": f"<html><body>{TRACE}</body></html>",
    "byte order mark": f'﻿{{"hint": "{TRACE}"}}',
}

ACCEPTED: dict[str, tuple[str, str]] = {
    "plain": (GOOD, "Look at the operator."),
    "json fence": (f"{FENCE}json\n{GOOD}\n{FENCE}", "Look at the operator."),
    "JSON fence": (f"{FENCE}JSON\n{GOOD}\n{FENCE}", "Look at the operator."),
    "space before the tag": (f"{FENCE} json\n{GOOD}\n{FENCE}", "Look at the operator."),
    "windows line ends": (
        f"{FENCE}json\r\n{GOOD}\r\n{FENCE}",
        "Look at the operator.",
    ),
    "invisible characters inside text": (
        json.dumps({"hint": "Look​ at it."}),
        "Look​ at it.",
    ),
    "emoji": (json.dumps({"hint": "\U0001f914"}), "\U0001f914"),
    "punctuation only": (json.dumps({"hint": "..."}), "..."),
}


# ---- what parse_hint accepts and refuses ----


@pytest.mark.parametrize("name", sorted(MALFORMED))
def test_a_malformed_answer_is_refused_with_a_fixed_message(name: str) -> None:
    with pytest.raises(LlmBadResponse) as refused:
        parse_hint(MALFORMED[name])

    assert str(refused.value) in {NOT_A_HINT, BLANK}
    assert TRACE not in str(refused.value)
    # Nor in the traceback a log would print: the parsing error it replaces (pydantic
    # quotes the input) is not chained to it.
    assert TRACE not in "".join(traceback.format_exception(refused.value))


@pytest.mark.parametrize("name", sorted(ACCEPTED))
def test_a_well_formed_answer_is_read(name: str) -> None:
    answer, hint = ACCEPTED[name]

    assert parse_hint(answer) == hint


@pytest.mark.parametrize(
    ("name", "message"),
    [
        ("zero-width hint", BLANK),
        ("control-character hint", BLANK),
        ("duplicate key", NOT_A_HINT),
    ],
)
def test_the_three_answers_6_6d_now_refuses_say_why(name: str, message: str) -> None:
    # Before 6.6d, the first two were returned as hints the student would see as
    # nothing, and the third kept its second "hint".
    with pytest.raises(LlmBadResponse, match=message.replace("{", "\\{")):
        parse_hint(MALFORMED[name])


# ---- one retry, never more ----


@pytest.mark.parametrize("name", sorted(MALFORMED))
def test_one_malformed_answer_is_retried_once_with_the_same_prompt(name: str) -> None:
    provider = ScriptedProvider(MALFORMED[name], GOOD)

    response = generate_feedback(provider, SAMPLE_REQUEST)

    assert response == FeedbackResponse(hint="Look at the operator.", fallback=False)
    assert provider.prompts == [build_prompt(SAMPLE_REQUEST)] * 2


@pytest.mark.parametrize("name", sorted(MALFORMED))
def test_two_malformed_answers_end_in_a_clean_error_after_two_calls(
    name: str,
) -> None:
    provider = ScriptedProvider(MALFORMED[name], MALFORMED[name], GOOD)

    with pytest.raises(LlmBadResponse) as raised:
        generate_feedback(provider, SAMPLE_REQUEST)

    assert len(provider.prompts) == MAX_ATTEMPTS == 2
    assert str(raised.value) in {NOT_A_HINT, BLANK}
    assert TRACE not in str(raised.value)


# ---- the clean error at the endpoint ----


class Recording:
    """Stands in for get_provider: one ScriptedProvider, kept to look at after."""

    def __init__(self, *script: str) -> None:
        self.provider = ScriptedProvider(*script)

    def __call__(self) -> Iterator[LlmProvider]:
        try:
            yield self.provider
        finally:
            self.provider.close()


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()


@pytest.mark.parametrize(
    "first, second",
    [
        ("cut off", "prose instead of JSON"),
        ("zero-width hint", "zero-width hint"),
        ("duplicate key", "extra key"),
        ("html page", "two fences"),
    ],
)
def test_the_endpoint_answers_two_malformed_answers_with_a_clean_502(
    client: TestClient, test_token: str, first: str, second: str
) -> None:
    recording = Recording(MALFORMED[first], MALFORMED[second])
    app.dependency_overrides[get_provider] = recording
    body = SAMPLE_REQUEST.model_dump(mode="json") | {"code": "print('SECRET-CODE')\n"}

    response = client.post(
        "/feedback", json=body, headers={"Authorization": f"Bearer {test_token}"}
    )

    assert response.status_code == 502
    assert response.json() in ({"detail": NOT_A_HINT}, {"detail": BLANK})
    assert "SECRET" not in response.text
    assert len(recording.provider.prompts) == 2
    assert recording.provider.closed


def test_a_malformed_then_good_answer_is_a_200(
    client: TestClient, test_token: str
) -> None:
    recording = Recording(MALFORMED["cut off"], GOOD)
    app.dependency_overrides[get_provider] = recording

    response = client.post(
        "/feedback",
        json=SAMPLE_REQUEST.model_dump(mode="json"),
        headers={"Authorization": f"Bearer {test_token}"},
    )

    assert response.status_code == 200
    assert response.json() == {"hint": "Look at the operator.", "fallback": False}


# ---- Gemini itself answering badly, through the real provider ----


def gemini_answering(*bodies: tuple[int, bytes, str]) -> tuple[GeminiProvider, list]:
    """The real provider, whose Gemini answers each call with the next raw body."""
    script = list(bodies)
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        status, content, kind = script.pop(0)
        return httpx.Response(status, content=content, headers={"content-type": kind})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    return GeminiProvider(KEY, MODEL, client), seen


def as_gemini(text: str) -> tuple[int, bytes, str]:
    body = {"candidates": [{"content": {"role": "model", "parts": [{"text": text}]}}]}
    return 200, json.dumps(body).encode(), "application/json"


GOOD_BODY = (200, json.dumps(gemini_fixture("hint")[1]).encode(), "application/json")


@pytest.mark.parametrize(
    "first",
    [
        (200, f"<html>{TRACE}</html>".encode(), "text/html"),
        (200, b"", "application/json"),
        (200, f'{{"candidates": "{TRACE}"}}'.encode(), "application/json"),
        as_gemini(MALFORMED["cut off"]),
        as_gemini(MALFORMED["zero-width hint"]),
    ],
    ids=["html", "empty body", "odd shape", "cut-off hint", "invisible hint"],
)
def test_gemini_answering_badly_once_is_retried_through_the_real_provider(
    first: tuple[int, bytes, str],
) -> None:
    provider, seen = gemini_answering(first, GOOD_BODY)

    response = generate_feedback(provider, SAMPLE_REQUEST)

    assert response.fallback is False
    assert response.hint.startswith("Look again")
    assert len(seen) == 2
    assert seen[0].content == seen[1].content


def test_gemini_answering_badly_twice_is_a_clean_error_through_the_real_provider() -> (
    None
):
    provider, seen = gemini_answering(
        (200, f"<html>{TRACE}</html>".encode(), "text/html"),
        as_gemini(MALFORMED["prose instead of JSON"]),
    )

    with pytest.raises(LlmBadResponse) as raised:
        generate_feedback(provider, SAMPLE_REQUEST)

    assert str(raised.value) == NOT_A_HINT
    assert TRACE not in str(raised.value)
    assert len(seen) == 2


def test_the_endpoint_turns_the_real_providers_bad_answers_into_a_clean_502(
    client: TestClient, test_token: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("GEMINI_API_KEY", KEY)
    monkeypatch.setenv("GEMINI_MODEL", MODEL)
    config.get_settings.cache_clear()
    provider, seen = gemini_answering(
        as_gemini(MALFORMED["two objects"]), as_gemini(MALFORMED["cut off"])
    )
    monkeypatch.setattr(api, "gemini_from_settings", lambda settings: provider)

    response = client.post(
        "/feedback",
        json=SAMPLE_REQUEST.model_dump(mode="json"),
        headers={"Authorization": f"Bearer {test_token}"},
    )

    assert response.status_code == 502
    assert response.json() == {"detail": NOT_A_HINT}
    assert TRACE not in response.text
    assert len(seen) == 2
