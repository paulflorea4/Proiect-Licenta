"""Fakes shared by the tests (6.6a): a scripted provider, and Gemini itself faked at
the HTTP level from the response fixtures in ``tests/fixtures/gemini``.

``ScriptedProvider`` stands in for any :class:`LlmProvider` when a test is about
what happens around the provider. ``gemini_playing`` keeps the real
:class:`GeminiProvider` and fakes only the network, so the request it builds and the
way it reads Gemini's answer are tested too.
"""

import json
from pathlib import Path
from typing import Any

import httpx

from ai_service.llm import GeminiProvider, LlmProvider

FIXTURES = Path(__file__).parent / "fixtures" / "gemini"
KEY = "fake-gemini-key-for-tests"
MODEL = "fixture-model"


class ScriptedProvider(LlmProvider):
    """Plays its script, one entry per call: a text to return or an error to raise."""

    def __init__(self, *script: str | Exception):
        self.script = list(script)
        self.prompts: list[str] = []
        self.closed = False

    def generate(self, prompt: str) -> str:
        self.prompts.append(prompt)
        step = self.script.pop(0)
        if isinstance(step, Exception):
            raise step
        return step

    def close(self) -> None:
        self.closed = True


def fixture_names() -> list[str]:
    return sorted(path.stem for path in FIXTURES.glob("*.json"))


def gemini_fixture(name: str) -> tuple[int, Any]:
    """A fixture's HTTP status and body: an ``error`` body carries its own code,
    anything else is a 200."""
    body = json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))
    status = body["error"]["code"] if "error" in body else 200
    return status, body


def gemini_playing(
    *names: str, key: str = KEY
) -> tuple[GeminiProvider, list[httpx.Request]]:
    """The real Gemini provider, answering each call with the next fixture, and the
    requests it sent. A call beyond the script fails the test. ``key`` is the API key
    it holds (a test about where a key may show passes the configured one)."""
    script = list(names)
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        assert script, "Gemini was called more often than the test expected"
        status, body = gemini_fixture(script.pop(0))
        return httpx.Response(status, json=body)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    return GeminiProvider(key, MODEL, client), seen


def prompt_sent(request: httpx.Request) -> str:
    """The prompt text in a request the provider sent to Gemini."""
    return json.loads(request.content)["contents"][0]["parts"][0]["text"]
