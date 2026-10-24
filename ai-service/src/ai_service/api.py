"""``POST /feedback`` (6.5a): request → prompt → LLM → validate → guard → response.

The whole loop is :func:`ai_service.feedback.generate_feedback`; this module only puts
it behind HTTP. The endpoint is on a router made by
:func:`ai_service.auth.protected_router`, so the bearer token is checked before the
body is read or a provider is built.
"""

from collections.abc import Iterator
from typing import Annotated

from fastapi import Depends, HTTPException, status

from ai_service.auth import protected_router
from ai_service.config import get_settings
from ai_service.feedback import generate_feedback
from ai_service.llm import (
    GeminiProvider,
    LlmBadResponse,
    LlmError,
    LlmNotConfigured,
    LlmProvider,
    LlmUnavailable,
    gemini_from_settings,
)
from ai_service.schemas import FeedbackRequest, FeedbackResponse

router = protected_router()

# What each provider error becomes. The body is the error's own message, which is
# fixed text (never the key, the prompt or the model's answer, 6.2a). The backend's
# client (7.1a) turns any of them into its own "hint unavailable" error.
ERROR_STATUS: dict[type[LlmError], int] = {
    LlmNotConfigured: status.HTTP_503_SERVICE_UNAVAILABLE,
    LlmUnavailable: status.HTTP_503_SERVICE_UNAVAILABLE,
    LlmBadResponse: status.HTTP_502_BAD_GATEWAY,
}


def get_provider() -> Iterator[GeminiProvider]:
    """The configured provider for one request, closed when the request is done.
    Tests replace it through ``app.dependency_overrides``."""
    try:
        provider = gemini_from_settings(get_settings())
    except LlmNotConfigured as error:
        raise _http_error(error) from None
    try:
        yield provider
    finally:
        provider.close()


@router.post("/feedback")
def feedback(
    request: FeedbackRequest,
    provider: Annotated[LlmProvider, Depends(get_provider)],
) -> FeedbackResponse:
    """The hint for the request's level, or ``fallback`` with the safe hint (6.4b).

    A plain ``def``: FastAPI runs it on a worker thread, so the blocking call to the
    provider (up to two attempts, 6.3c) does not stall other requests.
    """
    try:
        return generate_feedback(provider, request)
    except LlmError as error:
        raise _http_error(error) from None


def _http_error(error: LlmError) -> HTTPException:
    return HTTPException(status_code=ERROR_STATUS[type(error)], detail=str(error))
