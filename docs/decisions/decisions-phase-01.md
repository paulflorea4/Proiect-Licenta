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

## 1.2d — Migration V4: `assignments`

- **File:** `V4__create_assignments.sql`, same conventions as V1–V3 (identity `BIGINT` id, `TIMESTAMPTZ NOT NULL DEFAULT NOW()` timestamps, FK index `idx_assignments_course_id`, no `ON DELETE`).
- **As the row says:** `language VARCHAR(20)` (no `CHECK`, validated against the languages config in the application, so a new language needs no migration); `max_attempts` and `starter_code` nullable (`NULL` = unlimited attempts / no starter code); `published BOOLEAN NOT NULL DEFAULT FALSE`.
- **Nullability gaps in the row, filled in:** `course_id`, `title` `NOT NULL`; `description` `NOT NULL` (the AI feedback input includes it, and 3.3a takes it as a required field — an empty string is still allowed; this differs from `courses.description` on purpose); `deadline` `NOT NULL` (3.3a requires a future deadline; submissions after it are rejected, so every assignment has one); `time_limit_ms` and `memory_limit_mb` `NOT NULL` with **no DB default** — the sandbox limits are the service's call (3.3a validates them against server-side maximums), and a silent DB default would hide a missing value.
- **No numeric `CHECK`s** (e.g. `max_attempts > 0`, positive limits): range validation belongs to 3.3a's "sane server-side maximums", consistent with keeping validation in the application layer. Revisit if the human wants the DB to enforce it as well.
- **`updated_at` has no trigger:** it defaults to `NOW()` on insert and the application must set it on each update (editing title/description/deadline arrives in Phase 3). A trigger would add a second place where behaviour lives; flagged in the SQL comment.
- **Tests (`AssignmentsMigrationTests`):** V4 applied; new assignment is unpublished with `max_attempts`/`starter_code` null and both timestamps set; optional fields can be set; an unknown language string is accepted (proves no DB restriction); unknown course rejected; each required column (course, title, description, language, deadline, both limits) enforced, one test per column.

## 1.2e — Migration V5: `rubric_criteria`

- **File:** `V5__create_rubric_criteria.sql`, same conventions as V1–V4 (identity `BIGINT` id, FK index `idx_rubric_criteria_assignment_id`, no `ON DELETE`). No timestamps — the row lists none, and criteria are locked after the first submission anyway.
- **As the row says:** `type VARCHAR(30)` with no `CHECK` (validated in code; later phases add types freely); `config JSONB` nullable.
- **`weight INTEGER NOT NULL` — whole points.** The row names no type. The spec says weights sum to 100 and the default static-analysis weight is the integer `10`, so whole numbers make the sum-to-100 check exact (no 33.33 + 33.33 + 33.33 ≠ 100 rounding trouble). Trade-off: a teacher cannot give three criteria a third each. Flagged in the PR; widening to `NUMERIC` later would be a plain migration. The sum rule and non-negativity are enforced by the service (2.x/3.x rubric tasks), not the DB.
- **Other nullability:** `assignment_id`, `name` (`VARCHAR(255)`), `type`, `weight` `NOT NULL`.
- **Tests (`RubricCriteriaMigrationTests`):** V5 applied; generated id and null `config` by default; `config` really is `jsonb` and queryable (`config->>'maxComplexity'`); arbitrary type string accepted (no DB restriction); unknown assignment rejected; each required column enforced, one test per column.

## 1.2f — Migration V6: `test_cases`

