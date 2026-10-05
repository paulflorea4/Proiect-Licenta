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
- Config will be loaded from `.env` through `pydantic-settings` (task 6.1b) — not added yet. The variables are listed in `ai-service/.env.example`: `GEMINI_API_KEY`, `GEMINI_MODEL`, `AI_SERVICE_TOKEN`. Never commit `ai-service/.env`.
- Code style is enforced by ruff + black (type hints on function signatures, as in the existing code); the layout grows with the tasks that add modules — don't pre-create empty packages.

## Conventions
- This service never connects to the database (root `CLAUDE.md`, Table ownership) and never executes student code (`docs/project-specifications.md`, Architecture). Everything it needs arrives in the request body.
- Every endpoint except `GET /health` requires the bearer token once auth is added (root `CLAUDE.md`, Defaults).
- Tests are part of the commit that adds the code, and all tests of all three projects must pass before a task counts as finished (root `CLAUDE.md`, Workflow).
- Rules fixed in the root `CLAUDE.md` Defaults that apply here once the code exists:
  - Gemini is called through a provider interface, and the model name comes from `GEMINI_MODEL` — never hardcode it.
  - Student-supplied text (code, comments, open answers) is untrusted data in every prompt: clearly delimited and quoted, never instructions. Hidden test inputs and expected outputs are never put in a prompt.
  - The bearer token is compared in constant time and never logged. The service is reachable only by the backend over a private network.
  - Plagiarism constants (k-gram size, window size, similarity threshold `0.70`) are named constants — don't inline them or tune them.
- New dependencies are a gate — ask before adding one.
