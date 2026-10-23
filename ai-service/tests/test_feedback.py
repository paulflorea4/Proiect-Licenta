import json
import os
import subprocess
import sys

import pytest

from ai_service import config, feedback
from ai_service.config import Settings
from ai_service.feedback import (
    MAX_ATTEMPTS,
    RETRYABLE_STATUS_CODES,
    SAMPLE_REQUEST,
    generate_feedback,
    main,
    parse_hint,
)
from ai_service.leak_guard import SAFE_HINT
from ai_service.llm import (
    LlmBadResponse,
    LlmError,
    LlmNotConfigured,
    LlmProvider,
    LlmUnavailable,
    gemini_from_settings,
)
from ai_service.prompts import build_prompt
from ai_service.schemas import FeedbackRequest, FeedbackResponse

GOOD = '{"hint": "Check which operator you used."}'
HINT = FeedbackResponse(hint="Check which operator you used.", fallback=False)
SAFE = FeedbackResponse(hint=SAFE_HINT, fallback=True)

# The sample's fix itself, `print(a * b)` edited: a leak at every level.
LEAK = json.dumps({"hint": "Write:\n```python\nprint(a + b)\n```"})


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


def at_level(level: int) -> FeedbackRequest:
    return SAMPLE_REQUEST.model_copy(update={"hint_level": level})


def unavailable(status: int) -> LlmUnavailable:
    return LlmUnavailable(f"Gemini answered HTTP {status}", status_code=status)


TIMEOUT = LlmUnavailable("The Gemini request timed out")


# ---- reading the answer ----


@pytest.mark.parametrize(
    ("answer", "hint"),
    [
        ('{"hint": "Look at the loop."}', "Look at the loop."),
        ('  \n{"hint": "  Look at the loop.  "}\n ', "Look at the loop."),
        ('```json\n{"hint": "Look at the loop."}\n```', "Look at the loop."),
        ('```\n{"hint": "Look at the loop."}\n```', "Look at the loop."),
        (
            '{"hint": "Line one.\\n\\n```python\\nx = 1\\n```"}',
            "Line one.\n\n```python\nx = 1\n```",
        ),
    ],
    ids=[
        "plain",
        "surrounding blanks",
        "json fence",
        "bare fence",
        "code inside the hint",
    ],
)
def test_a_hint_object_is_read(answer: str, hint: str) -> None:
    assert parse_hint(answer) == hint


@pytest.mark.parametrize(
    "answer",
    [
        "",
        "   ",
        "Look at the loop.",
        '{"hint": ""}',
        '{"hint": "   "}',
        '{"hint": 42}',
        '{"hint": null}',
        '{"text": "Look at the loop."}',
        '{"hint": "Look.", "solution": "print(a + b)"}',
        '["Look at the loop."]',
        '"Look at the loop."',
        '{"hint": "Look at the loop."',
        'Here you go: {"hint": "Look at the loop."}',
        '```json\n{"hint": "Look at the loop."}',
    ],
    ids=[
        "empty",
        "blank",
        "plain text",
        "empty hint",
        "blank hint",
        "number",
        "null",
        "other key",
        "extra key",
        "list",
        "string",
        "cut off",
        "text around",
        "unclosed fence",
    ],
)
def test_anything_else_is_a_bad_response_that_does_not_quote_it(answer: str) -> None:
    with pytest.raises(LlmBadResponse) as raised:
        parse_hint(answer)

    assert "Look at the loop" not in str(raised.value)
    assert "print(a + b)" not in str(raised.value)


# ---- one more try, and only one ----


@pytest.mark.parametrize("level", [1, 2, 3])
def test_a_good_first_answer_is_one_call(level: int) -> None:
    provider = ScriptedProvider(GOOD)

    assert generate_feedback(provider, at_level(level)) == HINT
    assert provider.prompts == [build_prompt(at_level(level))]


@pytest.mark.parametrize(
    "first",
    [
        "not json",
        '{"hint": ""}',
        LlmBadResponse("Gemini's answer has no text"),
        LlmBadResponse("Gemini blocked the prompt"),
        *[unavailable(status) for status in sorted(RETRYABLE_STATUS_CODES)],
    ],
    ids=lambda first: str(first)[:40],
)
def test_one_failure_worth_retrying_is_retried_with_the_same_prompt(
    first: str | Exception,
) -> None:
    provider = ScriptedProvider(first, GOOD)

    assert generate_feedback(provider, SAMPLE_REQUEST) == HINT
    assert len(provider.prompts) == 2
    assert provider.prompts[0] == provider.prompts[1] == build_prompt(SAMPLE_REQUEST)


