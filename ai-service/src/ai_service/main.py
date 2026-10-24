from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from ai_service import api
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
app.include_router(api.router)


@app.exception_handler(RequestValidationError)
def refuse_invalid_body(_: Request, error: RequestValidationError) -> JSONResponse:
    """A 422 that says where and why, but never echoes what was sent: FastAPI's own
    adds each wrong value (``input``), which can be a student's code or a field a
    hidden test must not carry (6.3a)."""
    detail = [
        {"loc": list(e["loc"]), "msg": e["msg"], "type": e["type"]}
        for e in error.errors()
    ]
    return JSONResponse(status_code=422, content={"detail": detail})


@app.get("/health")
def health() -> dict[str, str]:
    """Open to anyone: the hosting health checks (16.8a) have no token."""
    return {"status": "ok"}
