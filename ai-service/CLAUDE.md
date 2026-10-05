# ai-service/CLAUDE.md

Python FastAPI service, managed with `uv` (build backend: `hatchling`). Project-wide rules (gates, defaults, Git safety, PR flow) are in the root `CLAUDE.md`; this file only holds what is specific to `/ai-service`.

## Commands (run from `/ai-service`)
- Install dependencies: `uv sync`
- Tests: `uv run pytest` (test paths are configured in `pyproject.toml`)
- Run: `uv run uvicorn ai_service.main:app --reload`
- Lint: `uv run ruff check .` (add `--fix` for auto-fixable findings). Format: `uv run black .` (`--check` to verify only). Both run in CI (`.github/workflows/ai-service-ci.yml`); config is in `pyproject.toml`. ruff only lints — black is the formatter, so don't enable `ruff format`.
- Always go through `uv run` — don't call a global `python` or `pytest`.
- `requires-python` is `>=3.12`, and CI runs on 3.12. A newer local interpreter works, but don't rely on features newer than 3.12.

## Layout
- `src/ai_service/` — the package (`main.py` holds the FastAPI `app`; only `GET /health` exists so far).
- `tests/` — pytest tests, using FastAPI's `TestClient` (needs `httpx`, already a dev dependency).
- Config will be loaded from `.env` through `pydantic-settings` (task 6.1b) — not added yet.

## Conventions
- This service never connects to the database (root `CLAUDE.md`, Table ownership) and never executes student code (`docs/project-specifications.md`, Architecture). Everything it needs arrives in the request body.
- Every endpoint except `GET /health` requires the bearer token once auth is added (root `CLAUDE.md`, Defaults).
- Tests are part of the commit that adds the code, and all tests of all three projects must pass before a task counts as finished (root `CLAUDE.md`, Workflow).
- New dependencies are a gate — ask before adding one.