@pytest.mark.parametrize(
    ("first", "second", "raised"),
    [
        ("not json", "still not json", LlmBadResponse),
        (unavailable(503), unavailable(503), LlmUnavailable),
        ("not json", unavailable(503), LlmUnavailable),
        (unavailable(429), '{"hint": " "}', LlmBadResponse),
    ],
    ids=["bad twice", "503 twice", "bad then 503", "429 then blank"],
)
def test_after_two_failures_the_last_error_is_raised_and_nothing_more_is_tried(
    first: str | Exception, second: str | Exception, raised: type[LlmError]
) -> None:
    provider = ScriptedProvider(first, second, GOOD)

    with pytest.raises(raised):
        generate_feedback(provider, SAMPLE_REQUEST)

    assert len(provider.prompts) == MAX_ATTEMPTS == 2


@pytest.mark.parametrize(
    "error",
    [
        TIMEOUT,
        LlmUnavailable("The Gemini request failed"),
        unavailable(400),
        unavailable(401),
        unavailable(403),
        unavailable(404),
        LlmNotConfigured("GEMINI_API_KEY and GEMINI_MODEL must both be set"),
    ],
    ids=[
        "timeout",
        "connection failed",
        "400",
        "401",
        "403",
        "404",
        "not configured",
    ],
)
def test_a_failure_another_try_cannot_help_is_raised_at_once(error: LlmError) -> None:
    provider = ScriptedProvider(error, GOOD)

    with pytest.raises(type(error)) as raised:
        generate_feedback(provider, SAMPLE_REQUEST)

    assert raised.value is error
    assert len(provider.prompts) == 1


# ---- a leaked solution is regenerated, never returned ----


@pytest.mark.parametrize("level", [1, 2, 3])
def test_a_leak_is_regenerated_with_the_after_leak_prompt(level: int) -> None:
    provider = ScriptedProvider(LEAK, GOOD)

    assert generate_feedback(provider, at_level(level)) == HINT
    assert provider.prompts == [
        build_prompt(at_level(level)),
        build_prompt(at_level(level), after_leak=True),
    ]
    # The refused answer is never quoted back to the model.
    assert "a + b" not in provider.prompts[1]


@pytest.mark.parametrize(
    "first",
    ["not json", unavailable(503)],
    ids=["bad answer", "503"],
)
def test_only_a_leak_changes_the_retrys_prompt(first: str | Exception) -> None:
    provider = ScriptedProvider(first, LEAK)

    assert generate_feedback(provider, SAMPLE_REQUEST) == SAFE
    assert provider.prompts == [build_prompt(SAMPLE_REQUEST)] * 2


@pytest.mark.parametrize("level", [1, 2, 3])
@pytest.mark.parametrize(
    "first",
    [LEAK, "not json", unavailable(503)],
    ids=["leak", "bad answer", "503"],
)
def test_when_the_last_attempt_leaks_the_safe_hint_is_returned(
    first: str | Exception, level: int
) -> None:
    provider = ScriptedProvider(first, LEAK, GOOD)

    response = generate_feedback(provider, at_level(level))

    assert response == SAFE
    assert "a + b" not in response.hint
    assert len(provider.prompts) == MAX_ATTEMPTS


@pytest.mark.parametrize(
    ("second", "raised"),
    [("not json", LlmBadResponse), (unavailable(503), LlmUnavailable)],
    ids=["bad answer", "503"],
)
def test_a_leak_then_a_failure_raises_the_failure(
    second: str | Exception, raised: type[LlmError]
) -> None:
    provider = ScriptedProvider(LEAK, second, GOOD)

    with pytest.raises(raised):
        generate_feedback(provider, SAMPLE_REQUEST)

    assert len(provider.prompts) == MAX_ATTEMPTS


def test_a_hint_with_an_allowed_snippet_is_returned_as_it_is() -> None:
    hint = (
        "On another example:\n```python\ntotal = 0\nfor x in xs:\n    total += x\n```"
    )
    provider = ScriptedProvider(json.dumps({"hint": hint}))

    assert generate_feedback(provider, at_level(3)) == FeedbackResponse(
        hint=hint, fallback=False
    )
    assert len(provider.prompts) == 1


@pytest.mark.parametrize(
    "answer",
    [
        '{"hint": "Look at the loop.", "fallback": true}',
        '{"hint": "Look at the loop.", "fallback": false}',
    ],
    ids=["true", "false"],
)
def test_the_model_cannot_set_the_fallback_flag(answer: str) -> None:
    with pytest.raises(LlmBadResponse):
        parse_hint(answer)

    provider = ScriptedProvider(answer, answer)
    with pytest.raises(LlmBadResponse):
        generate_feedback(provider, SAMPLE_REQUEST)


