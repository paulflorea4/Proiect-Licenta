"""The service's settings, read from environment variables and ``ai-service/.env``.

The variables are the ones listed in ``ai-service/.env.example``. Real environment
variables win over the file. There are no database settings: this service never
connects to the database.
"""

from functools import lru_cache
from pathlib import Path

from pydantic import SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# ai-service/.env, wherever the service is started from (src/ai_service/config.py is
# two levels below the project directory).
DEFAULT_ENV_FILE = Path(__file__).resolve().parents[2] / ".env"
# The file get_settings() reads; None reads none (the tests use that).
ENV_FILE: Path | None = DEFAULT_ENV_FILE


class Settings(BaseSettings):
    """``AI_SERVICE_TOKEN`` is required; the Gemini values are not needed until the
    service calls the real API (6.2a), so they may be left empty for now."""

    model_config = SettingsConfigDict(env_file_encoding="utf-8", extra="ignore")

    ai_service_token: SecretStr
    gemini_api_key: SecretStr | None = None
    # No default model anywhere in the code (root CLAUDE.md): None until it is set.
    gemini_model: str | None = None

    @field_validator("ai_service_token")
    @classmethod
    def token_must_not_be_blank(cls, token: SecretStr) -> SecretStr:
        if not token.get_secret_value().strip():
            raise ValueError("AI_SERVICE_TOKEN must not be empty")
        return token

    @field_validator("gemini_api_key", "gemini_model", mode="before")
    @classmethod
    def blank_means_unset(cls, value: object) -> object:
        if isinstance(value, str) and not value.strip():
            return None
        return value


@lru_cache
def get_settings() -> Settings:
    """The settings, read once. Fails with a validation error naming the variable
    (never its value) when the token is missing or empty."""
    return Settings(_env_file=ENV_FILE)
