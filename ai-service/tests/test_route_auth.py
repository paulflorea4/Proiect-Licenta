"""Every route but /health refuses a request without the right token, and the token
never shows up in a response or a log line (6.6e).

The routes are read from the app itself when this module is collected, so an
endpoint added later gets these tests without anyone writing them. 6.1c's guard
checks that the token check is attached; these send the requests.
"""

import logging
import re
import socket
import threading
import time
from collections.abc import Iterator

import httpx
import pytest
import uvicorn
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_service import api, config
from ai_service.auth import NOT_AUTHENTICATED
from ai_service.feedback import SAMPLE_REQUEST
from ai_service.main import app
from fakes import gemini_playing

PUBLIC = {("GET", "/health")}
GEMINI_KEY = "GEMINI-KEY-NEVER-SHOWN"


def routes_of(target: FastAPI) -> list[tuple[str, str]]:
    """Every (method, path) the app answers, routers included (FastAPI 0.142 keeps
    an included router as one entry; its effective candidates are its routes)."""
    found: set[tuple[str, str]] = set()

    def walk(routes: list) -> None:  # type: ignore[type-arg]
        for route in routes:
            candidates = getattr(route, "effective_candidates", None)
            if candidates is not None:
                walk(candidates())
                continue
            for method in getattr(route, "methods", None) or ():
                found.add((method, route.path))

    walk(target.routes)
    return sorted(found)


PROTECTED = [route for route in routes_of(app) if route not in PUBLIC]


def concrete(path: str) -> str:
    """The path with any {parameter} filled in."""
    return re.sub(r"\{[^}]+\}", "1", path)


def wrong_headers(token: str) -> dict[str, dict[str, str]]:
    """Ways to send no token, or not the right one. Several hold the right token in
    the wrong form, so a refusal that echoed the header would show it."""
    return {
        "none": {},
        "empty": {"Authorization": ""},
        "scheme only": {"Authorization": "Bearer"},
        "wrong token": {"Authorization": "Bearer WRONG-TOKEN-SENT"},
        "token one character short": {"Authorization": f"Bearer {token[:-1]}"},
        "token in upper case": {"Authorization": f"Bearer {token.upper()}"},
        "token without a scheme": {"Authorization": token},
        "token with another scheme": {"Authorization": f"Basic {token}"},
        "token in another header": {"X-Api-Key": token},
    }


WRONG = list(wrong_headers("x"))


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()


# ---- the walk finds the routes ----


def test_the_walk_finds_every_route_of_the_app() -> None:
    # Positive control: the parametrized tests below cover what this lists.
    assert ("POST", "/feedback") in PROTECTED
    assert set(routes_of(app)) == PUBLIC | set(PROTECTED)
    assert PROTECTED, "no protected route found: the walk is broken"


# ---- every route but /health needs the token ----


@pytest.mark.parametrize("way", WRONG)
@pytest.mark.parametrize("route", PROTECTED, ids=" ".join)
def test_every_protected_route_refuses_a_missing_or_wrong_token(
    client: TestClient, test_token: str, route: tuple[str, str], way: str
) -> None:
    method, path = route
    sent = wrong_headers(test_token)[way]

    response = client.request(method, concrete(path), json={}, headers=sent)

    assert response.status_code == 401
    assert response.json() == {"detail": NOT_AUTHENTICATED}
    assert response.headers["WWW-Authenticate"] == "Bearer"
    shown = response.text + str(response.headers)
    assert test_token not in shown
    assert test_token.upper() not in shown
    assert "WRONG-TOKEN-SENT" not in shown


@pytest.mark.parametrize("way", ["right token", *WRONG])
def test_health_answers_without_any_token(
    client: TestClient, test_token: str, way: str
) -> None:
    sent = (
        {"Authorization": f"Bearer {test_token}"}
        if way == "right token"
        else wrong_headers(test_token)[way]
    )

    response = client.get("/health", headers=sent)

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert test_token not in response.text


# ---- no answer holds the token or the Gemini key ----


@pytest.fixture
def gemini_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GEMINI_API_KEY", GEMINI_KEY)
    monkeypatch.setenv("GEMINI_MODEL", "fixture-model")
    config.get_settings.cache_clear()


def play(monkeypatch: pytest.MonkeyPatch, *names: str) -> list[list[httpx.Request]]:
    """Gemini answers each request's calls with these fixtures. The provider holds
    the configured key, as the real one does, so a test can look for it. Returns, per
    request, the calls Gemini received (filled in as they happen)."""
    calls: list[list[httpx.Request]] = []

    def build(settings: config.Settings) -> api.GeminiProvider:
        assert settings.gemini_api_key is not None
        provider, seen = gemini_playing(
            *names, key=settings.gemini_api_key.get_secret_value()
        )
        calls.append(seen)
        return provider

    monkeypatch.setattr(api, "gemini_from_settings", build)
    return calls