def test_only_the_safe_hint_is_flagged_as_a_fallback() -> None:
    # The safe hint's text from the model is a real answer that passed the guard.
    provider = ScriptedProvider(json.dumps({"hint": SAFE_HINT}))

    assert generate_feedback(provider, SAMPLE_REQUEST) == FeedbackResponse(
        hint=SAFE_HINT, fallback=False
    )


def test_the_retry_rules_are_the_named_constants() -> None:
    assert MAX_ATTEMPTS == 2
    assert RETRYABLE_STATUS_CODES == {429, 500, 502, 503, 504}


# ---- the command ----


def test_the_command_prints_the_hint_and_succeeds(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    provider = ScriptedProvider(GOOD)
    monkeypatch.setattr(feedback, "gemini_from_settings", lambda settings: provider)

    assert main([]) == 0

    out, err = capsys.readouterr()
    assert out == "Check which operator you used.\n"
    assert err == ""
    assert provider.prompts == [build_prompt(SAMPLE_REQUEST)]
    assert provider.closed


def test_the_command_says_on_stderr_when_it_prints_the_safe_hint(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    provider = ScriptedProvider(LEAK, LEAK)
    monkeypatch.setattr(feedback, "gemini_from_settings", lambda settings: provider)

    assert main([]) == 0

    out, err = capsys.readouterr()
    assert out == SAFE_HINT + "\n"
    assert err == "Both answers leaked a solution: this is the safe hint.\n"
    assert provider.closed


@pytest.mark.parametrize("level", ["1", "2", "3"])
def test_the_command_takes_the_hint_level(
    monkeypatch: pytest.MonkeyPatch, level: str
) -> None:
    provider = ScriptedProvider(GOOD)
    monkeypatch.setattr(feedback, "gemini_from_settings", lambda settings: provider)

    assert main([level]) == 0

    assert provider.prompts == [build_prompt(at_level(int(level)))]


@pytest.mark.parametrize("args", [["0"], ["4"], ["x"], ["1", "2"]])
def test_the_command_refuses_other_arguments(
    args: list[str], capsys: pytest.CaptureFixture[str]
) -> None:
    assert main(args) == 2

    assert capsys.readouterr().err.startswith("Usage:")


def test_the_command_reports_a_failed_call_in_one_line(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    provider = ScriptedProvider(unavailable(503), unavailable(503))
    monkeypatch.setattr(feedback, "gemini_from_settings", lambda settings: provider)

    assert main([]) == 1

    out, err = capsys.readouterr()
    assert out == ""
    assert err == "No feedback: Gemini answered HTTP 503\n"
    assert provider.closed


def test_the_command_reports_missing_gemini_settings(
    capsys: pytest.CaptureFixture[str],
) -> None:
    # The fixture's settings have no Gemini key or model.
    assert main([]) == 1

    out, err = capsys.readouterr()
    assert out == ""
    assert err.startswith("Not configured: GEMINI_API_KEY and GEMINI_MODEL")


def test_the_command_reports_a_missing_token(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv("AI_SERVICE_TOKEN")

    assert main([]) == 1

    assert "ai_service_token" in capsys.readouterr().err


def test_python_dash_m_runs_the_command() -> None:
    # A real process. Empty environment values win over the developer's own .env,
    # so it stops at the configuration and never reaches the network.
    env = {
        **os.environ,
        "AI_SERVICE_TOKEN": "token",
        "GEMINI_API_KEY": "",
        "GEMINI_MODEL": "",
    }

    run = subprocess.run(
        [sys.executable, "-m", "ai_service.feedback", "2"],
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert run.returncode == 1
    assert run.stdout == ""
    assert run.stderr.startswith("Not configured: GEMINI_API_KEY and GEMINI_MODEL")


def test_the_sample_request_is_a_made_up_failing_submission() -> None:
    assert SAMPLE_REQUEST.hint_level == 1
    assert SAMPLE_REQUEST.public_tests[0].status.value == "FAILED"
    json.dumps(SAMPLE_REQUEST.model_dump(mode="json"))


# ---- the real API (opt-in) ----


@pytest.mark.skipif(
    os.environ.get("RUN_LIVE_GEMINI") != "1",
    reason="calls the real Gemini API; set RUN_LIVE_GEMINI=1 to run it",
)
def test_the_real_gemini_api_gives_a_hint_for_the_sample_request() -> None:
    gemini = gemini_from_settings(Settings(_env_file=config.DEFAULT_ENV_FILE))
    try:
        response = generate_feedback(gemini, SAMPLE_REQUEST)
    finally:
        gemini.close()

    print(f"\nlive hint: {len(response.hint)} characters, fallback {response.fallback}")
    assert response.hint.strip()
