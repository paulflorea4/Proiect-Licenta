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
- On the classpath: Web, Security, Validation, Data JPA, Flyway (`spring-boot-starter-flyway` plus `flyway-database-postgresql` — in Spring Boot 4 bare `flyway-core` does not autoconfigure) and the PostgreSQL driver.
- Migrations live in `src/main/resources/db/migration` (plain SQL, Flyway, run on startup; files named `V<n>__<snake_case_description>.sql`, one per task row). Hibernate runs with `ddl-auto=validate`, so it never changes the schema. Dev-only seed data goes in a separate Flyway location enabled only by the `dev` profile; that location does not exist yet.
- Schema conventions (set in 1.2a, reuse in every later migration): `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, enum-like values (e.g. `role`) are plain `VARCHAR` validated in the application layer, no DB `CHECK`. Migration tests (`*MigrationTests`) run against Testcontainers inside a `@Transactional` test; Postgres aborts the transaction on the first constraint error, so use one test per violation.
- No `spring.datasource.*` keys in `application.properties`: the `SPRING_DATASOURCE_*` environment variables bind directly and have no default, so the app fails at startup if they are missing. Tests never use them.
- Configuration comes from environment variables listed in `backend/.env.example` (datasource, `JWT_SECRET`, `AI_SERVICE_URL`, `AI_SERVICE_TOKEN`, `CORS_ALLOWED_ORIGINS`). Never commit `backend/.env`, and never log secrets.

## Conventions
- No package-structure or naming convention exists yet beyond the base package — don't invent one. Each is decided by the task that introduces the first class of its kind (a choice with no default in any `*.md` file is a gate).
- Backend-relevant rules fixed in the root `CLAUDE.md` Defaults: schema changes only through Flyway; Spring Security + JWT, no OAuth; student code runs only in a sandbox container started through a `SandboxRunner` interface (`docker` CLI via `ProcessBuilder`); calls to the AI service are never made inside an open database transaction and never decide the grade.
- Spotless owns formatting: don't hand-format or fight it — run `spotless:apply`.
- Tests are part of the commit that adds the code, and all tests of all three projects must pass before a task counts as finished (root `CLAUDE.md`, Workflow).
- Tests get their database only from Testcontainers (`postgres:16`, matching `infra/docker-compose.yml`) — never the compose database or a CI service container. A `@SpringBootTest` imports `TestcontainersConfiguration` (`@ServiceConnection`); see `FlywayMigrationTests` for the pattern. This is a small first version; 2.7a turns it into the shared test base.
- Docker must be running for the backend tests (Testcontainers) and for the sandbox tests (from Phase 4). On Windows start Docker Desktop first, or `./mvnw verify` fails before running any test.
