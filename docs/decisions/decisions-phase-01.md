# Decisions / Assumptions — Phase 1 (`docs/tasks/tasks-phase-01.md`)

Historical, append-only. One entry per commit where a non-obvious call was made — a gap in the task spec, a gate resolution, a formatter/tool choice, a bug found and fixed. Not read every session — see the note in root `CLAUDE.md`. Load this only when auditing past work or tracing why something is the way it is. Covers Phase 0 only — see the sibling `docs/decisions/decisions-phase-XX.md` files for other phases.

## 1.1a — Data JPA, PostgreSQL driver, Flyway, Testcontainers in `/backend`

- **Dependencies (all named by the row, so no extra gate), versions managed by Spring Boot 4.1.1's BOM:** `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `flyway-database-postgresql` (runtime; Flyway 12 needs it for Postgres), `postgresql` (runtime); tests: `spring-boot-starter-data-jpa-test`, `spring-boot-starter-flyway-test`, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql` (Testcontainers 2.0.5; note the 2.x artifact/package names).
- **No `spring.datasource.*` in `application.properties`.** The row says to read the datasource from the env vars and put the settings in `application.properties` for now. I first wrote `${SPRING_DATASOURCE_URL}` placeholders, then found that a missing variable produced the confusing `'url' must start with "jdbc"` (Spring leaves unresolved placeholders as literal text). Relying on relaxed binding alone (as the README's env convention already says) fails with the clear "Failed to configure a DataSource: 'url' attribute is not specified" and needs no mapping. The file holds a comment explaining this; 2.1a replaces it with profiles anyway.
- **`spring.jpa.hibernate.ddl-auto=validate`:** enforces "schema changes through Flyway only" — Hibernate checks, never alters. No entities exist yet, so it is a no-op for now.
- **Test database:** `TestcontainersConfiguration` (`@ServiceConnection`, `postgres:16`, same major as `infra/docker-compose.yml`) imported by every `@SpringBootTest`, so tests never touch the compose DB or env vars. `BackendApplicationTests` now imports it (the context needs a datasource). Kept as small as the row asks; 2.7a generalises it.
- **Migration test:** `FlywayMigrationTests` asserts `flyway_schema_history` exists after startup. Verified it can fail: with `SPRING_FLYWAY_ENABLED=false` it fails ("Expecting value to be true but was false"). Flyway creates the history table even with zero migrations, so no placeholder migration was needed; `db/migration/.gitkeep` keeps the (empty) location in git until V1 in 1.2a.
- **Local requirement:** Docker must be running. Docker Desktop was stopped at the start of this session, so I started it (installed app, no config change).
- Backend CI needs no change: `./mvnw -B verify` already runs on a runner with Docker (0.4a).

## 1.1b — README: the AI service never touches the database

- **The README already said it** (one sentence inside the Flyway paragraph, from the original docs set). Rather than leave the row a no-op, the statement was promoted to its own bold paragraph right under "Database: PostgreSQL", and the duplicate sentence in the Flyway paragraph was trimmed to "Nothing else runs migrations."
- **Content is limited to facts that hold today or are fixed in the root `CLAUDE.md`:** no DB driver (`ai-service/pyproject.toml` has only `fastapi`/`uvicorn`), no DB variables in `ai-service/.env.example`, no migrations; data arrives in the request body and the backend persists results. The "why" restates the row's rationale plus the root rule that hidden test inputs/expected outputs never leave the backend.
- Docs-only change; no code touched. All three projects' tests were still run before marking the row Done.

## 1.2a — Migration V1: `users`

- **File:** `backend/src/main/resources/db/migration/V1__create_users.sql` (naming `V<n>__<snake_case>.sql` for all later migrations). The `.gitkeep` from 1.1a is removed now that the folder has a real file.
- **`id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`** — the row does not name an id type and no `*.md` file sets one. Picked the conventional default (not UUID): ids appear in URLs like `/admin/users/{id}` and JWT claims, are smaller and sort naturally. `GENERATED ALWAYS` (not `BY DEFAULT`) so application code can never insert its own id by accident. Every later table should follow this; flagged in the PR so the human can veto before 1.2b copies it.
- **Columns as per the row:** `email VARCHAR(255) NOT NULL UNIQUE`, `password_hash VARCHAR(255) NOT NULL`, `full_name VARCHAR(255) NOT NULL`, `role VARCHAR(20) NOT NULL` (no `CHECK`, per the row), `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. `NOT NULL` on `created_at` added on top of the row's `DEFAULT NOW()` — the default makes it always set, so the constraint just documents it.
- **Email uniqueness is case-sensitive at the DB level.** The row asks for a plain unique column. Signup/signin (2.x) should therefore lower-case/trim the email before saving; a `lower(email)` unique index is the stronger alternative if the human wants it enforced in the DB. Not done here, to stay within the row.
- **Tests (`UsersMigrationTests`, Testcontainers):** V1 recorded as successful in `flyway_schema_history`; an insert gets a generated id and `created_at`; duplicate email is rejected; each of email, password_hash, full_name and role is required. One test per `NOT NULL` column because Postgres aborts the surrounding `@Transactional` test transaction after the first error (my first version put all four in one test and failed with `current transaction is aborted`).

## 1.2b — Migration V2: `courses`

- **File:** `V2__create_courses.sql`. Same conventions as V1 (identity `BIGINT` id, `TIMESTAMPTZ NOT NULL DEFAULT NOW()` `created_at`).
- **Nullability gaps in the row, filled in:** `title` `NOT NULL`; `description` nullable `TEXT` (a course may have none; no length cap needed); `teacher_id` `NOT NULL REFERENCES users (id)` (a course without a teacher is meaningless); `enroll_code` `NOT NULL UNIQUE`.
- **`enroll_code VARCHAR(20)`:** the row only says unique and that generation is 3.1a ("short and unambiguous"). 20 is a generous ceiling so the generator can change length without a migration; the real length is 3.1a's call.
- **No `ON DELETE` clause (default `NO ACTION`):** nothing in the tasks deletes users or courses, so deleting a teacher who owns courses is refused rather than cascading away graded work. Revisit if a delete feature appears.
- **Explicit index `idx_courses_teacher_id`:** Postgres does not index foreign keys, and "a teacher's courses" is a core query. Not in the row; added as a small convention (recorded in `backend/CLAUDE.md`): FKs get an index unless already covered by a PK/unique constraint's leading column. Later migrations (1.2c onward) should follow it.
- **Tests (`CoursesMigrationTests`):** V2 applied; generated id and `created_at`; optional description; duplicate `enroll_code` rejected; unknown `teacher_id` rejected; `title`, `teacher_id`, `enroll_code` each required (one test per column, as in 1.2a).

## 1.2c — Migration V3: `enrollments`

- **File:** `V3__create_enrollments.sql`. Composite `PRIMARY KEY (course_id, student_id)`, both `NOT NULL REFERENCES` (`courses (id)`, `users (id)`), no surrogate `id`, as the row says. The primary key is what stops a student enrolling twice.
- **`enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`** — same timestamp convention as `created_at`.
- **Index:** only `idx_enrollments_student_id` is added. Per the FK-index convention from 1.2b, `course_id` needs none (it is the leading column of the primary key), while `student_id` is second and so cannot be served by it — "a student's courses" needs its own index.
- **No check that `student_id` belongs to a `STUDENT`:** roles are validated in the application layer (1.2a), so the DB cannot and should not enforce it; the enrollment endpoint (3.x) does.
- **No `ON DELETE`**, consistent with 1.2b.
- **Tests (`EnrollmentsMigrationTests`):** V3 applied; `enrolled_at` defaulted; second enrollment of the same student in the same course rejected; unknown course and unknown student rejected; each column required (one test per column).
