from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from ai_service.config import get_settings


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    # Read the settings before serving anything, so a missing or empty
    # AI_SERVICE_TOKEN stops the service at startup instead of at the first request.
    get_settings()
    yield


# No /docs, /redoc or /openapi.json: every route but /health requires the bearer
# token (6.1c), and FastAPI serves those three without it.
app = FastAPI(
    title="Grading Platform AI Service",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)

# Endpoints other than /health live on routers made by
# ai_service.auth.protected_router(), included here with app.include_router(...).


@app.get("/health")
def health() -> dict[str, str]:
    """Open to anyone: the hosting health checks (16.8a) have no token."""
    return {"status": "ok"}
