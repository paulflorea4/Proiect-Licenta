# Decisions / Assumptions — Phase 6 (`docs/tasks/tasks-phase-06.md`)

Historical, append-only. One entry per commit where a non-obvious call was made — a gap in the task spec, a gate resolution, a formatter/tool choice, a bug found and fixed. Not read every session — see the note in root `CLAUDE.md`. Load this only when auditing a specific Phase 6 decision.

## 6.1a — FastAPI app skeleton, `GET /health`

- **Already delivered by 0.1c, closed without code (gate, the human's decision).** `ai-service/src/ai_service/main.py` holds the FastAPI `app` with `GET /health` answering `{"status": "ok"}`, and `tests/test_health.py` checks it. The row's wording conflicted with the repo state, which the root `CLAUDE.md` makes a gate. The options offered were: mark it Done with no code (chosen), tighten the skeleton (an app factory for the 6.1b/6.1c tests), or change the answer to the backend's `{"status": "UP"}`.
- **Left as it is, for the tasks that own it:**
  - `/health` keeps `{"status": "ok"}`; the backend's says `UP`. If the hosting health checks (16.8a) want one shape, that task decides.
  - No app factory yet. If 6.1b's settings or 6.1c's token need the app built per test, those commits add it.
- **Tests:** none added or changed. All three projects were run and pass (backend 2226, ai-service 1, frontend 3; lint and format clean).

## 6.1b — `pydantic-settings` config class

- **New dependency `pydantic-settings` (gate, approved by the human).** Offered against a hand-written class over `os.environ`, which would not read `ai-service/.env` itself. It brings `python-dotenv` as its only new package. Added without a version bound in `pyproject.toml`, like `fastapi` and `uvicorn` (`uv add` wrote `>=2.16.1`, removed to match); `uv.lock` pins 2.16.1 and 1.2.4.
- **Only `AI_SERVICE_TOKEN` is required now.** The row says the service refuses to start without it. `.env.example` says the API key is only needed once the service calls the real API (6.2a), and the model is not used before then either, so both are optional (`None`) until 6.2a, which will refuse to build the Gemini provider without them. The model has no default anywhere, as the root `CLAUDE.md` asks.
- **Blank is empty.** A token of spaces is refused like an empty one; a blank Gemini value is `None`. A token is not trimmed or otherwise changed (the backend compares it as it is), and no minimum length is enforced: none is written anywhere, and `.env.example` already asks for a long random value. Left to the human if wanted.
- **"Refuses to start" is the app's `lifespan`,** which calls `get_settings()` before serving: uvicorn prints the validation error (the variable's name and "must not be empty", never a value) and exits with code 3 (checked by hand with a real uvicorn start). Reading the settings at import time instead would have made `import ai_service.main` fail in every test without a token.
- **The token and the API key are `SecretStr`,** so the settings print as `**********` in a log line, a `repr` or a dump (the root `CLAUDE.md`: the token is never logged).
- **The `.env` path is found from the package's own location** (`DEFAULT_ENV_FILE`), not the working directory, so the service reads `ai-service/.env` however it is started. Real environment variables win over the file (pydantic-settings' order); unknown variables in the file are ignored, so one shared file with other services' variables would not stop it.
- **Tests never read the developer's own `.env`:** `tests/conftest.py` sets `config.ENV_FILE` to `None`, a test token and no Gemini values for every test, and clears the `get_settings` cache around each. A test that wants a file writes one in `tmp_path`.
- **Tests (+17, ai-service 1 -> 18; backend 2226 and frontend 3 unchanged and passing):** the three values from the environment and from a file; the environment wins; the default file is `ai-service/.env`; the settings are exactly the variables of `.env.example`; unknown variables ignored; a missing, empty or blank token refused (also the unfilled copy of `.env.example`); the app does not start without a token and answers `/health` with one; Gemini values optional, blank = unset; no secret in `str`, `repr` or a dump; settings read once.
- **Mutation-checked, each failing at least one test:** no blank check (3 failures); the token a plain `str` (4); the settings not read at startup (1); the env file never read (1).
- **Existing warning, not from this change:** pytest reports Starlette's `StarletteDeprecationWarning` about `httpx` in its test client (it was there before, with 1 test). Not acted on.

