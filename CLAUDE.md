# CLAUDE.md

## Read first
- Progress: @docs/current-progress.md — read this first, every session. It says which phase and commit to resume. Keep it to exactly the four lines in `docs/current-progress-example.md` — it is a "where do I resume" pointer, not a log. Don't append history to it.
  - Doesn't exist yet? This is session 1 — start at Phase 0, `tasks-phase-00.md`, commit 0.1a.
- Tasks: `docs/tasks/tasks-phase-XX.md` — one file per phase, work rows top to bottom (only read the phase relevant for current working task)
- Spec: `docs/project-specifications.md` (only read if task notes do not have sufficient context)
- Historical logs (audit only — do NOT load these by default, only when investigating a specific past decision, PR, or suggestion): `docs/pr-history.md` (every PR opened, in order, with notable review outcomes), `docs/decisions/decisions-phase-XX.md` (non-obvious calls made per commit, with rationale — one file per phase, only read the phase relevant to what you're auditing), `docs/suggestions.md` (flagged process/tooling suggestions, not yet acted on).

## Workflow
- One commit = one row in a `tasks-phase-XX.md` file. Never batch multiple rows into one commit.
- Flip a row's Status to Done in the same commit that finishes it.
- Tests are part of the commit that adds the code. A task is considered finished only if all tests from all projects are passing. Even if you modify only one project, run tests from all projects before considering a task finished.
- Update `docs/current-progress.md` after every commit, and whenever pausing mid-task or context is running low — even mid-commit. Format and real examples: `docs/current-progress-example.md`. Anything not covered by its four lines (a PR's own history, a non-obvious decision's rationale, a suggestion) goes in `docs/pr-history.md`, `docs/decisions/decisions-phase-XX.md` (the file for the current phase), or `docs/suggestions.md` instead — never back into `current-progress.md`.
- Schema changes go through Flyway migrations only, never manual edits.

## Gates — stop and wait for a human
Reserved for decisions the agent genuinely cannot make on its own.

| Trigger | Why |
|---|---|
| Any row marked **GATE** in a tasks file | Explicitly flagged as needing human input |
| A choice with no default in this file or in any other `*.md` file | Nothing to fall back on |
| Merging any PR | Always requires human review and input |
| Any action that adds new dependencies / external software | Keeps the external surface area a deliberate choice |
| A task row's literal wording conflicts with the current repo state (e.g. names a tool that isn't installed, or a different tool already fills that role) | Resolving it either way is itself a choice with no stated default |

