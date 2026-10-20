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


app = FastAPI(title="Grading Platform AI Service", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
