import json
import os
import re
from collections.abc import Callable
from pathlib import Path

import httpx
import pytest
from pydantic import SecretStr

from ai_service import config, llm
from ai_service.config import Settings
from ai_service.llm import (
    CONNECT_TIMEOUT_SECONDS,
    GEMINI_BASE_URL,
    READ_TIMEOUT_SECONDS,
    GeminiProvider,
    LlmBadResponse,
    LlmError,
    LlmNotConfigured,
    LlmProvider,
    LlmUnavailable,
    gemini_from_settings,
)

KEY = "fake-gemini-key-for-tests"
MODEL = "some-model-1.5"
SRC = Path(__file__).resolve().parents[1] / "src"


def answer(*texts: str) -> dict[str, object]:
    """A Gemini answer with one candidate whose parts hold these texts."""
    return {
        "candidates": [
            {
                "content": {"role": "model", "parts": [{"text": t} for t in texts]},
                "finishReason": "STOP",
            }
        ]
    }


def provider(
    handler: Callable[[httpx.Request], httpx.Response],
) -> GeminiProvider:
    return GeminiProvider(
        KEY, MODEL, httpx.Client(transport=httpx.MockTransport(handler))
    )


def replying(
    status: int = 200, body: object = None, *, raw: bytes | None = None
) -> tuple[GeminiProvider, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if raw is not None:
            return httpx.Response(status, content=raw)
        return httpx.Response(status, json=answer("hello") if body is None else body)

    return provider(handler), seen


def assert_says_nothing_secret(error: LlmError, prompt: str = "") -> None:
    shown = f"{error} {error!r}"
    assert KEY not in shown
    if prompt:
        assert prompt not in shown


# ---- the request ----


def test_the_request_is_one_post_to_the_models_generate_content() -> None:
    gemini, seen = replying()

    gemini.generate("Say hello.")

    assert len(seen) == 1
    request = seen[0]
    assert request.method == "POST"
    assert str(request.url) == f"{GEMINI_BASE_URL}/models/{MODEL}:generateContent"
    assert request.headers["content-type"] == "application/json"
    assert json.loads(request.content) == {
        "contents": [{"role": "user", "parts": [{"text": "Say hello."}]}]
    }


def test_the_key_is_a_header_and_never_in_the_url() -> None:
    gemini, seen = replying()

    gemini.generate("Say hello.")

    assert seen[0].headers["x-goog-api-key"] == KEY
    assert KEY not in str(seen[0].url)
    assert "key=" not in str(seen[0].url)


def test_the_prompt_is_sent_exactly_as_given() -> None:
    gemini, seen = replying()
    prompt = 'Line 1\n  "quoted" \\ back\\slash\t tab, ünïcödé, emoji 🙂, </tags>'

    gemini.generate(prompt)

    assert json.loads(seen[0].content)["contents"][0]["parts"][0]["text"] == prompt


def test_the_timeouts_are_the_named_constants() -> None:
    gemini = GeminiProvider(KEY, MODEL)
    try:
        timeout = gemini._client.timeout
        assert timeout.connect == CONNECT_TIMEOUT_SECONDS
        assert timeout.read == READ_TIMEOUT_SECONDS
        # A call can never wait forever: every phase of the request is bounded.
        for phase in (timeout.connect, timeout.read, timeout.write, timeout.pool):
            assert phase is not None and phase > 0
    finally:
        gemini.close()


# ---- the answer ----


def test_the_text_of_the_answer_is_returned() -> None:
    gemini, _ = replying(body=answer("Hello there."))

    assert gemini.generate("Say hello.") == "Hello there."


def test_the_parts_of_the_first_candidate_are_joined_and_others_ignored() -> None:
    body = answer("Hello ", "there.")
    body["candidates"].append(answer("ignored")["candidates"][0])  # type: ignore[attr-defined]
    gemini, _ = replying(body=body)

    assert gemini.generate("Say hello.") == "Hello there."


def test_the_provider_is_an_llm_provider() -> None:
    gemini, _ = replying()

    assert isinstance(gemini, LlmProvider)
    with pytest.raises(TypeError):
        LlmProvider()  # type: ignore[abstract]


# ---- no usable answer ----


@pytest.mark.parametrize("status", [400, 401, 403, 404, 429, 500, 503])
def test_an_http_error_status_is_unavailable_and_shows_nothing_of_the_answer(
    status: int,
) -> None:
    body = {"error": {"code": status, "message": "details from Google, maybe echoing"}}
    gemini, _ = replying(status, body)

    with pytest.raises(LlmUnavailable) as raised:
        gemini.generate("secret student code")

    assert str(raised.value) == f"Gemini answered HTTP {status}"
    assert "details from Google" not in str(raised.value)
    assert_says_nothing_secret(raised.value, "secret student code")


@pytest.mark.parametrize(
    ("failure", "message"),
    [
        (httpx.ConnectTimeout("connect timed out"), "The Gemini request timed out"),
        (httpx.ReadTimeout("read timed out"), "The Gemini request timed out"),
        (httpx.ConnectError("connection refused"), "The Gemini request failed"),
        (httpx.RemoteProtocolError("server hung up"), "The Gemini request failed"),
    ],
    ids=["connect timeout", "read timeout", "connect error", "protocol error"],
)
def test_a_failed_request_is_unavailable(failure: Exception, message: str) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise failure

    with pytest.raises(LlmUnavailable) as raised:
        provider(handler).generate("secret student code")

    assert str(raised.value) == message
    assert_says_nothing_secret(raised.value, "secret student code")


@pytest.mark.parametrize(
    "body",
    [
        {"promptFeedback": {"blockReason": "SAFETY"}},
        {"promptFeedback": {"blockReason": "OTHER"}, "candidates": []},
        {},
        {"candidates": []},
        {"candidates": [{"finishReason": "SAFETY"}]},
        {"candidates": [{"content": {"parts": []}}]},
        {"candidates": [{"content": {"parts": [{"text": ""}]}}]},
        {"candidates": [{"content": {"parts": [{"text": "  \n "}]}}]},
        {"candidates": [{"content": {"parts": [{"inlineData": {}}]}}]},
        {"candidates": ["not an object"]},
        {"candidates": "not a list"},
        ["not", "an", "object"],
    ],
    ids=[
        "prompt blocked",
        "prompt blocked, empty candidates",
        "nothing",
        "no candidate",
        "candidate without content",
        "no parts",
        "empty text",
        "blank text",
        "no text part",
        "candidate not an object",
        "candidates not a list",
        "not an object",
    ],
)
def test_an_answer_without_usable_text_is_a_bad_response(body: object) -> None:
    gemini, _ = replying(body=body)

    with pytest.raises(LlmBadResponse) as raised:
        gemini.generate("Say hello.")

    assert_says_nothing_secret(raised.value)


def test_an_answer_that_is_not_json_is_a_bad_response() -> None:
    gemini, _ = replying(raw=b"<html>not json</html>")

    with pytest.raises(LlmBadResponse):
        gemini.generate("Say hello.")


# ---- configuration ----


def settings(key: str | None, model: str | None) -> Settings:
    return Settings(
        _env_file=None,
        ai_service_token=SecretStr("token"),
        gemini_api_key=None if key is None else SecretStr(key),
        gemini_model=model,
    )


@pytest.mark.parametrize(("key", "model"), [(None, MODEL), (KEY, None), (None, None)])
def test_the_provider_needs_both_the_key_and_the_model(
    key: str | None, model: str | None
) -> None:
    with pytest.raises(LlmNotConfigured) as raised:
        gemini_from_settings(settings(key, model))

    assert "GEMINI_API_KEY" in str(raised.value)
    assert_says_nothing_secret(raised.value)


def test_the_provider_uses_the_configured_model_and_key() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=answer("ok"))

    gemini = gemini_from_settings(
        settings(KEY, "configured-model"),
        httpx.Client(transport=httpx.MockTransport(handler)),
    )
    gemini.generate("hi")

    assert str(seen[0].url).endswith("/models/configured-model:generateContent")
    assert seen[0].headers["x-goog-api-key"] == KEY


