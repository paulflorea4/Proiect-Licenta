# PR History

Historical, append-only. Every PR opened, in order, with notable review outcomes. Not read every session — see the note in root `CLAUDE.md`. Load this only when auditing past work or tracing why something is the way it is.


- PR #1 — `phase-00/0.1a-repo-skeleton` — commit 0.1a: root README confirmed unchanged, combined `.gitignore` added. Outcome: merged, no review comments.
- PR #2 — `phase-00/0.1b-backend-starter` — commit 0.1b: unmodified Spring Initializr project in `/backend` (Maven, Java 17, Boot 4.1.1, Web/Security/Validation). Outcome: merged, no review comments.
- PR #3 — `phase-00/0.1c-ai-service-skeleton` — commit 0.1c: `/ai-service` FastAPI skeleton (`uv` + `hatchling`, `src/` layout, `GET /health`, one test). Outcome: merged, no review comments.
- PR #4 — `phase-00/0.1d-frontend-skeleton` — commit 0.1d: unmodified `npm create vite@latest` React+TS skeleton in `/frontend` (create-vite 9.2.1). Outcome: merged, no review comments.
- PR #5 — `phase-00/0.1e-infra-placeholder` — commit 0.1e: `/infra` placeholder directory with a short README. Outcome: merged, no review comments.
- PR #6 — `phase-00/0.1f-frontend-tests` (stacked on `phase-00/0.1e-infra-placeholder`, PR #5) — commit 0.1f: Vitest + Testing Library + jsdom for `/frontend`, `npm test`, 3 smoke tests on the template `App`. Added at the human's request (new row, not in the original table). Outcome: merged, no review comments — but into the `0.1e` branch (#5 had already merged, base was not retargeted), so it never reached `main`; carried to `main` by PR #7.
- PR #7 — `phase-00/0.1e-infra-placeholder` → `main` — recovery PR, no new task: carries commit 0.1f (merged by #6 into the 0.1e branch instead of `main`) to `main`, plus a docs-only fix of `current-progress.md` / this file. Outcome: merged, no review comments.
- PR #8 — `phase-00/0.2a-docker-compose-postgres` — commit 0.2a: `infra/docker-compose.yml` (Postgres 16, named volume, healthcheck) and `infra/.env.example`. Required `POSTGRES_PASSWORD` from untracked `infra/.env`; configurable `POSTGRES_PORT`. Outcome: merged, no review comments from the human. A GitGuardian bot comment flagged `infra/docker-compose.yml` line 10 as a hardcoded "Generic Password" — false positive (the `${POSTGRES_PASSWORD:?...}` required-variable check, no value in the repo); no reply made.
- PR #9 — `phase-00/0.2b-readme-docker-compose` — commit 0.2b: root README Postgres paragraph updated to match 0.2a (copy `infra/.env.example` to `infra/.env`, set `POSTGRES_PASSWORD`, optional `POSTGRES_PORT`, then `docker compose up -d`). Outcome: awaiting review.
