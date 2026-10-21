"""Feedback on a student's submission.

6.2b: one hardcoded prompt, to prove the provider end to end. 6.3b replaces
:data:`HARDCODED_PROMPT` with a prompt built from the request, and 6.5a puts
``POST /feedback`` on top. Until then the prompt stays this one constant: don't
generalize it early.

Run it against the real API (reads ``ai-service/.env``)::

    uv run python -m ai_service.feedback
"""

import sys

from pydantic import ValidationError

from ai_service.config import get_settings
from ai_service.llm import LlmError, LlmProvider, gemini_from_settings

# A made-up case, no student's data: a tutor's hint for a classic slip.
HARDCODED_PROMPT = (
    "You are a programming tutor. A student's Python program should read two "
    "integers from one line of standard input and print their sum, but it prints "
    "their product instead. In one or two sentences, give the student a hint that "
    "points them at the mistake. Do not write the corrected code."
)


def generate_feedback(provider: LlmProvider) -> str:
    """The provider's answer to the hardcoded prompt."""
    return provider.generate(HARDCODED_PROMPT)


def main() -> int:
    """Prints the real provider's answer; on failure, a one-line reason and 1."""
    try:
        provider = gemini_from_settings(get_settings())
    except (LlmError, ValidationError) as e:
        print(f"Not configured: {e}", file=sys.stderr)
        return 1
    try:
        print(generate_feedback(provider))
    except LlmError as e:
        print(f"No feedback: {e}", file=sys.stderr)
        return 1
    finally:
        provider.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
