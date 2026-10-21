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
