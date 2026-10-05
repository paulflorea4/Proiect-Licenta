# backend/CLAUDE.md

Spring Boot 4.1.1, Java 17, Maven. Project-wide rules (gates, defaults, Git safety, PR flow) are in the root `CLAUDE.md`; this file only holds what is specific to `/backend`.

## Commands (run from `/backend`)
- Build + test: `./mvnw -B verify` (`mvnw.cmd -B verify` on Windows). This is also what CI runs (`.github/workflows/backend-ci.yml`).
- Tests only: `./mvnw -B test`
- Run: `./mvnw spring-boot:run` — real OS environment variables are read directly (no dotenv library), so load `backend/.env` into the shell or the IDE run configuration first. See the root README, "Environment variables".
- Format: `./mvnw spotless:apply` (palantir-java-format, 4-space). `spotless:check` is bound to the `verify` phase, so `./mvnw -B verify` (and CI) fails on unformatted code � run `spotless:apply` before committing.
- Use the Maven wrapper, never a system Maven — it pins the version.

## Layout
- Base package: `com.gradingplatform.backend`. Tests mirror it under `src/test/java`.
- Currently only Web, Security and Validation are on the classpath. Data JPA, the PostgreSQL driver and Flyway arrive in task 1.1a, together with the first Testcontainers test.

## Conventions
- None beyond the root `CLAUDE.md` yet. Package structure and naming are decided in the tasks that introduce them (a choice with no default in any `*.md` file is a gate).
- Tests are part of the commit that adds the code, and all tests of all three projects must pass before a task counts as finished (root `CLAUDE.md`, Workflow).
- Docker must be running for Testcontainers-backed tests (from 1.1a) and the sandbox tests (from Phase 4).
