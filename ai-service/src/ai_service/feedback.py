"""Feedback on a student's submission: the request's prompt, through a provider.

6.2b proved the provider with one hardcoded prompt; since 6.3b the prompt is built
from the request (:func:`ai_service.prompts.build_prompt`). 6.3c validates the answer
and 6.5a puts ``POST /feedback`` on top; until 6.3c the model's text is returned as
it comes (the prompt asks for ``{"hint": ...}``).

Try it against the real API with a made-up request (reads ``ai-service/.env``)::

    uv run python -m ai_service.feedback [hint level 1-3, default 1]
"""

import sys

from pydantic import ValidationError

from ai_service.config import get_settings
from ai_service.llm import LlmError, LlmProvider, gemini_from_settings
from ai_service.prompts import build_prompt
from ai_service.schemas import FeedbackRequest

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


def generate_feedback(provider: LlmProvider, request: FeedbackRequest) -> str:
    """The provider's answer to the request's prompt."""
    return provider.generate(build_prompt(request))


def main(argv: list[str] | None = None) -> int:
    """Prints the real provider's answer to the sample request at the level given;
    on failure, a one-line reason and 1."""
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
