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

## 0.3b — `ai-service/.env.example`

- **GATE handled as written:** placeholders only; all three variables (`GEMINI_API_KEY`, `GEMINI_MODEL`, `AI_SERVICE_TOKEN`) are empty. No DB variables.
- **`GEMINI_MODEL` is left empty on purpose**, not given a "sensible" model name: the root `CLAUDE.md` says the model name comes from this env var and is never hardcoded, and choosing a model is the human's call. The comment says there is no default anywhere in the code (6.1b should not add one).
- `AI_SERVICE_TOKEN` must be byte-for-byte the same as in `backend/.env` (0.3a); the file says so, and 6.1b makes the service refuse to start when it is empty.

## 0.3c — `frontend/.env.example`

- One variable, **`VITE_API_BASE_URL=http://localhost:8080`** (Spring Boot's default port, no trailing slash). The `VITE_` prefix is required: Vite only exposes prefixed variables to client code, as the root README says. 8.2a reads it from here.
- The file warns that everything in it ends up in the browser bundle, so it must never hold a secret — the frontend has none by design.
- Unlike the other two services, the file's header suggests copying to `.env.local` rather than `.env`: both are loaded by Vite, and `.env.local` is already covered by `frontend/.gitignore` (`*.local`) as well as the root `.gitignore`. The backend's CORS allow-list (`CORS_ALLOWED_ORIGINS`, 0.3a) defaults to the Vite dev origin `http://localhost:5173`.

## 0.4a — `.github/workflows/backend-ci.yml`

- **Triggers on every `pull_request`, no path filter.** The row says "on every PR"; a path filter would leave the check absent on non-backend PRs (and stuck as "expected" if it is ever made required). Independence from the other services comes from separate workflow files, not filters.
- **Command: `./mvnw -B verify`** in `/backend` (build + tests + package) — the Maven wrapper pins Maven 3.9.16, so no `setup-maven` step.
- **Java 17, Temurin** — the project's target version (`<java.version>17`). The dev machine has JDK 21, so this is the first run on the real target.
- **Actions used:** `actions/checkout@v7` and `actions/setup-java@v6` (with `cache: maven`), both GitHub first-party, referenced by major-version tag, not pinned to a commit SHA. `permissions: contents: read` only. These are the only external pieces CI pulls in; they are implied by the row ("build + test on every PR") but flagged in the PR since new external software is a gate in `CLAUDE.md`.
- `mvnw` was checked to be executable in git and LF-normalized (`.gitattributes`), which Linux runners need.
- Docker is already present on GitHub-hosted Ubuntu runners, so nothing is set up for it yet (Testcontainers from 1.1a, sandbox from Phase 4).

## 0.4b — `.github/workflows/ai-service-ci.yml`

- **Same shape as 0.4a:** triggers on every `pull_request`, no path filter, `permissions: contents: read`, its own workflow file for independence.
- **Tests only, no lint step.** The row says to run tests only if no linter is configured yet; ruff + black arrive in 0.5b, which adds the lint step to this file. A comment in the workflow says so.
- **uv installed with `pip install uv`, not `astral-sh/setup-uv`.** uv is already the project's package manager (`ai-service/CLAUDE.md`), so this adds no new tool, and avoiding the third-party action keeps the only external actions GitHub first-party (`actions/checkout@v7`, `actions/setup-python@v6`, by major tag like 0.4a). uv itself is not version-pinned in CI yet.
- **Python 3.12** to match `requires-python` and the project default; the dev machine runs a newer interpreter.
- **`uv sync --locked`** so CI fails if `uv.lock` is out of date instead of silently re-resolving; then `uv run pytest`. pip cache is keyed on `uv.lock`.
- Verified locally with `uv sync --locked` + `uv run pytest`; the workflow itself is first exercised by this PR's own check run.

## 0.4c — `.github/workflows/frontend-ci.yml`

- **Same shape as 0.4a/0.4b:** every `pull_request`, no path filter, `permissions: contents: read`, own workflow file.
- **Steps: `npm ci`, `npm run lint`, `npm test`, `npm run build`** — the existing scripts, so CI and local use cannot drift. `npm run lint` currently runs oxlint; the oxlint-vs-ESLint gate belongs to 0.5c and CI follows whatever the script does.
- **Node 24** — matches the dev machine (v24.19) and `@types/node` `^24`; satisfies Vite 8's minimum. npm cache keyed on `package-lock.json`.
- **Action added:** `actions/setup-node@v6` (GitHub first-party, major tag, like the other workflows).
- Verified locally with `npm run lint`, `npm test` and `npm run build`; the workflow itself is first exercised by this PR's check run.

## 0.5a — Spotless in `/backend`

- **Formatter: palantir-java-format, 4-space** — the row's default; no formatter preference is on record. `removeUnusedImports` is also enabled (no effect on current code).
- **Versions pinned:** `spotless-maven-plugin` 3.10.3 and palantir-java-format 2.102.0 (latest on Maven Central at the time). Spring Boot's parent POM does not manage either.
- **Wired in as `check` on the `verify` phase**, not `apply`: CI (`./mvnw -B verify`) fails on unformatted code instead of silently rewriting it, and the check runs after the tests. Developers run `./mvnw spotless:apply`; documented in `backend/CLAUDE.md`. Because it is in `verify`, no change to `backend-ci.yml` was needed.
- **Line endings:** Spotless's default (`GIT_ATTRIBUTES`) follows git's settings, so CRLF working copies on Windows (`core.autocrlf=true`) and LF on Linux CI both pass without a `.gitattributes` change. Verified locally on Windows; the Linux run is the PR's CI check.
- The two existing Java files were reformatted (tabs → 4 spaces, empty test body collapsed to `{}`); no behaviour change.

## 0.5b — ruff + black in `/ai-service`

- **Division of labour:** ruff lints, black formats (the row names both). ruff's own formatter is not enabled, so there is one formatter. Both use `line-length = 88` (black's default) so they never disagree on E501.
- **ruff rule set: `E, F, I, UP, B`** — pycodestyle errors, pyflakes, import sorting, pyupgrade (target py312), flake8-bugbear. A small, low-noise set; more rules can be added by a later task. `src = ["src", "tests"]` so isort classifies `ai_service` as first-party.
- **Added as dev dependencies** via `uv add --dev` (ruff 0.16.10, black 26.10.0, locked in `uv.lock`); the version floors in `pyproject.toml` are what uv wrote. They are the tools the row specifies.
- **CI:** `ai-service-ci.yml` now runs `ruff check .` and `black --check .` before the tests (lint failures show up fast); the "tests only" comment from 0.4b was removed.
- Existing code already passed both tools unchanged, so there is no reformatting in this commit.
- Line endings: black keeps whatever line ending a file already uses, so CRLF working copies on Windows and LF on Linux both pass.

