# Grading Platform

Grading Platform is a web application that grades programming assignments automatically and gives students AI-generated feedback. Manual grading does not scale: teachers spend hours running submissions, students wait days for feedback, and the feedback is often just a number. Open-source tools mostly stop at test results and similarity reports, while commercial tools that add rubrics and AI assistants are priced per student. This platform combines deterministic grading (sandboxed test execution weighted by a rubric) with AI feedback that explains why a submission fails and how to improve, without giving away the solution. Built as a Bachelor's thesis; initial languages are Java and Python.

Teachers create courses and assignments (description, language, public and hidden test cases, deadline, rubric). Students enroll, submit code, and see which tests passed, a rubric-weighted score, and progressively stronger hints on request.

## Architecture

The system is split into three independent services; only the backend talks to the database:

- **`/backend`** — Java Spring Boot. User-facing REST API: authentication and role-based access (student / teacher / admin), courses, assignments, submissions, sandboxed execution of submitted code in Docker, rubric scoring, teacher dashboard, real-time submission status.
- **`/ai-service`** — Python FastAPI. Stateless AI and analysis service called by the backend: hint/feedback generation, test-case proposals, open-answer grading, plagiarism similarity (winnowing), static analysis.
- **`/frontend`** — React + TypeScript. Single-page application, talks only to the Spring Boot API.
- **`/infra`** — Local development infrastructure (database, sandbox images, etc.).

Database: PostgreSQL.

Schema migrations are owned exclusively by `/backend`, via Flyway (`backend/src/main/resources/db/migration`), run automatically on Spring Boot startup. The AI service never connects to the database and never runs migrations of its own — it only receives data in HTTP requests and returns results, and the backend persists them. This keeps the services from drifting the schema independently.

See `docs/project-specifications.md` for the full specification.

## Getting started

Bring up the local Postgres database from `/infra`: copy `.env.example` to `.env`, set `POSTGRES_PASSWORD` (the only required value; also set `POSTGRES_PORT` if another Postgres on your machine already uses 5432), then run `docker compose up -d`. It listens on `localhost:5432` (or your `POSTGRES_PORT`) and persists data in a named Docker volume, so it survives container restarts until you explicitly run `docker compose down -v`.

Docker must also be installed and running on whatever machine runs the backend, because from Phase 4 on the backend starts one short-lived, network-less sandbox container per run to execute submissions.

### Environment variables

Each service ships an `.env.example` (copy it to `.env` — `.env.local` for the frontend — and fill in real values; never commit the real file). Two values must agree across files: `AI_SERVICE_TOKEN` is the same secret in `backend/.env` and `ai-service/.env`, and the backend's `SPRING_DATASOURCE_*` values must match the database in `infra/.env`. How each service actually loads its file:

- **`/backend`** — Spring Boot reads real OS environment variables directly via its built-in relaxed binding (e.g. the `SPRING_DATASOURCE_URL` env var maps straight to the `spring.datasource.url` property, no extra library needed). Source `backend/.env` into your shell, or your IDE's run configuration, before running the app. Which `application-{profile}.yml` overrides apply is a separate concern, controlled via Spring profiles (`dev` for local development, `prod` for deployment).
- **`/ai-service`** — will load `.env` at startup through `pydantic-settings` (see `docs/tasks/tasks-phase-06.md`, 6.1b), added once the AI service has real code to configure.
- **`/frontend`** — Vite's built-in env handling: `.env` (and `.env.local`, etc.) at the project root is loaded automatically, and only `VITE_`-prefixed vars are exposed to client code via `import.meta.env`. Everything in these files ends up in the browser bundle, so never put a secret there.

## Development

Each service has its own `CLAUDE.md` with build, test, and lint commands:

- `backend/CLAUDE.md`
- `ai-service/CLAUDE.md`
- `frontend/CLAUDE.md`

Project-wide conventions and workflow live in the root `CLAUDE.md`.
