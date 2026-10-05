# backend/CLAUDE.md

Spring Boot 4.1.1, Java 17, Maven. Project-wide rules (gates, defaults, Git safety, PR flow) are in the root `CLAUDE.md`; this file only holds what is specific to `/backend`.

## Commands (run from `/backend`)
- Build + test: `./mvnw -B verify` (`mvnw.cmd -B verify` on Windows). This is also what CI runs (`.github/workflows/backend-ci.yml`).
- Tests only: `./mvnw -B test`
- Run: `./mvnw spring-boot:run` — real OS environment variables are read directly (no dotenv library), so load `backend/.env` into the shell or the IDE run configuration first. See the root README, "Environment variables".
- Format: `./mvnw spotless:apply` (palantir-java-format, 4-space). `spotless:check` is bound to the `verify` phase, so `./mvnw -B verify` (and CI) fails on unformatted code — run `spotless:apply` before committing.
- Use the Maven wrapper, never a system Maven — it pins the version.
- Spring profiles: `dev` for local development, `prod` for deployment (root README). Nothing profile-specific exists yet; `application.properties` only sets the application name.

## Layout
- Base package: `com.gradingplatform.backend`. Tests mirror it under `src/test/java`.
- Currently only Web, Security and Validation are on the classpath. Data JPA, the PostgreSQL driver and Flyway arrive in task 1.1a, together with the first Testcontainers test.
- Migrations will live in `src/main/resources/db/migration` (plain SQL, Flyway, run on startup); dev-only seed data goes in a separate Flyway location enabled only by the `dev` profile. Neither directory exists yet.
- Configuration comes from environment variables listed in `backend/.env.example` (datasource, `JWT_SECRET`, `AI_SERVICE_URL`, `AI_SERVICE_TOKEN`, `CORS_ALLOWED_ORIGINS`). Never commit `backend/.env`, and never log secrets.

## Conventions
- No package-structure or naming convention exists yet beyond the base package — don't invent one. Each is decided by the task that introduces the first class of its kind (a choice with no default in any `*.md` file is a gate).
- Backend-relevant rules fixed in the root `CLAUDE.md` Defaults: schema changes only through Flyway; Spring Security + JWT, no OAuth; student code runs only in a sandbox container started through a `SandboxRunner` interface (`docker` CLI via `ProcessBuilder`); calls to the AI service are never made inside an open database transaction and never decide the grade.
- Spotless owns formatting: don't hand-format or fight it — run `spotless:apply`.
- Tests are part of the commit that adds the code, and all tests of all three projects must pass before a task counts as finished (root `CLAUDE.md`, Workflow).
- Docker must be running for Testcontainers-backed tests (from 1.1a) and the sandbox tests (from Phase 4).
