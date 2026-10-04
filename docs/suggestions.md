# Suggestions

Historical, append-only. Things that would meaningfully help (a missing MCP connector, a slow test step, a repeated manual chore) noticed while working — comments, not tasks. Not read every session — see the note in root `CLAUDE.md`. Nothing here is installed, configured, or restructured without a task or explicit approval.


- (0.1c) `fastapi.testclient.TestClient` currently emits a `StarletteDeprecationWarning` with `httpx` ("install `httpx2` instead"). Harmless today; switching the dev dependency to `httpx2` would remove it, but that is a new dependency, so it was not done.
- (0.1c) The dev machine only has Python 3.14 while the project targets 3.12. Installing 3.12 (`uv python install 3.12`) would let local runs match CI — not done, it is external software.

- (0.1d) Heads-up for **0.5c**: the current `create-vite` react-ts template (create-vite 9.2.1) ships **oxlint** (`.oxlintrc.json`, `npm run lint` → `oxlint`), not ESLint. 0.5c's literal wording ("ESLint + Prettier") will therefore conflict with the repo state — per the gate table that needs a human choice (keep oxlint, or replace it with ESLint) when we get there. Nothing changed in 0.1d; the output is unmodified.
- (0.1d) The unmodified Vite template has no test runner or `test` script, but 0.4c's CI is "lint + test + build" and the workflow says all projects' tests must pass. A test runner (e.g. Vitest) will have to be introduced by some task — none of the Phase 0 rows names one. That is a new dependency, so it needs your call.