## 0.5c — ESLint + Prettier in `/frontend`

- **Linter: ESLint replaces oxlint — the human's decision** (the row says ESLint, the template shipped oxlint; this was the gate raised in `docs/suggestions.md`). `oxlint` and `.oxlintrc.json` are removed; `npm run lint` is now `eslint .`, so the 0.4c CI step is unchanged.
- **ESLint 10 flat config** (`eslint.config.js`, the shape Vite's own template uses for ESLint): `@eslint/js` recommended, `typescript-eslint` recommended (non-type-checked — fast, and `tsc -b` already type-checks), `eslint-plugin-react-hooks` (`rules-of-hooks`, same as oxlint had) and `eslint-plugin-react-refresh` (Vite preset, same as oxlint's `only-export-components`), `globals.browser`. Only `*.ts`/`*.tsx` are linted; `dist` is ignored.
- **Prettier 3 with `eslint-config-prettier` last in the ESLint config**, so the two tools never disagree. ESLint does no formatting.
- **Prettier options:** `semi: false`, `singleQuote: true` — the template's existing style, so no source files were reformatted. `endOfLine: "auto"` as the row requires (CRLF checkouts on Windows). `.prettierignore`: `dist`, `node_modules`, `package-lock.json`.
- **Scripts:** `lint`, `format` (`prettier --write .`), `format:check` (`prettier --check .`). CI gets a Prettier check step between lint and test.
- Only `frontend/CLAUDE.md` needed reformatting (blank lines after headings), which Prettier also covers.
- **New dev dependencies** (the row asks for ESLint + Prettier; the plugins are what a working ESLint setup for React + TS needs): eslint, @eslint/js, typescript-eslint, eslint-plugin-react-hooks, eslint-plugin-react-refresh, globals, prettier, eslint-config-prettier. `typescript-eslint` supports TypeScript `<6.1`, which covers the project's `~6.0`.
- Verified locally: lint passes on the code and fails on a deliberate unused variable; `format:check`, build and tests pass.

