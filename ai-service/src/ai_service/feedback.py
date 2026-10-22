"""Feedback on a student's submission: the request's prompt, through a provider.

6.2b proved the provider with one hardcoded prompt; 6.3b builds the prompt from the
request (:func:`ai_service.prompts.build_prompt`). 6.3c reads the model's answer
(:func:`parse_hint`) and tries once more when the answer is unusable or the provider
was briefly unavailable. 6.5a puts ``POST /feedback`` on top.

Try it against the real API with a made-up request (reads ``ai-service/.env``)::

    uv run python -m ai_service.feedback [hint level 1-3, default 1]
"""

import json
import re
import sys

from pydantic import ValidationError

from ai_service.config import get_settings
from ai_service.llm import (
    LlmBadResponse,
    LlmError,
    LlmProvider,
    LlmUnavailable,
    gemini_from_settings,
)
from ai_service.prompts import build_prompt
from ai_service.schemas import FeedbackRequest, FeedbackResponse

# One call and at most one more (6.3c). Never more: every call costs the student's
# wait and tokens, and the backend's timeout bounds the whole of it.
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
        answer = FeedbackResponse.model_validate(json.loads(body))
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


def generate_feedback(provider: LlmProvider, request: FeedbackRequest) -> str:
    """The hint for the request: the prompt goes to the provider and the answer is
    read with :func:`parse_hint`. An unusable answer, or a provider that is briefly
    unavailable (HTTP 429 or 5xx), is tried once more (:data:`MAX_ATTEMPTS`).

    Raises:
        LlmError: the last attempt's error, when no attempt gave a hint, or at once
            when trying again cannot help (a timeout, another error status, missing
            configuration). Its message is fixed text.
    """
    prompt = build_prompt(request)
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            return parse_hint(provider.generate(prompt))
        except LlmError as error:
            if attempt == MAX_ATTEMPTS or not _worth_another_try(error):
                raise
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
        print(generate_feedback(provider, request))
    except LlmError as e:
        print(f"No feedback: {e}", file=sys.stderr)
        return 1
    finally:
        provider.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
