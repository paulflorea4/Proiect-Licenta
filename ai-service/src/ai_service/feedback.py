"""Feedback on a student's submission: the request's prompt, through a provider.

6.2b proved the provider with one hardcoded prompt; 6.3b builds the prompt from the
request (:func:`ai_service.prompts.build_prompt`). 6.3c reads the model's answer
(:func:`parse_hint`) and tries once more when the answer is unusable or the provider
was briefly unavailable. 6.4a refuses a hint that leaks a solution
(:func:`ai_service.leak_guard.find_leak`): it is regenerated, and if the last attempt
leaks too, a generic safe hint is returned instead, flagged as such (6.4b). The retry
after a leak tells the model why its answer was refused (6.4c). 6.5a puts
``POST /feedback`` on top.

Try it against the real API with a made-up request (reads ``ai-service/.env``)::

    uv run python -m ai_service.feedback [hint level 1-3, default 1]
"""

import json
import re
import sys

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from ai_service.config import get_settings
from ai_service.leak_guard import SAFE_HINT, find_leak
from ai_service.llm import (
    LlmBadResponse,
    LlmError,
    LlmProvider,
    LlmUnavailable,
    gemini_from_settings,
)
from ai_service.prompts import build_prompt
from ai_service.schemas import FeedbackRequest, FeedbackResponse

# One call and at most one more (6.3c), for a failure or a leak (6.4a) alike. Never
# more: every call costs the student's wait and tokens, and the backend's timeout
# bounds the whole of it.
MAX_ATTEMPTS = 2

# The provider's error statuses that are worth one more try: too many requests and
# the server-side failures that Gemini returns within a second when overloaded. A
# timeout is never retried: a second full wait would outlast the backend's timeout.
RETRYABLE_STATUS_CODES = frozenset({429, 500, 502, 503, 504})

# A whole answer wrapped in a Markdown code fence, as models sometimes send JSON.
_FENCED = re.compile(r"\A```[A-Za-z]*[ \t]*\n(?P<body>.*)\n```\Z", re.S)

# A made-up submission, no student's data: the product printed instead of the sum.
SAMPLE_REQUEST = FeedbackRequest.model_validate(
    {
        "assignmentDescription": (
            "Read two integers separated by a space from standard input and print "
            "their sum."
        ),
        "language": "PYTHON",
        "code": "a, b = map(int, input().split())\nprint(a * b)\n",
        "publicTests": [
            {
                "name": "small numbers",
                "input": "2 3\n",
                "expectedOutput": "5",
                "status": "FAILED",
                "actualOutput": "6\n",
                "stderr": "",
            }
        ],
        "hiddenTests": [{"passed": True}, {"passed": False}],
        "hintLevel": 1,
    }
)


class _ModelAnswer(BaseModel):
    """What the model must answer. Not the response: the model cannot set ``fallback``,
    so an answer that holds it is refused like any other unknown key."""

    model_config = ConfigDict(extra="forbid")

    hint: str = Field(min_length=1)


def parse_hint(text: str) -> str:
    """The hint in the model's answer, which must be ``{"hint": "<non-blank text>"}``
    and nothing else (a Markdown code fence around it is allowed).

    Raises:
        LlmBadResponse: anything else. The message is fixed text; the answer itself
            is never put into it.
    """
    body = text.strip()
    fenced = _FENCED.match(body)
    if fenced:
        body = fenced.group("body").strip()
    try:
        answer = _ModelAnswer.model_validate(json.loads(body))
    except (ValueError, ValidationError):
        raise LlmBadResponse("The model's answer is not a {hint} object") from None
    hint = answer.hint.strip()
    if not hint:
        raise LlmBadResponse("The model's hint is blank")
    return hint


def _worth_another_try(error: LlmError) -> bool:
    if isinstance(error, LlmBadResponse):
        return True
    # A timeout or a failed connection has no status code, so it is not retried.
    return (
        isinstance(error, LlmUnavailable)
        and error.status_code in RETRYABLE_STATUS_CODES
    )


def generate_feedback(
    provider: LlmProvider, request: FeedbackRequest
) -> FeedbackResponse:
    """The hint for the request: the prompt goes to the provider and the answer is
    read with :func:`parse_hint`. An unusable answer, a provider that is briefly
    unavailable (HTTP 429 or 5xx), or a hint that leaks a solution (:func:`find_leak`)
    is tried once more (:data:`MAX_ATTEMPTS`): after a leak with the prompt built
    ``after_leak``, otherwise with the same prompt. The last attempt decides: a leak
    then gives :data:`SAFE_HINT` with ``fallback`` true, never the leak; a failure is
    raised. A real hint has ``fallback`` false.

    Raises:
        LlmError: the last attempt's error, when no attempt gave a hint, or at once
            when trying again cannot help (a timeout, another error status, missing
            configuration). Its message is fixed text.
    """
    prompt = build_prompt(request)
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            hint = parse_hint(provider.generate(prompt))
        except LlmError as error:
            if attempt == MAX_ATTEMPTS or not _worth_another_try(error):
                raise
            continue
        if find_leak(hint, request) is None:
            return FeedbackResponse(hint=hint, fallback=False)
        if attempt == MAX_ATTEMPTS:
            return FeedbackResponse(hint=SAFE_HINT, fallback=True)
        prompt = build_prompt(request, after_leak=True)
    raise AssertionError("unreachable: the last attempt returns or raises")


def main(argv: list[str] | None = None) -> int:
    """Prints the hint for the sample request at the level given, from the real
    provider; on failure, a one-line reason and 1."""
    args = sys.argv[1:] if argv is None else argv
    if len(args) > 1 or (args and args[0] not in {"1", "2", "3"}):
        print("Usage: python -m ai_service.feedback [1|2|3]", file=sys.stderr)
        return 2
    request = SAMPLE_REQUEST.model_copy(
        update={"hint_level": int(args[0]) if args else 1}
    )
    try:
        provider = gemini_from_settings(get_settings())
    except (LlmError, ValidationError) as e:
        print(f"Not configured: {e}", file=sys.stderr)
        return 1
    try:
        response = generate_feedback(provider, request)
        print(response.hint)
        if response.fallback:
            print(
                "Both answers leaked a solution: this is the safe hint.",
                file=sys.stderr,
            )
    except LlmError as e:
        print(f"No feedback: {e}", file=sys.stderr)
        return 1
    finally:
        provider.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
