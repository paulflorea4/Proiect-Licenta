import os
import subprocess
import sys

import pytest

from ai_service import config, feedback
from ai_service.config import Settings
from ai_service.feedback import HARDCODED_PROMPT, generate_feedback, main
from ai_service.llm import (
    LlmBadResponse,
    LlmProvider,
    LlmUnavailable,
    gemini_from_settings,
)


class FakeProvider(LlmProvider):
    """Records each prompt and answers with a fixed text, or raises."""

    def __init__(
        self, answer: str = "Look at the operator.", error: Exception | None = None
    ):
        self.prompts: list[str] = []
        self.answer = answer
        self.error = error
        self.closed = False

    def generate(self, prompt: str) -> str:
        self.prompts.append(prompt)
        if self.error is not None:
            raise self.error
        return self.answer

    def close(self) -> None:
        self.closed = True


# ---- generate_feedback ----


def test_the_hardcoded_prompt_is_sent_once_and_the_answer_returned() -> None:
    fake = FakeProvider("Check which operator you used.")

    assert generate_feedback(fake) == "Check which operator you used."
    assert fake.prompts == [HARDCODED_PROMPT]


def test_the_prompt_asks_for_a_hint_and_not_for_the_solution() -> None:
    assert "hint" in HARDCODED_PROMPT
    assert "Do not write the corrected code" in HARDCODED_PROMPT


@pytest.mark.parametrize(
    "error", [LlmUnavailable("Gemini answered HTTP 503"), LlmBadResponse("no text")]
)
def test_a_provider_error_is_not_hidden(error: Exception) -> None:
    with pytest.raises(type(error)):
        generate_feedback(FakeProvider(error=error))


# ---- the command ----


def test_the_command_prints_the_answer_and_succeeds(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeProvider("Check which operator you used.")
    monkeypatch.setattr(feedback, "gemini_from_settings", lambda settings: fake)

    assert main() == 0

    out, err = capsys.readouterr()
    assert out == "Check which operator you used.\n"
    assert err == ""
    assert fake.prompts == [HARDCODED_PROMPT]
    assert fake.closed


def test_the_command_reports_a_failed_call_in_one_line(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    fake = FakeProvider(error=LlmUnavailable("Gemini answered HTTP 503"))
    monkeypatch.setattr(feedback, "gemini_from_settings", lambda settings: fake)

    assert main() == 1

    out, err = capsys.readouterr()
    assert out == ""
    assert err == "No feedback: Gemini answered HTTP 503\n"
    assert fake.closed


def test_the_command_reports_missing_gemini_settings(
    capsys: pytest.CaptureFixture[str],
) -> None:
    # The fixture's settings have no Gemini key or model.
    assert main() == 1

    out, err = capsys.readouterr()
    assert out == ""
    assert err.startswith("Not configured: GEMINI_API_KEY and GEMINI_MODEL")


def test_the_command_reports_a_missing_token(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv("AI_SERVICE_TOKEN")

    assert main() == 1

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
        [sys.executable, "-m", "ai_service.feedback"],
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )

    assert run.returncode == 1
    assert run.stdout == ""
    assert run.stderr.startswith("Not configured: GEMINI_API_KEY and GEMINI_MODEL")


# ---- the real API (opt-in) ----


@pytest.mark.skipif(
    os.environ.get("RUN_LIVE_GEMINI") != "1",
    reason="calls the real Gemini API; set RUN_LIVE_GEMINI=1 to run it",
)
def test_the_real_gemini_api_gives_feedback_for_the_hardcoded_prompt() -> None:
    gemini = gemini_from_settings(Settings(_env_file=config.DEFAULT_ENV_FILE))
    try:
        text = generate_feedback(gemini)
    finally:
        gemini.close()

    print(f"\nlive feedback: {len(text)} characters")
    assert text.strip()
