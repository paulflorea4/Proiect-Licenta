# tasks-phase-16.md — Hardening: Tests, CI, Deployment

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 16.1a | Coverage tool per service (JaCoCo, coverage.py, Vitest coverage) + CI-enforced floor | Not started | Pick one number (e.g. 70%) and apply it consistently — don't let each service set its own bar. |
| 16.2a | Structured JSON logging — Spring Boot (Logback JSON encoder) | Not started | Include submission id as a correlation field on every log line from the worker. |
| 16.2b | Structured JSON logging — Python service | Not started | Never log student code, hidden test content, or `AI_SERVICE_TOKEN`. |
| 16.3a | Retry/backoff around Gemini calls in the AI service | Not started | Free tier — a real risk once many students request hints at once near a deadline. |
| 16.3b | Backend resilience to AI-service failure: timeouts, bounded retries, graceful 503 for hints, analysis and similarity skipped without failing grading | Not started | Verify with a test that stops the AI service mid-run — grading must still complete. |
| 16.4a | Rate limit `/auth/signup` and `/auth/signin` | Not started | Brute-force protection, not load management. |
| 16.4b | Rate limit submissions per student per assignment | Not started | Protects the sandbox host from a student hammering submit. |
| 16.4c | Rate limit hint requests per student | Not started | Bounds LLM cost — the other half of the "hints are on demand" design. |
| 16.5a | Sandbox hardening review: re-run the Phase 4 hostile-program suite against the final configuration and record findings and residual risks | Not started | Findings go in `docs/decisions/decisions-phase-16.md` — raw material for the thesis's security chapter. |
| 16.5b | Extend `docker-compose.yml` to also build/run backend, ai-service, frontend alongside the DB | Not started | One command brings up the whole stack locally — this is what a reviewer runs to sanity-check a PR, not just CI. The ai-service gets no published host port: only the backend reaches it, over the compose network. Blocked on 16.5c if the backend runs in a container. |
| 16.5c | **GATE** — decide how a containerized backend reaches Docker to start sandbox containers | Not started | Mounting the host's Docker socket into the backend container makes a backend compromise root-equivalent on the host. Options: socket mount (document the risk), run the backend outside compose, or a dedicated runner host. This is a genuine security trade-off for the human to choose. Also note that the bind-mounted working directory from 4.3a is resolved on the Docker host, not inside the backend container, so a containerized backend needs a host path shared with it (or the `docker cp` fallback). |
| 16.6a | **GATE** — wait for your hosting choice + credentials | Not started | Nothing deploy-specific gets built before this is resolved. The sandbox needs a host that allows Docker, which rules out some platforms — raise that when asking. Also confirm the platform can keep the ai-service on a private network; a bearer token over plain HTTP is only acceptable on a private network. |
| 16.7a | Deploy step in CI | Not started | Blocked on 16.6a. |
| 16.8a | Health checks wired into whatever the hosting platform expects | Not started | Reuses the `/health` endpoints from 2.2b and 6.1a — don't build new ones. |
| 16.9a | End-to-end smoke test against the full compose stack: sign in → create course and assignment → enroll → submit → graded → hint | Not started | Scripted over the HTTP API, no new dependencies. This is the "does the whole thesis demo work" check. |