## 0.6a — review of root `CLAUDE.md`

- **Reviewed against the repo after 0.1–0.5:** layout (`/backend`, `/ai-service`, `/frontend`, `/infra`), progress-file format, gates table, Git safety (hook and `.claude/settings.json` deny rules both exist and match the text), PR conventions and the per-service file list all match what Phase 0 produced. No rule was changed.
- **Two additions only, both descriptive of what now exists:** a Workflow bullet that lint/format checks count as part of "passing" (CI enforces them since 0.4–0.5), and a Defaults "Tooling" bullet naming each project's toolchain and the three CI workflows. The Defaults rows (auth, roles, sandbox, scoring, AI, …) concern later phases and were left untouched.
- Nothing was added about Flyway/Spring profiles/pydantic-settings yet: they do not exist in the repo until Phases 1 and 6.

## 0.6b — `backend/CLAUDE.md`

- **Kept the file from PR #16** (human's instruction: keep what exists, add what is necessary) and reviewed it against the repo; commands, layout and test notes were all accurate.
- **Fixed a bug of mine from 0.5a:** the Spotless line in this file contained a Windows-1252 em dash byte (invalid UTF-8, rendered as `�`) because the edit script ran without `encoding='utf-8'`. Replaced with a proper `—`. All other tracked docs were checked and are valid UTF-8.
- **Added only facts that already exist in the repo or the root `CLAUDE.md`:** Spring profiles (`dev`/`prod`, per the README) and that none are configured yet; where migrations and dev seed data will live (plus that the directories don't exist yet); the env var list from `backend/.env.example`; and a one-line pointer to the backend-relevant Defaults (Flyway only, Spring Security + JWT, `SandboxRunner`, no AI calls inside a transaction).
- **"Package conventions" (from the row): none invented.** Only the base package exists. The row asks for conventions, but a package layout has no default in any `*.md` file, so it is left to the task that adds the first class of each kind, as the file already said; the wording now makes that explicit.

## 0.6c — `ai-service/CLAUDE.md`

- **Kept the file from PR #16** (human's instruction) and checked it against the repo: commands (`uv sync`, `uv run pytest`, ruff/black from 0.5b, run command), layout and conventions were accurate. File is valid UTF-8.
- **Added only existing facts:** `uv.lock` is committed and CI uses `uv sync --locked` (so a dependency change must include the lock); the default uvicorn port matches `AI_SERVICE_URL`; the three env vars from `.env.example`; a note not to pre-create empty packages; and a short list of the root-Defaults rules that bind this service once code exists (provider interface + `GEMINI_MODEL`, untrusted student text in prompts, hidden tests never in prompts, constant-time unlogged token, named plagiarism constants incl. the `0.70` placeholder).
- **Project layout (from the row):** the file documents the real layout (`src/ai_service/main.py`, `tests/`). Nothing further was invented — modules arrive with their tasks.

## 0.6d — `frontend/CLAUDE.md`

- **Kept the file from PR #16** (human's instruction) and checked it against the repo; it already described the 0.5c ESLint/Prettier setup, the Vitest setup and the "no real components yet" conventions section the row asks for. Valid UTF-8, Prettier-clean.
- **Added only existing facts:** the dev server origin (`localhost:5173`, matches `CORS_ALLOWED_ORIGINS` in the backend `.env.example`); the TypeScript compiler options from `tsconfig.app.json` that affect how code must be written (`verbatimModuleSyntax`, `erasableSyntaxOnly`, unused-code errors) and that ESLint covers only `*.ts`/`*.tsx`; and the one `VITE_` variable that exists, `VITE_API_BASE_URL`.
- **Component conventions (from the row): none invented** — as the row says, there are no real components yet, so the section states that.
- This closes Phase 0's task list (0.6b–d were already created in PR #16; rows 0.6b–d are now each reviewed and marked Done in their own commits, which resolves the note in `docs/suggestions.md`).
