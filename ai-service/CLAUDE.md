# ai-service/CLAUDE.md

Python FastAPI service, managed with `uv` (build backend: `hatchling`). Project-wide rules (gates, defaults, Git safety, PR flow) are in the root `CLAUDE.md`; this file only holds what is specific to `/ai-service`.

## Commands (run from `/ai-service`)
- Install dependencies: `uv sync`
- Tests: `uv run pytest` (test paths are configured in `pyproject.toml`)
- Run: `uv run uvicorn ai_service.main:app --reload` (uvicorn's default port 8000 matches `AI_SERVICE_URL` in `backend/.env.example`)
- Lint: `uv run ruff check .` (add `--fix` for auto-fixable findings). Format: `uv run black .` (`--check` to verify only). Both run in CI (`.github/workflows/ai-service-ci.yml`); config is in `pyproject.toml`. ruff only lints — black is the formatter, so don't enable `ruff format`.
- Always go through `uv run` — don't call a global `python` or `pytest`.
- `uv.lock` is committed and CI runs `uv sync --locked`: after any dependency change, commit the updated lock file or CI fails.
- `requires-python` is `>=3.12`, and CI runs on 3.12. A newer local interpreter works, but don't rely on features newer than 3.12.

## Layout
- `src/ai_service/` — the package (`main.py` holds the FastAPI `app`; only `GET /health` exists so far).
- `tests/` — pytest tests, using FastAPI's `TestClient` (needs `httpx`, already a dev dependency).
- Settings (6.1b, `config.py`): `Settings` (`pydantic-settings`, added at 6.1b with the human's approval; it brings `python-dotenv`) reads exactly the variables of `ai-service/.env.example` from the environment and from `ai-service/.env` (`DEFAULT_ENV_FILE`, found from the package's location, so it works whatever the working directory; real environment variables win; unknown variables are ignored; no database settings). **`AI_SERVICE_TOKEN` is required**: missing, empty or blank stops the service at startup (the app's `lifespan` calls `get_settings()`; uvicorn prints the variable's name, never a value). `GEMINI_API_KEY` and `GEMINI_MODEL` are optional until 6.2a (blank = `None`; the model has no default anywhere). The token and the API key are `SecretStr`, so printing or logging the settings shows `**********`. Use `get_settings()` (read once, cached), never `Settings()` directly. Never commit `ai-service/.env`.
- Tests: `tests/conftest.py` gives every test the same settings whatever the developer's own `.env` holds (`config.ENV_FILE` set to `None`, a test token, no Gemini values, cache cleared around each test); change them with `monkeypatch` (the `test_token` fixture holds the token). `with TestClient(app)` runs the startup (and so the settings check); a plain `TestClient(app)` does not.
- Authentication (6.1c, `auth.py`): **every endpoint except `GET /health` requires `Authorization: Bearer <AI_SERVICE_TOKEN>`**. The check, `require_bearer_token`, is attached to routers, never to single endpoints: **create every new endpoint on a router made by `protected_router()`** and include it in `main.py`. The scheme is matched ignoring case, the token exactly, with `hmac.compare_digest` on bytes (a non-ASCII header is a 401, not a 500); missing, malformed and wrong all get the same 401 `{"detail": "Not authenticated"}` with `WWW-Authenticate: Bearer`, never echoing what was sent. FastAPI's `/docs`, `/redoc` and `/openapi.json` are switched off (they would be open). `tests/test_auth.py::test_every_route_of_the_app_but_health_requires_the_token` fails when any route of the app other than `GET /health` lacks the check (an endpoint added straight on `app`, a router made another way).
- Code style is enforced by ruff + black (type hints on function signatures, as in the existing code); the layout grows with the tasks that add modules — don't pre-create empty packages.

## Conventions
- This service never connects to the database (root `CLAUDE.md`, Table ownership) and never executes student code (`docs/project-specifications.md`, Architecture). Everything it needs arrives in the request body.
- Every endpoint except `GET /health` requires the bearer token (root `CLAUDE.md`, Defaults; 6.1c, see Layout).
- Tests are part of the commit that adds the code, and all tests of all three projects must pass before a task counts as finished (root `CLAUDE.md`, Workflow).
- Rules fixed in the root `CLAUDE.md` Defaults that apply here once the code exists:
  - Gemini is called through a provider interface, and the model name comes from `GEMINI_MODEL` — never hardcode it.
  - Student-supplied text (code, comments, open answers) is untrusted data in every prompt: clearly delimited and quoted, never instructions. Hidden test inputs and expected outputs are never put in a prompt.
  - The bearer token is compared in constant time and never logged. The service is reachable only by the backend over a private network.
  - Plagiarism constants (k-gram size, window size, similarity threshold `0.70`) are named constants — don't inline them or tune them.
- New dependencies are a gate — ask before adding one.
