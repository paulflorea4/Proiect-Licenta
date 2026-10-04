# Decisions / Assumptions — Phase 0 (`docs/tasks/tasks-phase-00.md`)

Historical, append-only. One entry per commit where a non-obvious call was made — a gap in the task spec, a gate resolution, a formatter/tool choice, a bug found and fixed. Not read every session — see the note in root `CLAUDE.md`. Load this only when auditing past work or tracing why something is the way it is. Covers Phase 0 only — see the sibling `docs/decisions/decisions-phase-XX.md` files for other phases.

## 0.1c — `/ai-service` Python skeleton

- **Package manager: `uv`; build backend: `hatchling`.** The task left the choice to me, and no other `*.md` file sets one. `uv` was already installed on the dev machine, gives a reproducible `uv.lock`, and manages the venv, so no separate `requirements.txt` or `pip` + `venv` steps. `hatchling` is the build backend because it handles the `src/` layout with a one-line `[tool.hatch.build.targets.wheel]` setting. Run commands with `uv run ...`.
- **Dependencies added:** runtime `fastapi`, `uvicorn` (the task asks for the bare FastAPI app, which needs an ASGI server to run); dev group `pytest`, `httpx` (the workflow requires tests in the same commit, and FastAPI's `TestClient` requires `httpx`). Versions are left unpinned in `pyproject.toml` and pinned by `uv.lock`. `pydantic-settings` and the Gemini client are not added here — they belong to 6.1b / 6.2a.
- **Python version:** `requires-python = ">=3.12"` to match the project default. Only Python 3.14 is installed on the dev machine, so local tests run on 3.14; no `.python-version` pin was added, to avoid making `uv` download another interpreter. CI (0.4b) should run on 3.12.
- **Package name:** `ai_service` under `src/`, test in `tests/test_health.py`.

## 0.1f — `/frontend` test runner (not in the original task table)

- **Added at the human's request** ("I want tests for the frontend"), as a new row 0.1f so the one-commit-per-row rule still holds. It resolves the gap noted in `docs/suggestions.md` under 0.1d (no test runner for 0.4c's CI step).
- **Vitest** because the project is Vite-based: it reuses `vite.config.ts` and needs no separate transform setup. **jsdom** as the DOM environment, **@testing-library/react** + **@testing-library/dom** (peer dependency) + **@testing-library/jest-dom** + **@testing-library/user-event** for rendering and interaction. Dev dependencies only; versions pinned by `package-lock.json`.
- **Vitest globals are off**, so tests import `describe`/`it`/`expect` explicitly (no `tsconfig.app.json` change) and `src/test/setup.ts` registers Testing Library's `cleanup` itself.
- Tests live next to the code (`src/App.test.tsx`). They only cover the unmodified template `App` (heading, counter) — real component tests arrive with real components. The tests are type-checked by `tsc -b` and linted by oxlint.
- The oxlint-vs-ESLint question for 0.5c is unaffected and still open.