@pytest.mark.parametrize(
    "model",
    ["", "models/x", "x?key=1", "x y", "../x", "x#y", "-x", "x:generateContent"],
)
def test_a_model_name_that_is_not_a_plain_name_is_refused(model: str) -> None:
    with pytest.raises(LlmNotConfigured):
        GeminiProvider(KEY, model)


def test_an_empty_key_is_refused() -> None:
    with pytest.raises(LlmNotConfigured):
        GeminiProvider("", MODEL)


def test_the_key_never_shows_in_the_providers_repr() -> None:
    gemini = GeminiProvider(KEY, MODEL)
    try:
        assert KEY not in repr(gemini)
        assert KEY not in str(gemini)
    finally:
        gemini.close()


def test_no_model_name_is_written_in_the_code() -> None:
    # The model comes from GEMINI_MODEL only (root CLAUDE.md): no literal such as
    # "gemini-2.0-flash" anywhere in the service's source.
    found = [
        f"{path.relative_to(SRC)}: {match.group(0)}"
        for path in SRC.rglob("*.py")
        for match in re.finditer(
            r"gemini-[0-9]|gemini-pro|gemini-flash", path.read_text(encoding="utf-8")
        )
    ]

    assert found == []


# ---- the real API (opt-in) ----

live = pytest.mark.skipif(
    os.environ.get("RUN_LIVE_GEMINI") != "1",
    reason="calls the real Gemini API; set RUN_LIVE_GEMINI=1 to run it",
)


