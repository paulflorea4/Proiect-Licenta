"""Large-language-model providers (6.2a): a prompt goes in, text comes out.

:class:`LlmProvider` is the interface the rest of the service codes against, so the
thesis can compare providers later. :class:`GeminiProvider` is the one implementation:
the Gemini REST API over ``httpx``. The model name comes from ``GEMINI_MODEL``, never
from the code.

Errors never carry the API key, the prompt or the provider's answer: a prompt holds
student code, and an error message ends up in logs.
"""

import re
from abc import ABC, abstractmethod
from typing import Any

import httpx

from ai_service.config import Settings

GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

# Placeholders, for the human to tune. The backend has its own timeouts on its call to
# this service (root CLAUDE.md), which must allow for these.
CONNECT_TIMEOUT_SECONDS = 5.0
READ_TIMEOUT_SECONDS = 30.0

# What a model name may contain: it is put into the URL's path.
_MODEL_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

# Gemini's error status words are one uppercase word such as UNAVAILABLE or
# RESOURCE_EXHAUSTED. Only a value of this shape is ever put into a message.
_STATUS_WORD = re.compile(r"[A-Z][A-Z_]{0,39}")


class LlmError(Exception):
    """The provider gave no usable text. The message is fixed text."""


class LlmUnavailable(LlmError):
    """No answer: the request failed, timed out, or got an HTTP error status.

    ``status_code`` is the HTTP status when the provider answered with an error, and
    None when there was no answer (a timeout or a failed connection), which 6.3c
    never retries.
    """

    def __init__(self, message: str, *, status_code: int | None = None):
        super().__init__(message)
        self.status_code = status_code


class LlmBadResponse(LlmError):
    """An answer without usable text: not JSON, blocked, or empty."""


class LlmNotConfigured(LlmError):
    """The provider's settings are missing or invalid."""


class LlmProvider(ABC):
    """Turns a prompt into text."""

    @abstractmethod
    def generate(self, prompt: str) -> str:
        """The model's text for ``prompt``.

        Raises:
            LlmUnavailable: the provider could not be reached or refused the request.
            LlmBadResponse: it answered, but with no usable text.
        """


class GeminiProvider(LlmProvider):
    """Gemini's ``models/{model}:generateContent``, one text prompt per call.

    The key is sent in the ``x-goog-api-key`` header, never in the URL.
    """

    def __init__(self, api_key: str, model: str, client: httpx.Client | None = None):
        if not api_key:
            raise LlmNotConfigured("The Gemini API key is empty")
        if not _MODEL_NAME.fullmatch(model):
            raise LlmNotConfigured("The Gemini model name is not a valid name")
        self._api_key = api_key
        self._url = f"{GEMINI_BASE_URL}/models/{model}:generateContent"
        self._client = client or httpx.Client(
            timeout=httpx.Timeout(READ_TIMEOUT_SECONDS, connect=CONNECT_TIMEOUT_SECONDS)
        )

    def generate(self, prompt: str) -> str:
        try:
            response = self._client.post(
                self._url,
                headers={"x-goog-api-key": self._api_key},
                json={"contents": [{"role": "user", "parts": [{"text": prompt}]}]},
            )
        except httpx.TimeoutException as e:
            raise LlmUnavailable("The Gemini request timed out") from e
        except httpx.HTTPError as e:
            raise LlmUnavailable("The Gemini request failed") from e
        if not response.is_success:
            word = _status_word(response)
            raise LlmUnavailable(
                f"Gemini answered HTTP {response.status_code}"
                + (f" ({word})" if word else ""),
                status_code=response.status_code,
            )
        try:
            body = response.json()
        except ValueError:
            raise LlmBadResponse("Gemini's answer is not JSON") from None
        return _text_of(body)

    def close(self) -> None:
        self._client.close()

    def __repr__(self) -> str:
        return f"GeminiProvider(url={self._url!r})"


def _status_word(response: httpx.Response) -> str | None:
    """Gemini's ``error.status`` (e.g. ``UNAVAILABLE``) if it is one plain uppercase
    word; nothing else of the error body is ever read into a message."""
    try:
        status = response.json()["error"]["status"]
    except (ValueError, KeyError, TypeError):
        return None
    if isinstance(status, str) and _STATUS_WORD.fullmatch(status):
        return status
    return None


def _text_of(body: Any) -> str:
    """The text of the first candidate, its parts joined; refuses anything else."""
    if not isinstance(body, dict):
        raise LlmBadResponse("Gemini's answer has an unexpected shape")
    if (body.get("promptFeedback") or {}).get("blockReason"):
        raise LlmBadResponse("Gemini blocked the prompt")
    candidates = body.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        raise LlmBadResponse("Gemini's answer has no candidate")
    content = candidates[0].get("content") if isinstance(candidates[0], dict) else None
    parts = content.get("parts") if isinstance(content, dict) else None
    if not isinstance(parts, list):
        raise LlmBadResponse("Gemini's answer has no text")
    text = "".join(
        part["text"]
        for part in parts
        if isinstance(part, dict) and isinstance(part.get("text"), str)
    )
    if not text.strip():
        raise LlmBadResponse("Gemini's answer has no text")
    return text


def gemini_from_settings(
    settings: Settings, client: httpx.Client | None = None
) -> GeminiProvider:
    """The Gemini provider for the configured key and model; both must be set."""
    if settings.gemini_api_key is None or settings.gemini_model is None:
        raise LlmNotConfigured("GEMINI_API_KEY and GEMINI_MODEL must both be set")
    return GeminiProvider(
        settings.gemini_api_key.get_secret_value(), settings.gemini_model, client
    )