- **File:** `V6__create_test_cases.sql`, same conventions as V1–V5 (identity `BIGINT` id, FK indexes, no `ON DELETE`). Both FKs are indexed: `idx_test_cases_assignment_id` and `idx_test_cases_criterion_id` (the latter nullable, used to find a criterion's tests when scoring).
- **As the row says:** `criterion_id` nullable FK to `rubric_criteria (id)` (the "test belongs to a `TESTS` criterion" rule is the service's, 3.5a); `visibility VARCHAR(10)` with no `CHECK`; `weight INTEGER NOT NULL DEFAULT 1`.
- **Nullability gaps in the row, filled in:** `assignment_id`, `name` (`VARCHAR(255)`), `visibility`, `position` `NOT NULL`. `input` and `expected_output` are `TEXT NOT NULL` — an empty string is a legitimate stdin or expected stdout (a program that reads nothing or prints nothing), but `NULL` would be ambiguous.
- **`position INTEGER NOT NULL`, no DB default:** the service sets the next position when creating a test, so a missing value is a bug that should fail loudly, not silently become 0.
- **`weight` is a whole number** like `rubric_criteria.weight` (1.2e); the scoring formula uses the ratio of passed to total test weights, so integers lose nothing.
- **Tests (`TestCasesMigrationTests`):** V6 applied; `weight` defaults to 1; `criterion_id` can be null; empty input/expected output accepted; an arbitrary visibility string accepted (no DB restriction); unknown assignment and unknown criterion rejected; each required column enforced, one test per column. (My first run failed because the "arbitrary visibility" test value was longer than `VARCHAR(10)`; shortened to `OTHER`.)

## 1.2g — Migration V7: `submissions`

- **File:** `V7__create_submissions.sql`, same conventions as V1–V6 (identity `BIGINT` id, FK indexes, no `ON DELETE`).
- **`UNIQUE (assignment_id, student_id, attempt_no)` — not in the row, added on purpose.** 5.1c says `attempt_no` must be computed so two simultaneous requests cannot get the same number; the unique constraint makes the database the final guard (the loser gets a constraint violation to retry or reject) instead of relying on application locking alone.
- **The row's index on `(assignment_id, student_id)` is provided by that unique constraint's index** (those are its leading columns), so attempt counting and the best-score query use it and no second, redundant index is created — the FK-index convention from 1.2b says the same. Only `idx_submissions_student_id` is added, for "all submissions of one student across assignments".
- **`status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'`:** no `CHECK` (validated in code, per the row). The default is `QUEUED` because 5.1a returns a freshly created submission as `QUEUED`; it is the first state of the lifecycle `QUEUED → RUNNING → GRADED/ERROR`.
- **Nullability gaps filled in:** `assignment_id`, `student_id`, `language` (`VARCHAR(20)`, same as `assignments.language`), `source_code` (`TEXT`), `attempt_no` `NOT NULL`; `submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; `started_at` and `finished_at` nullable (set by the worker, 5.2b). `attempt_no` has no default: the service assigns it.
- **`language` is stored on the submission** (copied from the assignment when submitted) so a grade stays interpretable on its own; the service validates it equals the assignment language (5.1c).
- **Tests (`SubmissionsMigrationTests`):** V7 applied; new submission defaults (`QUEUED`, `submitted_at` set, `started_at`/`finished_at` null); arbitrary status accepted; duplicate attempt number for the same student and assignment rejected, while successive attempts and other students may reuse numbers; unknown assignment/student rejected; each required column enforced, one test per column.

## 1.2h — Migration V8: `test_results`

- **File:** `V8__create_test_results.sql`, same conventions as V1–V7 (identity `BIGINT` id, FK indexes, no `ON DELETE`).
- **`UNIQUE (submission_id, test_case_id)` — not in the row, added on purpose:** one result per test per submission, enforced by the database. Its index also serves lookups by `submission_id` (leading column), so no separate index on it; `idx_test_results_test_case_id` is added for the other FK. **Consequence for later tasks:** if a submission is re-run (for example re-queued after a restart, Phase 5), the worker must delete or replace the old `test_results` rows first — otherwise the insert fails, which is the intended loud signal against duplicate results.
- **`status VARCHAR(20) NOT NULL`**, no `CHECK` (validated in code, per the row); the longest value, `RUNTIME_ERROR`/`COMPILE_ERROR`, is 13 characters (a test covers it).
- **`actual_output`, `stderr` (`TEXT`) and `runtime_ms` (`INTEGER`) are nullable:** a compile error has no program output or per-test runtime, and a killed run may have neither. The row calls them truncated by 4.3c's cap, which is the sandbox's job; the column does not enforce a length.
- **No `truncated` flag column.** 4.3c says to "mark truncated output so the comparison doesn't silently pass on a prefix"; how it is marked (an in-band marker in the text, or a column) is 4.3c's call and would be its own migration. Not guessed here.
- **Tests (`TestResultsMigrationTests`):** V8 applied; a result needs only submission, test case and status (output, stderr and runtime default to null); those fields can be stored; the longest status fits; a second result for the same test in the same submission is rejected; unknown submission/test case rejected; each required column enforced, one test per column.
