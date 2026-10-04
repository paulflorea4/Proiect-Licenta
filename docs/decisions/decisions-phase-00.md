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

## 0.2a — `infra/docker-compose.yml`

- **Credentials: no committed password.** Neither the spec nor any task fixes a database name, user or password, and 0.3a says real secret values come from the human. So `POSTGRES_PASSWORD` is *required* (`${POSTGRES_PASSWORD:?...}`, no default) and read from an untracked `infra/.env` (Compose loads it automatically from `/infra`); `infra/.env.example` is the placeholder template. `POSTGRES_DB` and `POSTGRES_USER` are not secrets and default to `grading`. Consequence: `docker compose up` fails with a clear message until `infra/.env` exists. **0.2b must add that step to the root README**, whose existing paragraph says only `docker compose up -d`. (Alternative if wanted: a dev-only default password — a one-line change.) The password and the other DB values must match what 0.3a puts in `backend/.env.example` / 1.1a's datasource settings.
- **Port bound to `127.0.0.1` only**, and **configurable via `POSTGRES_PORT`** (default 5432, so the README stays true). Found while testing: the dev machine already runs a native PostgreSQL on 5432, so the default failed to bind there (`ports are not available`). The native server was left alone; the test run used 5433. Anyone with the same clash sets `POSTGRES_PORT` in `infra/.env`, and the backend's datasource URL must use the same port.
- Plain `postgres:16` image, named volume `pgdata` at `/var/lib/postgresql/data`, `pg_isready` healthcheck (5s interval, 10 retries, 10s start period), `restart: unless-stopped`. Compose project name pinned to `grading-platform`.
- **Verified against a real daemon** (Docker Desktop, started for this task): reached `healthy`, PostgreSQL 16.15, a row survived `restart` and `down`/`up`, and `down -v` removed the data and the volume. Tested with a throwaway password; nothing left behind.

## 0.3a — `backend/.env.example`

- **GATE handled as written:** placeholders only. The three secrets (`SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`, `AI_SERVICE_TOKEN`) are empty; generating them is the human's job. `AI_SERVICE_TOKEN` must equal the value in `ai-service/.env` (0.3b).
- **DB variable names: Spring's own** (`SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`), not `DB_*`. The root README already says the backend relies on relaxed binding of real env vars, so these map straight to `spring.datasource.*` with no mapping code, and 1.1a / 2.1a can read them as-is. They are deliberately different from the `POSTGRES_*` names in `infra/.env` (those configure the container, these configure the client); the file's comment says the values must match.
- **Non-secret conventions are filled in, not left blank:** database URL `jdbc:postgresql://localhost:5432/grading` and username `grading` (the compose defaults from 0.2a — 2.1a says `dev` may fall back to them), `AI_SERVICE_URL=http://localhost:8000` (uvicorn's default port), `CORS_ALLOWED_ORIGINS=http://localhost:5173` (Vite's default dev port), comma-separated. If `POSTGRES_PORT` is changed in `infra/.env`, the URL port here must change too.
