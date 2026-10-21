"""Bearer-token authentication between the backend and this service (6.1c).

Every endpoint except ``GET /health`` requires ``Authorization: Bearer
<AI_SERVICE_TOKEN>``. The check is attached to the router, not to each endpoint: a
module that adds endpoints creates its router with :func:`protected_router`, so an
endpoint on it cannot leave the check out (``tests/test_auth.py`` also fails if any
route of the app but ``/health`` lacks it).
"""

import hmac
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, status

from ai_service.config import get_settings

# One answer for a missing header, another scheme and a wrong token alike, so a
# caller learns nothing from the difference. Never contains what was sent.
NOT_AUTHENTICATED = "Not authenticated"


def require_bearer_token(request: Request) -> None:
    """Lets the request through only with ``Authorization: Bearer <AI_SERVICE_TOKEN>``.

    The scheme is matched ignoring case (RFC 7235); the token must match exactly,
    compared in constant time. The token is never logged.
    """
    scheme, _, sent = request.headers.get("Authorization", "").partition(" ")
    expected = get_settings().ai_service_token.get_secret_value()
    # Compared as bytes: compare_digest refuses str with non-ASCII characters, and
    # such a header must be a 401, not a 500.
    matches = hmac.compare_digest(sent.encode(), expected.encode())
    if scheme.lower() != "bearer" or not matches:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=NOT_AUTHENTICATED,
            headers={"WWW-Authenticate": "Bearer"},
        )


def protected_router(**kwargs: Any) -> APIRouter:
    """A router whose every endpoint requires the bearer token. Use it for every
    endpoint the service adds; include it in the app in ``main.py``."""
    return APIRouter(dependencies=[Depends(require_bearer_token)], **kwargs)
