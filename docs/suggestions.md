# Suggestions

Historical, append-only. Things that would meaningfully help (a missing MCP connector, a slow test step, a repeated manual chore) noticed while working — comments, not tasks. Not read every session — see the note in root `CLAUDE.md`. Nothing here is installed, configured, or restructured without a task or explicit approval.


- (0.1c) `fastapi.testclient.TestClient` currently emits a `StarletteDeprecationWarning` with `httpx` ("install `httpx2` instead"). Harmless today; switching the dev dependency to `httpx2` would remove it, but that is a new dependency, so it was not done.
- (0.1c) The dev machine only has Python 3.14 while the project targets 3.12. Installing 3.12 (`uv python install 3.12`) would let local runs match CI — not done, it is external software.

- (0.1d) Heads-up for **0.5c**: the current `create-vite` react-ts template (create-vite 9.2.1) ships **oxlint** (`.oxlintrc.json`, `npm run lint` → `oxlint`), not ESLint. 0.5c's literal wording ("ESLint + Prettier") will therefore conflict with the repo state — per the gate table that needs a human choice (keep oxlint, or replace it with ESLint) when we get there. Nothing changed in 0.1d; the output is unmodified.
- (0.1d) The unmodified Vite template has no test runner or `test` script, but 0.4c's CI is "lint + test + build" and the workflow says all projects' tests must pass. A test runner (e.g. Vitest) will have to be introduced by some task — none of the Phase 0 rows names one. That is a new dependency, so it needs your call.

- (0.1f) The "no test runner" gap raised under 0.1d is closed by 0.1f (Vitest). The oxlint-vs-ESLint question for 0.5c remains open.

- (0.2a) The dev machine has a native PostgreSQL listening on 5432, which clashes with the compose default. Handled with `POSTGRES_PORT` (see decisions). Whoever runs the backend or Testcontainers locally should be aware of two Postgres instances; Testcontainers uses random ports, so it is unaffected.
- (0.2a) Root README's Postgres paragraph (from the docs set) predates the required `infra/.env`; 0.2b should fix it rather than this commit editing README out of scope.

- (0.2b) GitGuardian is installed on the repo and flagged `infra/docker-compose.yml` line 10 on PR #8 as a hardcoded "Generic Password". It is a false positive (the `${POSTGRES_PASSWORD:?error message}` required-variable check, no secret value anywhere in the repo). The incident can be marked as a false positive on the GitGuardian dashboard (only the human can do that); the scanner may flag that line again if it is touched. Reword the line if the noise becomes a problem.
- (0.5c) Rows 0.6b, 0.6c and 0.6d in `tasks-phase-00.md` are still "Not started", but PR #16 (commit "0.6b-d") already added `backend/CLAUDE.md`, `ai-service/CLAUDE.md` and `frontend/CLAUDE.md`, and later commits have kept them current. Suggest flipping those three rows to Done (or telling me to) so the task list matches the repo. 0.6a is the next unfinished row.