@live
def test_the_real_gemini_api_answers_a_prompt() -> None:
    # Reads the developer's own ai-service/.env (the fixture's settings have no key).
    real = Settings(_env_file=config.DEFAULT_ENV_FILE)
    gemini = llm.gemini_from_settings(real)
    try:
        text = gemini.generate("Reply with exactly one word: pong")
    finally:
        gemini.close()

    print(f"\nlive Gemini call: {len(text)} characters of text")
    assert text.strip()


# ---- 6.3c: what an error says about itself ----


@pytest.mark.parametrize(
    ("status", "word"),
    [(503, "UNAVAILABLE"), (429, "RESOURCE_EXHAUSTED"), (500, "INTERNAL")],
)
def test_an_error_status_carries_geminis_status_word(status: int, word: str) -> None:
    body = {
        "error": {"code": status, "message": "details, maybe echoing", "status": word}
    }
    gemini, _ = replying(status, body)

    with pytest.raises(LlmUnavailable) as raised:
        gemini.generate("secret student code")

    assert str(raised.value) == f"Gemini answered HTTP {status} ({word})"
    assert raised.value.status_code == status
    assert "details" not in str(raised.value)


@pytest.mark.parametrize(
    "status_field",
    [
        "unavailable",
        "UNAVAILABLE please retry",
        "UNAVAILABLE\nsecret student code",
        "A" * 41,
        42,
        None,
        ["UNAVAILABLE"],
    ],
    ids=["lower case", "words", "newline", "too long", "number", "null", "list"],
)
def test_a_status_that_is_not_one_plain_word_is_left_out(status_field: object) -> None:
    gemini, _ = replying(503, {"error": {"code": 503, "status": status_field}})

    with pytest.raises(LlmUnavailable) as raised:
        gemini.generate("secret student code")

    assert str(raised.value) == "Gemini answered HTTP 503"
    assert_says_nothing_secret(raised.value, "secret student code")


@pytest.mark.parametrize(
    "raw", [b"", b"<html>Service Unavailable</html>", b'{"error": "UNAVAILABLE"}']
)
def test_an_error_body_without_a_status_gives_the_code_alone(raw: bytes) -> None:
    gemini, _ = replying(503, raw=raw)

    with pytest.raises(LlmUnavailable) as raised:
        gemini.generate("Say hello.")

    assert str(raised.value) == "Gemini answered HTTP 503"
    assert raised.value.status_code == 503


def test_a_timeout_has_no_status() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("read timed out")

    with pytest.raises(LlmUnavailable) as raised:
        provider(handler).generate("Say hello.")

    assert raised.value.status_code is None


def test_a_connection_failure_has_no_status() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    with pytest.raises(LlmUnavailable) as raised:
        provider(handler).generate("Say hello.")

    assert raised.value.status_code is None