@pytest.mark.parametrize(
    ("script", "body", "status"),
    [
        (["hint"], SAMPLE_REQUEST.model_dump(mode="json"), 200),
        (["hint"], {"hintLevel": 9}, 422),
        (
            ["error_503_unavailable"] * 2,
            SAMPLE_REQUEST.model_dump(mode="json"),
            503,
        ),
        (["error_400_invalid_argument"], SAMPLE_REQUEST.model_dump(mode="json"), 503),
        (["cut_off_max_tokens"] * 2, SAMPLE_REQUEST.model_dump(mode="json"), 502),
    ],
    ids=["hint", "invalid body", "gemini 503", "gemini 400", "bad answers"],
)
def test_no_answer_holds_the_token_or_the_gemini_key(
    client: TestClient,
    test_token: str,
    monkeypatch: pytest.MonkeyPatch,
    gemini_configured: None,
    script: list[str],
    body: dict[str, object],
    status: int,
) -> None:
    calls = play(monkeypatch, *script)

    response = client.post(
        "/feedback", json=body, headers={"Authorization": f"Bearer {test_token}"}
    )

    assert response.status_code == status
    shown = response.text + str(response.headers)
    assert test_token not in shown
    assert GEMINI_KEY not in shown
    # The key really was in play: Gemini, and only Gemini, received it.
    for call in (c for request in calls for c in request):
        assert call.headers["x-goog-api-key"] == GEMINI_KEY


def test_without_gemini_settings_the_answer_names_no_secret(
    client: TestClient, test_token: str
) -> None:
    response = client.post(
        "/feedback",
        json=SAMPLE_REQUEST.model_dump(mode="json"),
        headers={"Authorization": f"Bearer {test_token}"},
    )

    assert response.status_code == 503
    assert test_token not in response.text


# ---- no log line holds them, through a real server ----


class Grab(logging.Handler):
    """Keeps every record of every logger, formatted with its traceback."""

    def __init__(self) -> None:
        super().__init__(logging.DEBUG)
        self.setFormatter(logging.Formatter("%(name)s %(levelname)s %(message)s"))
        self.lines: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.lines.append(self.format(record))


@pytest.fixture
def grab() -> Iterator[Grab]:
    root = logging.getLogger()
    handler, level = Grab(), root.level
    root.addHandler(handler)
    root.setLevel(logging.DEBUG)
    try:
        yield handler
    finally:
        root.removeHandler(handler)
        root.setLevel(level)


@pytest.fixture
def server(gemini_configured: None) -> Iterator[str]:
    """The real app on a real uvicorn, logging at DEBUG into the root logger."""
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    running = uvicorn.Server(
        uvicorn.Config(
            app, host="127.0.0.1", port=port, log_level="debug", log_config=None
        )
    )
    thread = threading.Thread(target=running.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 10
    while not running.started:
        assert time.monotonic() < deadline, "uvicorn did not start"
        time.sleep(0.02)
    try:
        yield f"http://127.0.0.1:{port}"
    finally:
        running.should_exit = True
        thread.join(10)


def test_no_log_line_holds_the_token_or_the_gemini_key(
    grab: Grab, server: str, test_token: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    right = {"Authorization": f"Bearer {test_token}"}
    body = SAMPLE_REQUEST.model_dump(mode="json")

    def explode(*_: object) -> None:
        raise RuntimeError("unexpected failure inside the endpoint")

    with httpx.Client(base_url=server, timeout=10) as http:
        for headers in wrong_headers(test_token).values():
            for method, path in PROTECTED:
                http.request(method, concrete(path), json={}, headers=headers)
        play(monkeypatch, "hint")
        assert http.post("/feedback", json=body, headers=right).status_code == 200
        play(monkeypatch, "error_503_unavailable", "error_503_unavailable")
        assert http.post("/feedback", json=body, headers=right).status_code == 503
        assert http.post("/feedback", json={}, headers=right).status_code == 422
        monkeypatch.setattr(api, "generate_feedback", explode)
        assert http.post("/feedback", json=body, headers=right).status_code == 500

    log = "\n".join(grab.lines)
    # The capture works: the requests and the unexpected error are in it.
    assert '"POST /feedback HTTP/1.1" 401' in log
    assert '"POST /feedback HTTP/1.1" 200' in log
    assert "RuntimeError: unexpected failure inside the endpoint" in log
    # And no secret is.
    assert test_token not in log
    assert test_token[:-1] not in log
    assert test_token.upper() not in log
    assert GEMINI_KEY not in log
