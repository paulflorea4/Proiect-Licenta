# tasks-phase-00.md — Scaffolding & Environment

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 0.1a | Repo skeleton: confirm the root README.md, add a combined .gitignore (Java+Python+Node) | Done | First commit of the whole project. Nothing else depends on content here, only on the directories existing. No LICENSE file unless the human asks for one. The root README.md already exists from the docs set — keep it, do not regenerate it. The `.gitignore` must ignore real `.env` / `.env.local` files but not `.env.example`. |
| 0.1b | `/backend`: generate Spring Boot starter (Web, Security, Validation) | Done | Use Spring Initializr defaults and Maven. No custom code yet — this commit is the unmodified generated project. Data JPA and the PostgreSQL driver are deliberately left out: without a datasource the generated `contextLoads` test would fail, and "all tests pass" is a rule. They arrive in 1.1a together with the datasource settings and a test database. |
| 0.1c | `/ai-service`: Python skeleton (`pyproject.toml`, `src/` layout, `GET /health` only) | Done | Keep it to the bare FastAPI app. Record the package-manager/build-backend choice in `docs/decisions/decisions-phase-00.md`. |
| 0.1d | `/frontend`: `npm create vite@latest` React+TS skeleton | Done | Unmodified Vite output. |
| 0.1e | `/infra`: placeholder directory + short README describing what will live here | Done | Just the directory and its purpose (database now, sandbox images in Phase 4). |
| 0.1f | `/frontend`: test runner (Vitest + Testing Library + jsdom), `npm test` script, smoke tests for the template `App` | Done | Added at the human's request, after 0.1d, so the frontend has tests before 0.4c's CI step. Template source is untouched; only `vite.config.ts` (test block), `package.json` and new test files change. |
| 0.2a | `infra/docker-compose.yml`: Postgres 16, named volume, healthcheck | Done | Plain `postgres:16` image — no vector extension needed in this project. |
| 0.2b | Document `docker compose up` in the root README | Done | One paragraph: how to bring up the local DB, nothing else yet. |
| 0.2c | Document Docker as a prerequisite for running submissions in the root README | Done | The backend starts sandbox containers from Phase 4 on, so the Docker daemon must be running on whatever machine runs the backend. One sentence, no setup steps yet. |
| 0.3a | `backend/.env.example`: `JWT_SECRET`, DB connection vars, `AI_SERVICE_URL`, `AI_SERVICE_TOKEN`, `CORS_ALLOWED_ORIGINS` — no real values | Done | **GATE**: real secret values come from you, not this commit. Placeholders only. |
| 0.3b | `ai-service/.env.example`: `GEMINI_API_KEY`, `GEMINI_MODEL`, `AI_SERVICE_TOKEN` — no real values | Done | **GATE**: same as above, for the AI service's own key. No DB vars — this service never connects to the database. `AI_SERVICE_TOKEN` must hold the same value as in the backend's file; the human generates it (long random string). |
| 0.3c | `frontend/.env.example`: API base URL var | Done | No secrets here, just the convention. |
| 0.3d | Document the env-loading convention per service in the root README | Done | Spring profiles vs. a Python dotenv library vs. Vite's built-in env handling — write down which, so later phases don't reinvent it. |
| 0.4a | `.github/workflows/backend-ci.yml`: build + test on every PR | Done | Independent job so a Python or frontend failure never blocks a backend-only PR. GitHub-hosted Ubuntu runners include Docker, which Phase 4's sandbox tests will need. |
| 0.4b | `.github/workflows/ai-service-ci.yml`: lint + test on every PR | Done | Same independence reasoning as 0.4a. If no linter is configured yet (0.5b), run tests only and say so. |
| 0.4c | `.github/workflows/frontend-ci.yml`: lint + test + build on every PR | Done | Same independence reasoning as 0.4a. |
| 0.5a | `/backend`: Spotless config wired into the build | Done | If the human has no formatter preference on record, use palantir-java-format (4-space) and note the choice in decisions. |
| 0.5b | `/ai-service`: ruff + black config in `pyproject.toml`, lint step in `ai-service-ci.yml` | Done | Closes any lint gap left by 0.4b. |
| 0.5c | `/frontend`: ESLint + Prettier config | Done | Set Prettier `endOfLine` to `"auto"` — on Windows, `core.autocrlf=true` checks files out as CRLF and Prettier's default `lf` makes `format:check` fail on a clean checkout. |
| 0.6a | Review root `CLAUDE.md`: ground rules, gate policy, human-review-gate table, assumptions list | Done | Confirm the Defaults still match what Phase 0 actually produced (folder names, tools). |
| 0.6b | `backend/CLAUDE.md`: Java/Spring Boot build & test commands, package conventions | Done | Keep it short — commands and conventions only. |
| 0.6c | `ai-service/CLAUDE.md`: Python build/test/lint commands, project layout | Done | Keep it short — commands and conventions only. |
| 0.6d | `frontend/CLAUDE.md`: React build/test/lint commands, component conventions | Done | No real components yet, so the conventions section should say so rather than inventing one. |