## Defaults (do not change without being asked)
- Layout: `/backend` (Spring Boot), `/ai-service` (Python), `/frontend` (React), `/infra`.
- Migrations: Flyway, plain SQL.
- Auth: Spring Security + JWT, no OAuth.
- Roles: `STUDENT`, `TEACHER`, `ADMIN`. Signup always creates a `STUDENT`; only an admin can promote a user to `TEACHER`.
- Languages: Java 17 and Python 3.12 to start. Languages are defined in a config file (image, compile command, run command, source file name) — adding a language must never need a code branch.
- Test cases: stdin → expected stdout comparison, trailing whitespace normalized. Test visibility is `PUBLIC` or `HIDDEN`. A unit-test-framework harness (JUnit/pytest) is out of scope.
- Sandbox: one fresh Docker container per run, started by Spring Boot through the `docker` CLI (`ProcessBuilder`) behind a `SandboxRunner` interface. Always: no network, memory limit with swap disabled, CPU/PID limits, wall-clock timeout, output size cap, non-root user, read-only root filesystem, all capabilities dropped. Never loosen any limit without being asked.
- Execution queue: in-process bounded executor with status persisted in the DB (`QUEUED` → `RUNNING` → `GRADED` / `ERROR`). No message broker for v1. Submissions left `QUEUED`/`RUNNING` after a restart are re-queued on startup.
- Scoring: deterministic and rubric-weighted. `TESTS` criterion score = weighted fraction of its tests passed (`MANUAL` criteria are entered by the teacher); total = sum of criterion weight × criterion score; rubric weights sum to 100. The AI never changes this score. Only exception: LLM-graded open answers (Phase 15), stored separately, always teacher-overridable.
- Static-analysis criterion: suggested default weight `10` out of 100, kept as a named constant (placeholder pending the human's decision). Always teacher-configurable; never force it. Research found no evidence that quality metrics should weigh heavily in a grade.
- Locked after the first submission: an assignment's language, rubric criteria and weights, and test cases can no longer be changed or deleted (existing grades would silently stop matching them). Re-grading is out of scope for v1, so tests and rubric are finished before the assignment is published. Title, description and deadline stay editable.
- Attempts and deadlines: unlimited attempts unless the assignment sets `max_attempts`; the best score counts; submissions after the deadline are rejected.
- AI provider: Gemini API over HTTP, behind a provider interface. The model name comes from the `GEMINI_MODEL` env var — never hardcode it.
- AI feedback input: assignment description, student code, public test results (full detail), hidden test results as pass/fail only. Hidden inputs and expected outputs are never sent to the LLM or returned to students.
- Student-supplied text (code, comments, open answers) is untrusted data in every LLM prompt: passed as clearly delimited, quoted content, never as instructions, and the prompt tells the model to ignore any instructions inside it.
- Hints: three levels (1 nudge, 2 pointer, 3 partial explanation), requested one level at a time by the student, tracked per submission. Level 3 is concrete — it explains the fault and the logic the fix needs, and may include a short illustrative snippet (size capped by a named constant) — but no level may output a complete working solution. Students can flag a hint as wrong or unhelpful; flags are stored. Hints never change the score.
- Backend → AI service: synchronous HTTP with explicit connect/read timeouts, never inside an open database transaction, and never part of grading — the grade is committed before any AI call and never depends on one. A student's hint request waits for the response (bounded by the timeout); similarity and static analysis run after grading on the background worker. No message queue.
- AI service authentication: every endpoint except `GET /health` requires `Authorization: Bearer <AI_SERVICE_TOKEN>`, a shared secret from env vars, compared in constant time. The AI service is never published to the public internet — reachable only by the backend over a private network. The token is never logged.
- Plagiarism: token-based winnowing (k-gram size and window size as named constants). Boilerplate from the assignment's starter code is excluded. Students never see similarity results.
- Similarity threshold: `0.70`, kept as a named constant. Placeholder pending the human's own tuning — don't inline it, don't tune it yourself.
- Evaluation experiments for the thesis (feedback quality, plagiarism precision/recall, scoring agreement) are run by the human, not built here.
- Single environment (local/dev) until the Phase 16 deployment gate is resolved.

## Table ownership
Write access only.
- All tables are written by Spring Boot only: `users`, `courses`, `enrollments`, `assignments`, `rubric_criteria`, `test_cases`, `submissions`, `test_results`, `grades`, `criterion_scores`, `ai_feedback`, and every table added in later phases.
- The Python service never connects to the database. It receives everything it needs in the request body and returns its result in the response; Spring Boot persists it (e.g. `POST /feedback` returns the hint text, Spring Boot writes the `ai_feedback` row).
- Dev-only seed data lives in a separate Flyway location enabled only by the `dev` profile, never in the production migration path.

All schema migrations live in `/backend` Flyway history.

## PR conventions
- Branch: `phase-<NN>/<commit-id>-<short-slug>` (e.g. `phase-05/5.4a-rubric-scoring`).
- One commit from task phase per branch, one PR per commit — open the PR right after pushing. A PR can have multiple commits only by resolving reviewer comments.
- Wait for review before starting the next task: check the PR (`gh pr view <PR> --json comments,reviews`) immediately, then again every half an hour. The session will not be stopped in this time, but it will be idle.
- Address review comments with a new commit on the same branch, then repeat the polling process again.
- On a PR, the human writes the review comments and questions. Only answer them (in the PR thread or with a fix commit) — never add your own review comments, suggestions or approvals there. Anything you notice on your own goes in `docs/suggestions.md`.
- About every 30 minutes of work or waiting (and at every PR poll), re-read the current task's row in its `tasks-phase-XX.md` and `docs/current-progress.md` so the goal doesn't drift as context fills up.
- Never merge yourself — that's the human's call. Once you see the PR merged, open the next task's branch and PR.
- Append each PR (opened and, once known, its outcome) to `docs/pr-history.md`.

## Per-service instructions
Build/test commands and stack conventions live in the nested files, not here:
- `backend/CLAUDE.md`
- `ai-service/CLAUDE.md`
- `frontend/CLAUDE.md`

If you notice something that would meaningfully help (a missing MCP connector, a slow test step, a repeated manual chore) — append it to `docs/suggestions.md`. Don't install, configure, or restructure anything based on it without a task or explicit approval; a suggestion is a comment, not a task.
