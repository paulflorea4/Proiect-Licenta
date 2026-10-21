import hmac

import pytest
from fastapi import FastAPI
from fastapi.routing import APIRoute
from fastapi.testclient import TestClient
from starlette.routing import BaseRoute

from ai_service import auth
from ai_service.auth import NOT_AUTHENTICATED, protected_router, require_bearer_token
from ai_service.main import app

PUBLIC = {("GET", "/health")}


@pytest.fixture
def probe() -> TestClient:
    """An app with one endpoint on a router made by protected_router(), as every
    endpoint the service adds will be; the real app has none yet."""
    router = protected_router()

    @router.post("/probe")
    def answer() -> dict[str, str]:
        return {"answer": "reached"}

    probe_app = FastAPI()
    probe_app.include_router(router)
    return TestClient(probe_app)


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---- the check ----


def test_the_right_token_reaches_the_endpoint(
    probe: TestClient, test_token: str
) -> None:
    response = probe.post("/probe", headers=bearer(test_token))

    assert response.status_code == 200
    assert response.json() == {"answer": "reached"}


def test_the_scheme_is_matched_ignoring_case(
    probe: TestClient, test_token: str
) -> None:
    for scheme in ("bearer", "BEARER", "BeArEr"):
        response = probe.post(
            "/probe", headers={"Authorization": f"{scheme} {test_token}"}
        )
        assert response.status_code == 200, scheme


def refused(
    probe: TestClient, headers: dict[str, str] | dict[str, str | bytes]
) -> dict[str, object]:
    response = probe.post("/probe", headers=headers)
    assert response.status_code == 401, headers
    assert response.headers["WWW-Authenticate"] == "Bearer"
    return response.json()


@pytest.mark.parametrize(
    "header",
    [
        None,
        "",
        "Bearer",
        "Bearer ",
        "Bearer wrong-token",
        "Basic {token}",
        "Token {token}",
        "{token}",
        "Bearer  {token}",
        "Bearer {token} ",
        "Bearer {token}x",
        "Bearer x{token}",
        "Bearer {upper}",
        "Bearer {prefix}",
        "Bearer tökén-with-non-ascii",
    ],
    ids=[
        "no header",
        "empty",
        "scheme only",
        "scheme and space",
        "wrong token",
        "basic scheme",
        "other scheme",
        "token without scheme",
        "two spaces",
        "trailing space",
        "token plus a character",
        "a character plus token",
        "token in upper case",
        "start of the token",
        "non-ascii",
    ],
)
def test_anything_but_the_exact_token_is_a_401(
    probe: TestClient, test_token: str, header: str | None
) -> None:
    headers: dict[str, str | bytes] = {}
    if header is not None:
        value = header.format(
            token=test_token,
            upper=test_token.upper(),
            prefix=test_token[: len(test_token) // 2],
        )
        # A non-ASCII value goes as raw UTF-8 bytes (the client refuses it as text).
        headers["Authorization"] = value if value.isascii() else value.encode()

    assert refused(probe, headers) == {"detail": NOT_AUTHENTICATED}


def test_every_refusal_is_the_same_and_shows_no_token(
    probe: TestClient, test_token: str
) -> None:
    bodies = {
        probe.post("/probe", headers=headers).text
        for headers in (
            {},
            bearer("wrong"),
            {"Authorization": f"Basic {test_token}"},
            bearer(test_token + "x"),
        )
    }

    assert len(bodies) == 1
    (body,) = bodies
    assert test_token not in body
    assert "wrong" not in body


def test_the_token_is_compared_with_hmac_compare_digest(
    probe: TestClient, test_token: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    compared: list[tuple[bytes, bytes]] = []
    original = hmac.compare_digest

    def spy(a: bytes, b: bytes) -> bool:
        compared.append((a, b))
        return original(a, b)

    monkeypatch.setattr(auth.hmac, "compare_digest", spy)

    assert probe.post("/probe", headers=bearer("wrong")).status_code == 401
    assert probe.post("/probe", headers=bearer(test_token)).status_code == 200
    assert compared == [
        (b"wrong", test_token.encode()),
        (test_token.encode(), test_token.encode()),
    ]


def test_the_token_is_the_configured_one(
    probe: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("AI_SERVICE_TOKEN", "another-configured-token")

    assert (
        probe.post("/probe", headers=bearer("another-configured-token")).status_code
        == 200
    )
    assert (
        probe.post(
            "/probe", headers=bearer("test-token-for-the-ai-service")
        ).status_code
        == 401
    )


# ---- /health stays open ----


@pytest.mark.parametrize(
    "headers",
    [{}, {"Authorization": "Bearer wrong"}, {"Authorization": "garbage"}],
    ids=["no header", "wrong token", "malformed"],
)
def test_health_needs_no_token(headers: dict[str, str]) -> None:
    with TestClient(app) as client:
        response = client.get("/health", headers=headers)

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


# ---- no route can forget the check ----


def unprotected(routes: list[BaseRoute]) -> set[tuple[str, str]]:
    """The (method, path) pairs that do not require the bearer token."""
    found: set[tuple[str, str]] = set()
    for route in routes:
        if not isinstance(route, APIRoute):
            # A plain Starlette route (FastAPI's /docs, a mount) has no dependencies.
            for method in getattr(route, "methods", None) or {"*"}:
                found.add((method, getattr(route, "path", repr(route))))
            continue
        checks = {dependency.dependency for dependency in route.dependencies}
        if require_bearer_token not in checks:
            for method in route.methods:
                found.add((method, route.path))
    return found


def test_every_route_of_the_app_but_health_requires_the_token() -> None:
    assert unprotected(app.routes) == PUBLIC


def test_the_documentation_routes_are_not_served() -> None:
    client = TestClient(app)

    for path in ("/docs", "/redoc", "/openapi.json", "/docs/oauth2-redirect"):
        assert client.get(path).status_code == 404, path


def test_the_route_guard_sees_a_route_without_the_check() -> None:
    # Positive control for the guard above: an endpoint added straight on an app,
    # and FastAPI's own documentation routes, are reported.
    careless = FastAPI()

    @careless.post("/forgot")
    def forgot() -> None:
        return None

    covered = protected_router()

    @covered.post("/covered")
    def fine() -> None:
        return None

    careless.include_router(covered)

    assert unprotected(careless.routes) >= {("POST", "/forgot"), ("GET", "/docs")}
    assert ("POST", "/covered") not in unprotected(careless.routes)