## 6.1c — Bearer-token authentication

- **"At the router level" = `protected_router()`.** It returns an `APIRouter` whose `dependencies` hold `require_bearer_token`, so every endpoint declared on it is checked and none can opt out. Every module that adds endpoints (`POST /feedback` in 6.5a first) creates its router with it and `main.py` includes it. A dependency on the whole app was the other way, but it would have had to skip `/health` by path inside the check; with routers, `/health` simply is not on one.
- **What makes "can't forget" true is the guard test,** as for the backend's matrices: `test_every_route_of_the_app_but_health_requires_the_token` lists every route of the real app and fails for any but `GET /health` that lacks the check (an endpoint put straight on `app`, a router built with `APIRouter()`, a mounted app). A positive control shows the guard does see such routes.
- **FastAPI's `/docs`, `/redoc`, `/openapi.json` (and `/docs/oauth2-redirect`) are switched off.** They were served without a token, and the row says every route except `GET /health` requires one. Protecting them instead would mean replacing FastAPI's built-in routes with our own; the service is internal (only the backend calls it) and its contract is its pydantic models and tests, so they are off. Turning them back on behind the token is a small change if the human wants them.
- **The comparison:** `hmac.compare_digest` on the UTF-8 bytes of the sent and the configured token. On `str` it raises `TypeError` for non-ASCII characters, which would make such a header a 500; as bytes it is a 401 (tested by sending raw UTF-8). It runs whatever the scheme, so a wrong scheme takes the same path. Only the token's length can still show in the timing, which `compare_digest` does not hide; with a long random token that tells nothing useful.
- **One refusal for everything:** a missing header, an empty one, another scheme, a token without a scheme, extra spaces, a near miss and a wrong token all get the same 401 body `{"detail": "Not authenticated"}` and `WWW-Authenticate: Bearer` (RFC 6750), and the body never holds what was sent. The scheme is matched ignoring case (RFC 7235: scheme names are case-insensitive); the token exactly. `{"detail": ...}` is FastAPI's own error shape: the backend's `{status, code, message}` is its public contract, and this service's only caller is the backend's own client (Phase 7), which will map any non-2xx to its own error.
- **Nothing is logged** by the check, so the token cannot be.
- **The token is read through `get_settings()` on each request** (cached since 6.1b), so a test's `monkeypatch` of `AI_SERVICE_TOKEN` is seen once the cache is cleared, as the conftest does.
- **There is no protected endpoint yet,** so the tests build a probe app with one endpoint on a router from `protected_router()`: the same function, check and settings the real endpoints will use.
- **Tests (+26, ai-service 18 -> 44; backend 2226 and frontend 3 unchanged and passing):**
  - the right token reaches the endpoint, with the scheme in any case;
  - 15 wrong headers are each a 401 with the fixed body and `WWW-Authenticate`: none, empty, scheme only, scheme and space, wrong token, Basic, another scheme, token without scheme, two spaces, trailing space, one character more at either end, upper case, half the token, non-ASCII;
  - every refusal is byte-identical and shows no token;
  - `compare_digest` is what compares (spied, with the exact bytes), and the configured token is the one checked;
  - `/health` answers 200 without a header, with a wrong one and with a malformed one;
  - every route of the app but `/health` is checked, the documentation routes are 404, and the guard sees an unchecked route (positive control).
- **Mutation-checked, each failing at least one test:** the router without the check (17 failures); the scheme not checked (3); `==` instead of `compare_digest` (1); the documentation routes served again (2); `str` instead of bytes (2: the non-ASCII header becomes a 500).
- Also checked by hand on a real uvicorn: `/health` 200 without a token, `/docs` and `/openapi.json` 404, the token not in the log.
